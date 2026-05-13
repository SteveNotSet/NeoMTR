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

public class RailModelRepeater {

    public String modelKey;
    public RepeaterMode repeaterMode;
    public float offset;
    public boolean offsetFromStart;
    public boolean reversed;
    public float intervalOverride;
    public List<Float> manualPositions;

    public RailModelRepeater() {
        this.modelKey = "";
        this.repeaterMode = RepeaterMode.STRETCH_INTERVAL;
        this.offset = 0;
        this.offsetFromStart = true;
        this.reversed = false;
        this.intervalOverride = 0;
        this.manualPositions = Collections.emptyList();
    }

    public RailModelRepeater(String modelKey, boolean reversed) {
        this();
        this.modelKey = modelKey;
        this.reversed = reversed;
    }

    public RailModelRepeater copy() {
        RailModelRepeater copy = new RailModelRepeater();
        copy.modelKey = this.modelKey;
        copy.repeaterMode = this.repeaterMode;
        copy.offset = this.offset;
        copy.offsetFromStart = this.offsetFromStart;
        copy.reversed = this.reversed;
        copy.intervalOverride = this.intervalOverride;
        copy.manualPositions = new ArrayList<>(this.manualPositions);
        return copy;
    }

    public boolean isLegacyCompatible() {
        return repeaterMode == RepeaterMode.STRETCH_INTERVAL
                && offset == 0
                && offsetFromStart
                && !reversed
                && intervalOverride == 0
                && manualPositions.isEmpty();
    }

    public float resolveInterval(RailModelProperties properties) {
        return intervalOverride > 0 ? intervalOverride : properties.repeatInterval;
    }

    public void toMessagePack(MessagePacker packer) throws IOException {
        packer.packMapHeader(7);
        packer.packString("model_key").packString(modelKey);
        packer.packString("mode").packInt(repeaterMode.ordinal());
        packer.packString("offset").packFloat(offset);
        packer.packString("offset_from_start").packBoolean(offsetFromStart);
        packer.packString("reversed").packBoolean(reversed);
        packer.packString("interval_override").packFloat(intervalOverride);
        packer.packString("manual_positions").packArrayHeader(manualPositions.size());
        for (float pos : manualPositions) {
            packer.packFloat(pos);
        }
    }

    public static RailModelRepeater fromMessagePack(MapValue mapValue) {
        RailModelRepeater repeater = new RailModelRepeater();
        Map<Value, Value> map = mapValue.map();
        for (Map.Entry<Value, Value> entry : map.entrySet()) {
            String key = entry.getKey().asStringValue().asString();
            Value val = entry.getValue();
            switch (key) {
                case "model_key":
                    repeater.modelKey = val.asStringValue().asString();
                    break;
                case "mode":
                    repeater.repeaterMode = RepeaterMode.fromIndex(val.asIntegerValue().asInt());
                    break;
                case "offset":
                    repeater.offset = val.asFloatValue().toFloat();
                    break;
                case "offset_from_start":
                    repeater.offsetFromStart = val.asBooleanValue().getBoolean();
                    break;
                case "reversed":
                    repeater.reversed = val.asBooleanValue().getBoolean();
                    break;
                case "interval_override":
                    repeater.intervalOverride = val.asFloatValue().toFloat();
                    break;
                case "manual_positions":
                    ArrayValue arr = val.asArrayValue();
                    List<Float> positions = new ArrayList<>(arr.size());
                    for (Value v : arr) {
                        positions.add(v.asFloatValue().toFloat());
                    }
                    repeater.manualPositions = positions;
                    break;
            }
        }
        return repeater;
    }

    public void writePacket(FriendlyByteBuf packet) {
        packet.writeUtf(modelKey);
        packet.writeByte(repeaterMode.ordinal());
        packet.writeFloat(offset);
        packet.writeBoolean(offsetFromStart);
        packet.writeBoolean(reversed);
        packet.writeFloat(intervalOverride);
        packet.writeVarInt(manualPositions.size());
        for (float pos : manualPositions) {
            packet.writeFloat(pos);
        }
    }

    public static RailModelRepeater readPacket(FriendlyByteBuf packet) {
        RailModelRepeater repeater = new RailModelRepeater();
        repeater.modelKey = packet.readUtf();
        repeater.repeaterMode = RepeaterMode.fromIndex(packet.readByte());
        repeater.offset = packet.readFloat();
        repeater.offsetFromStart = packet.readBoolean();
        repeater.reversed = packet.readBoolean();
        repeater.intervalOverride = packet.readFloat();
        int count = packet.readVarInt();
        List<Float> positions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            positions.add(packet.readFloat());
        }
        repeater.manualPositions = positions;
        return repeater;
    }

    private static final Map<UUID, List<UndoEntry>> undoSnapshots = new HashMap<>();

    private record UndoEntry(BlockPos posA, BlockPos posB,
                             List<RailModelRepeater> oldPlacementsAB,
                             List<RailModelRepeater> oldPlacementsBA) {}

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
            player.displayClientMessage(Component.literal("No rail found."), false);
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
                finishPropagation(level, player, snapshot, modifiedRails, railwayData, msg,
                        exitNode, currentOffset, modelKey, interval, reversed);
                return;
            }

            BlockPos nextNode = forwardCandidates.get(0);
            Rail nextRail = railwayData.getRail(exitNode, nextNode);
            if (nextRail == null) break;

            int matchingIndex = findMatchingPlacement(nextRail, modelKey, interval);
            if (matchingIndex < 0) {
                String msg = String.format("Propagation stopped at (%d, %d, %d). Exit offset: %.3f. Modified %d rail(s). (no matching repeater on next rail)",
                        exitNode.getX(), exitNode.getY(), exitNode.getZ(),
                        currentOffset, modifiedRails.size());
                finishPropagation(level, player, snapshot, modifiedRails, railwayData, msg,
                        exitNode, currentOffset, modelKey, interval, reversed);
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
                String.format("Propagation complete. Modified %d rail(s).", modifiedRails.size()),
                exitNode, currentOffset, modelKey, interval, reversed);
    }

    public static void undoPropagate(RailwayData railwayData, ServerPlayer player) {
        List<UndoEntry> snapshot = undoSnapshots.remove(player.getUUID());
        if (snapshot == null || snapshot.isEmpty()) {
            player.displayClientMessage(Component.literal("Nothing to undo."), false);
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        List<BlockPos[]> modifiedRails = new ArrayList<>();
        for (UndoEntry entry : snapshot) {
            Rail railAB = railwayData.getRail(entry.posA, entry.posB);
            if (railAB != null) {
                ((RailExtraSupplier) railAB).setRepeaters(entry.oldPlacementsAB);
            }
            Rail railBA = railwayData.getRail(entry.posB, entry.posA);
            if (railBA != null) {
                ((RailExtraSupplier) railBA).setRepeaters(entry.oldPlacementsBA);
            }
            modifiedRails.add(new BlockPos[]{entry.posA, entry.posB});
        }
        broadcastRailUpdates(level, railwayData, modifiedRails);
        player.displayClientMessage(
                Component.literal(String.format("Undone propagation on %d rail(s).", snapshot.size())),
                false);
    }

    private static void snapshotRail(RailwayData railwayData, List<UndoEntry> snapshot,
                                     BlockPos posA, BlockPos posB) {
        Rail railAB = railwayData.getRail(posA, posB);
        Rail railBA = railwayData.getRail(posB, posA);
        List<RailModelRepeater> snapAB = copyPlacementList(railAB);
        List<RailModelRepeater> snapBA = copyPlacementList(railBA);
        snapshot.add(new UndoEntry(posA, posB, snapAB, snapBA));
    }

    private static List<RailModelRepeater> copyPlacementList(Rail rail) {
        if (rail == null) return Collections.emptyList();
        List<RailModelRepeater> result = new ArrayList<>();
        for (RailModelRepeater p : ((RailExtraSupplier) rail).getRepeaters()) {
            result.add(p.copy());
        }
        return result;
    }

    private static int findMatchingPlacement(Rail rail, String modelKey, float interval) {
        List<RailModelRepeater> repeaters = ((RailExtraSupplier) rail).getRepeaters();
        for (int i = 0; i < repeaters.size(); i++) {
            RailModelRepeater p = repeaters.get(i);
            if (p.repeaterMode == RepeaterMode.FIXED_INTERVAL
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
            RailModelRepeater p = ((RailExtraSupplier) railAB).getRepeaters().get(placementIndex);
            p.offset = offset;
            p.offsetFromStart = offsetFromStart;
        }
        if (railBA != null) {
            RailModelRepeater p = ((RailExtraSupplier) railBA).getRepeaters().get(placementIndex);
            p.offset = offset;
            p.offsetFromStart = offsetFromStart;
        }
    }

    private static void applyFixedInterval(RailwayData railwayData,
                                           BlockPos posStart, BlockPos posEnd,
                                           int placementIndex, String modelKey,
                                           float interval, boolean reversed,
                                           float offset, boolean offsetFromStart) {
        RailModelRepeater repeater = new RailModelRepeater();
        repeater.modelKey = modelKey;
        repeater.repeaterMode = RepeaterMode.FIXED_INTERVAL;
        repeater.offset = offset;
        repeater.offsetFromStart = offsetFromStart;
        repeater.reversed = reversed;
        repeater.intervalOverride = interval > 0 ? interval : 0;
        repeater.manualPositions = Collections.emptyList();

        applyRepeaterToRailPair(railwayData, posStart, posEnd, placementIndex, repeater);
    }

    private static void applyRepeaterToRailPair(RailwayData railwayData,
                                                BlockPos posA, BlockPos posB,
                                                int repeaterIndex,
                                                RailModelRepeater repeater) {
        Rail railAB = railwayData.getRail(posA, posB);
        Rail railBA = railwayData.getRail(posB, posA);

        if (railAB != null) {
            ensureRepeaterIndexPresence((RailExtraSupplier) railAB, repeaterIndex);
            ((RailExtraSupplier) railAB).getRepeaters().set(repeaterIndex, repeater.copy());
        }
        if (railBA != null) {
            ensureRepeaterIndexPresence((RailExtraSupplier) railBA, repeaterIndex);
            ((RailExtraSupplier) railBA).getRepeaters().set(repeaterIndex, repeater.copy());
        }
    }

    private static void ensureRepeaterIndexPresence(RailExtraSupplier supplier, int index) {
        List<RailModelRepeater> repeaters = supplier.getRepeaters();
        while (repeaters.size() <= index) {
            repeaters.add(new RailModelRepeater());
        }
    }

    private static void finishPropagation(ServerLevel level, ServerPlayer player,
                                          List<UndoEntry> snapshot,
                                          List<BlockPos[]> modifiedRails,
                                          RailwayData railwayData, String message,
                                          BlockPos terminalNode, float exitOffset,
                                          String modelKey, float interval, boolean reversed) {
        undoSnapshots.put(player.getUUID(), snapshot);
        broadcastRailUpdates(level, railwayData, modifiedRails);
        player.displayClientMessage(Component.literal(message), false);

        final FriendlyByteBuf resultPacket = new FriendlyByteBuf(Unpooled.buffer());
        resultPacket.writeBlockPos(terminalNode);
        resultPacket.writeFloat(exitOffset);
        resultPacket.writeUtf(modelKey);
        resultPacket.writeFloat(interval);
        resultPacket.writeBoolean(reversed);
        Registry.sendToPlayer(player, IPacket.PACKET_PROPAGATE_REPEATER_RESULT, resultPacket);
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
