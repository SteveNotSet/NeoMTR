package cn.zbx1425.mtrsteamloco.data;

import io.netty.buffer.Unpooled;
import mtr.Registry;
import mtr.data.Rail;
import mtr.data.RailwayData;
import mtr.packet.IPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.msgpack.core.MessagePacker;
import org.msgpack.value.Value;
import org.msgpack.value.ArrayValue;
import org.msgpack.value.MapValue;

import java.io.IOException;
import java.util.*;

public class RailModelPlacement {

    public String modelKey;
    public PlacementMode placementMode;
    public float offset;
    public boolean offsetFromStart;
    public boolean reversed;
    public float intervalOverride;
    public List<Float> manualPositions;

    public RailModelPlacement() {
        this.modelKey = "";
        this.placementMode = PlacementMode.STRETCH_INTERVAL;
        this.offset = 0;
        this.offsetFromStart = true;
        this.reversed = false;
        this.intervalOverride = 0;
        this.manualPositions = Collections.emptyList();
    }

    public RailModelPlacement(String modelKey, boolean reversed) {
        this();
        this.modelKey = modelKey;
        this.reversed = reversed;
    }

    public RailModelPlacement copy() {
        RailModelPlacement copy = new RailModelPlacement();
        copy.modelKey = this.modelKey;
        copy.placementMode = this.placementMode;
        copy.offset = this.offset;
        copy.offsetFromStart = this.offsetFromStart;
        copy.reversed = this.reversed;
        copy.intervalOverride = this.intervalOverride;
        copy.manualPositions = new ArrayList<>(this.manualPositions);
        return copy;
    }

    public boolean isLegacyCompatible() {
        return placementMode == PlacementMode.STRETCH_INTERVAL
                && offset == 0
                && offsetFromStart
                && intervalOverride == 0
                && manualPositions.isEmpty();
    }

    public float resolveInterval(RailModelProperties properties) {
        return intervalOverride > 0 ? intervalOverride : properties.repeatInterval;
    }

    public void toMessagePack(MessagePacker packer) throws IOException {
        packer.packMapHeader(7);
        packer.packString("model_key").packString(modelKey);
        packer.packString("mode").packInt(placementMode.ordinal());
        packer.packString("offset").packFloat(offset);
        packer.packString("offset_from_start").packBoolean(offsetFromStart);
        packer.packString("reversed").packBoolean(reversed);
        packer.packString("interval_override").packFloat(intervalOverride);
        packer.packString("manual_positions").packArrayHeader(manualPositions.size());
        for (float pos : manualPositions) {
            packer.packFloat(pos);
        }
    }

    public static RailModelPlacement fromMessagePack(MapValue mapValue) {
        RailModelPlacement placement = new RailModelPlacement();
        Map<Value, Value> map = mapValue.map();
        for (Map.Entry<Value, Value> entry : map.entrySet()) {
            String key = entry.getKey().asStringValue().asString();
            Value val = entry.getValue();
            switch (key) {
                case "model_key":
                    placement.modelKey = val.asStringValue().asString();
                    break;
                case "mode":
                    placement.placementMode = PlacementMode.fromIndex(val.asIntegerValue().asInt());
                    break;
                case "offset":
                    placement.offset = val.asFloatValue().toFloat();
                    break;
                case "offset_from_start":
                    placement.offsetFromStart = val.asBooleanValue().getBoolean();
                    break;
                case "reversed":
                    placement.reversed = val.asBooleanValue().getBoolean();
                    break;
                case "interval_override":
                    placement.intervalOverride = val.asFloatValue().toFloat();
                    break;
                case "manual_positions":
                    ArrayValue arr = val.asArrayValue();
                    List<Float> positions = new ArrayList<>(arr.size());
                    for (Value v : arr) {
                        positions.add(v.asFloatValue().toFloat());
                    }
                    placement.manualPositions = positions;
                    break;
            }
        }
        return placement;
    }

    public void writePacket(FriendlyByteBuf packet) {
        packet.writeUtf(modelKey);
        packet.writeByte(placementMode.ordinal());
        packet.writeFloat(offset);
        packet.writeBoolean(offsetFromStart);
        packet.writeBoolean(reversed);
        packet.writeFloat(intervalOverride);
        packet.writeVarInt(manualPositions.size());
        for (float pos : manualPositions) {
            packet.writeFloat(pos);
        }
    }

    public static RailModelPlacement readPacket(FriendlyByteBuf packet) {
        RailModelPlacement placement = new RailModelPlacement();
        placement.modelKey = packet.readUtf();
        placement.placementMode = PlacementMode.fromIndex(packet.readByte());
        placement.offset = packet.readFloat();
        placement.offsetFromStart = packet.readBoolean();
        placement.reversed = packet.readBoolean();
        placement.intervalOverride = packet.readFloat();
        int count = packet.readVarInt();
        List<Float> positions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            positions.add(packet.readFloat());
        }
        placement.manualPositions = positions;
        return placement;
    }

    private static final Map<UUID, List<UndoEntry>> undoSnapshots = new HashMap<>();

    private record UndoEntry(BlockPos posA, BlockPos posB,
                             List<RailModelPlacement> oldPlacementsAB,
                             List<RailModelPlacement> oldPlacementsBA) {}

    /**
     * Server-side propagation: starting from the rail (railStart -> railEnd),
     * compute the exit offset and propagate forward, only modifying the offset
     * on rails that already have a matching FIXED_INTERVAL entry (same modelKey,
     * same intervalOverride). Stops at junctions, dead ends, or mismatches.
     *
     * Direction: propagates away from railStart (the node the player clicked).
     */
    public static void propagate(RailwayData railwayData, ServerPlayer player,
                                 BlockPos railStart, BlockPos railEnd,
                                 int placementIndex, String modelKey,
                                 float interval, boolean reversed, float initialOffset) {
        ServerLevel level = (ServerLevel) player.level();
        List<UndoEntry> snapshot = new ArrayList<>();
        List<BlockPos[]> modifiedRails = new ArrayList<>();

        BlockPos entryNode = railStart;
        BlockPos exitNode = railEnd;
        final int MAX_PROPAGATION_STEPS = 1000;

        Rail firstRail = railwayData.getRail(entryNode, exitNode);
        if (firstRail == null) {
            player.displayClientMessage(Component.literal("No rail found."), true);
            return;
        }

        snapshotRail(railwayData, snapshot, entryNode, exitNode);
        boolean isCanonical = entryNode.asLong() <= exitNode.asLong();
        applyFixedInterval(railwayData, entryNode, exitNode,
                placementIndex, modelKey, interval, reversed,
                initialOffset, isCanonical);
        modifiedRails.add(new BlockPos[]{entryNode, exitNode});

        float currentOffset = computeExitOffset(initialOffset, firstRail.getLength(), interval);

        for (int step = 1; step < MAX_PROPAGATION_STEPS; step++) {
            Set<BlockPos> connections = railwayData.getRailConnectionsFrom(exitNode);
            connections.remove(entryNode);

            Vec3 exitTangent = computeExitTangent(firstRail);

            List<BlockPos> forwardCandidates = new ArrayList<>();
            boolean hasBackwardConnections = false;
            for (BlockPos candidate : connections) {
                Rail candidateRail = railwayData.getRail(exitNode, candidate);
                if (candidateRail == null) continue;
                Vec3 candidateTangent = computeEntryTangent(candidateRail);
                double dot = exitTangent.x * candidateTangent.x + exitTangent.z * candidateTangent.z;
                if (dot > 0) {
                    forwardCandidates.add(candidate);
                } else {
                    hasBackwardConnections = true;
                }
            }

            if (forwardCandidates.size() != 1 || hasBackwardConnections) {
                String msg = String.format("Propagation stopped at (%d, %d, %d). Exit offset: %.3f. Modified %d rail(s).",
                        exitNode.getX(), exitNode.getY(), exitNode.getZ(),
                        currentOffset, modifiedRails.size());
                if (forwardCandidates.isEmpty()) {
                    msg += " (dead end)";
                } else if (hasBackwardConnections) {
                    msg += " (junction: reverse-side rails present)";
                } else {
                    msg += String.format(" (%d branches)", forwardCandidates.size());
                }
                finishPropagation(level, player, snapshot, modifiedRails, railwayData, msg);
                return;
            }

            BlockPos nextNode = forwardCandidates.get(0);
            Rail nextRail = railwayData.getRail(exitNode, nextNode);
            if (nextRail == null) break;

            int matchingIndex = findMatchingPlacement(nextRail, modelKey, interval);
            if (matchingIndex < 0) {
                String msg = String.format("Propagation stopped at (%d, %d, %d). Exit offset: %.3f. Modified %d rail(s). (no matching placement on next rail)",
                        exitNode.getX(), exitNode.getY(), exitNode.getZ(),
                        currentOffset, modifiedRails.size());
                finishPropagation(level, player, snapshot, modifiedRails, railwayData, msg);
                return;
            }

            snapshotRail(railwayData, snapshot, exitNode, nextNode);
            boolean nextIsCanonical = exitNode.asLong() <= nextNode.asLong();
            setPlacementOffset(railwayData, exitNode, nextNode, matchingIndex,
                    currentOffset, nextIsCanonical);
            modifiedRails.add(new BlockPos[]{exitNode, nextNode});

            currentOffset = computeExitOffset(currentOffset, nextRail.getLength(), interval);
            entryNode = exitNode;
            exitNode = nextNode;
            firstRail = nextRail;
        }

        finishPropagation(level, player, snapshot, modifiedRails, railwayData,
                String.format("Propagation complete. Modified %d rail(s).", modifiedRails.size()));
    }

    public static void undoPropagate(RailwayData railwayData, ServerPlayer player) {
        List<UndoEntry> snapshot = undoSnapshots.remove(player.getUUID());
        if (snapshot == null || snapshot.isEmpty()) {
            player.displayClientMessage(Component.literal("Nothing to undo."), true);
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        List<BlockPos[]> modifiedRails = new ArrayList<>();
        for (UndoEntry entry : snapshot) {
            Rail railAB = railwayData.getRail(entry.posA, entry.posB);
            if (railAB != null) {
                ((RailExtraSupplier) railAB).setModelPlacements(entry.oldPlacementsAB);
            }
            Rail railBA = railwayData.getRail(entry.posB, entry.posA);
            if (railBA != null) {
                ((RailExtraSupplier) railBA).setModelPlacements(entry.oldPlacementsBA);
            }
            modifiedRails.add(new BlockPos[]{entry.posA, entry.posB});
        }
        broadcastRailUpdates(level, railwayData, modifiedRails);
        player.displayClientMessage(
                Component.literal(String.format("Undone propagation on %d rail(s).", snapshot.size())),
                true);
    }

    private static void snapshotRail(RailwayData railwayData, List<UndoEntry> snapshot,
                                     BlockPos posA, BlockPos posB) {
        Rail railAB = railwayData.getRail(posA, posB);
        Rail railBA = railwayData.getRail(posB, posA);
        List<RailModelPlacement> snapAB = copyPlacementList(railAB);
        List<RailModelPlacement> snapBA = copyPlacementList(railBA);
        snapshot.add(new UndoEntry(posA, posB, snapAB, snapBA));
    }

    private static List<RailModelPlacement> copyPlacementList(Rail rail) {
        if (rail == null) return Collections.emptyList();
        List<RailModelPlacement> result = new ArrayList<>();
        for (RailModelPlacement p : ((RailExtraSupplier) rail).getModelPlacements()) {
            result.add(p.copy());
        }
        return result;
    }

    private static int findMatchingPlacement(Rail rail, String modelKey, float interval) {
        List<RailModelPlacement> placements = ((RailExtraSupplier) rail).getModelPlacements();
        for (int i = 0; i < placements.size(); i++) {
            RailModelPlacement p = placements.get(i);
            if (p.placementMode == PlacementMode.FIXED_INTERVAL
                    && p.modelKey.equals(modelKey)
                    && Math.abs(p.intervalOverride - interval) < 0.001f) {
                return i;
            }
        }
        return -1;
    }

    private static void setPlacementOffset(RailwayData railwayData,
                                           BlockPos posA, BlockPos posB,
                                           int placementIndex,
                                           float offset, boolean offsetFromStart) {
        Rail railAB = railwayData.getRail(posA, posB);
        Rail railBA = railwayData.getRail(posB, posA);
        if (railAB != null) {
            RailModelPlacement p = ((RailExtraSupplier) railAB).getModelPlacements().get(placementIndex);
            p.offset = offset;
            p.offsetFromStart = offsetFromStart;
        }
        if (railBA != null) {
            RailModelPlacement p = ((RailExtraSupplier) railBA).getModelPlacements().get(placementIndex);
            p.offset = offset;
            p.offsetFromStart = offsetFromStart;
        }
    }

    private static void applyFixedInterval(RailwayData railwayData,
                                           BlockPos posStart, BlockPos posEnd,
                                           int placementIndex, String modelKey,
                                           float interval, boolean reversed,
                                           float offset, boolean offsetFromStart) {
        RailModelPlacement placement = new RailModelPlacement();
        placement.modelKey = modelKey;
        placement.placementMode = PlacementMode.FIXED_INTERVAL;
        placement.offset = offset;
        placement.offsetFromStart = offsetFromStart;
        placement.reversed = reversed;
        placement.intervalOverride = interval > 0 ? interval : 0;
        placement.manualPositions = Collections.emptyList();

        applyPlacementToRail(railwayData, posStart, posEnd, placementIndex, placement);
    }

    private static void applyPlacementToRail(RailwayData railwayData,
                                             BlockPos posA, BlockPos posB,
                                             int placementIndex,
                                             RailModelPlacement placement) {
        Rail railAB = railwayData.getRail(posA, posB);
        Rail railBA = railwayData.getRail(posB, posA);

        if (railAB != null) {
            ensurePlacementIndex((RailExtraSupplier) railAB, placementIndex);
            ((RailExtraSupplier) railAB).getModelPlacements().set(placementIndex, placement.copy());
        }
        if (railBA != null) {
            ensurePlacementIndex((RailExtraSupplier) railBA, placementIndex);
            ((RailExtraSupplier) railBA).getModelPlacements().set(placementIndex, placement.copy());
        }
    }

    private static void ensurePlacementIndex(RailExtraSupplier supplier, int index) {
        List<RailModelPlacement> placements = supplier.getModelPlacements();
        while (placements.size() <= index) {
            placements.add(new RailModelPlacement());
        }
    }

    private static void finishPropagation(ServerLevel level, ServerPlayer player,
                                          List<UndoEntry> snapshot,
                                          List<BlockPos[]> modifiedRails,
                                          RailwayData railwayData, String message) {
        undoSnapshots.put(player.getUUID(), snapshot);
        broadcastRailUpdates(level, railwayData, modifiedRails);
        player.displayClientMessage(Component.literal(message), true);
    }

    private static void broadcastRailUpdates(ServerLevel level, RailwayData railwayData,
                                             List<BlockPos[]> modifiedRails) {
        for (BlockPos[] pair : modifiedRails) {
            Rail railAB = railwayData.getRail(pair[0], pair[1]);
            Rail railBA = railwayData.getRail(pair[1], pair[0]);
            if (railAB == null || railBA == null) continue;

            final FriendlyByteBuf outbound = new FriendlyByteBuf(Unpooled.buffer());
            outbound.writeUtf(railAB.transportMode.toString());
            outbound.writeBlockPos(pair[0]);
            outbound.writeBlockPos(pair[1]);
            railAB.writePacket(outbound);
            railBA.writePacket(outbound);
            outbound.writeLong(0);

            for (ServerPlayer levelPlayer : level.players()) {
                Registry.sendToPlayer(levelPlayer, IPacket.PACKET_CREATE_RAIL, outbound);
            }
        }
    }

    /**
     * O_next = (I - (L - O) % I) % I, with special case for O >= L.
     */
    static float computeExitOffset(float offset, double railLength, float interval) {
        if (offset >= railLength) {
            return offset - (float) railLength;
        }
        double remainder = (railLength - offset) % interval;
        return (float) ((interval - remainder) % interval);
    }

    /**
     * Tangent at the exit node (t=L end), pointing forward (away from the rail).
     * facingEnd points backward (toward start), so we negate.
     */
    private static Vec3 computeExitTangent(Rail rail) {
        return new Vec3(-rail.facingEnd.cos, 0, -rail.facingEnd.sin);
    }

    /**
     * Tangent at the entry node (t=0 end) of the rail, using the rail's facingStart angle.
     */
    private static Vec3 computeEntryTangent(Rail rail) {
        return new Vec3(rail.facingStart.cos, 0, rail.facingStart.sin);
    }
}
