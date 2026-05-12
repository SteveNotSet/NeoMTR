package cn.zbx1425.mtrsteamloco.render.rail;

import cn.zbx1425.mtrsteamloco.data.*;
import cn.zbx1425.sowcer.math.Matrix4f;
import cn.zbx1425.sowcer.util.AttrUtil;
import mtr.data.Rail;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class BakedRail {

    public Map<String, HashMap<Long, ArrayList<Matrix4f>>> modelChunks = new HashMap<>();

    public Set<Long> contributedNodeHashes = new HashSet<>();

    public static final int POS_SHIFT = 1;

    public int color;

    public BakedRail(Rail rail, BlockPos posStart, BlockPos posEnd,
                     HashSet<Long> renderedNodeModels) {
        color = AttrUtil.argbToBgr(rail.railType.color | 0xFF000000);
        boolean isCanonical = posStart.asLong() <= posEnd.asLong();
        BlockPos canonStart = isCanonical ? posStart : posEnd;
        BlockPos canonEnd = isCanonical ? posEnd : posStart;

        List<RailModelPlacement> placements = ((RailExtraSupplier) rail).getModelPlacements();
        double railLength = rail.getLength();

        for (RailModelPlacement placement : placements) {
            String resolvedKey = RailRenderDispatcher.getModelKeyForRender(rail, placement.modelKey);
            if (resolvedKey.equals("null") || resolvedKey.isEmpty()) continue;

            RailModelProperties props = RailModelRegistry.getProperty(resolvedKey);
            float interval = placement.resolveInterval(props);
            float yOffset = props.yOffset;

            List<Double> canonPositions = computePositions(
                    placement, railLength, interval, renderedNodeModels,
                    canonStart, canonEnd);

            HashMap<Long, ArrayList<Matrix4f>> chunks =
                    modelChunks.computeIfAbsent(resolvedKey, k -> new HashMap<>());

            boolean effectiveReversed = isCanonical ? placement.reversed : !placement.reversed;

            for (double tCanon : canonPositions) {
                double tLocal = isCanonical ? tCanon : (railLength - tCanon);
                Vec3 pos = rail.getPosition(tLocal);
                double tFwd = Math.min(tLocal + 0.01, railLength);
                Vec3 fwd = rail.getPosition(tFwd);

                float xc = (float) pos.x;
                float yc = (float) pos.y + yOffset;
                float zc = (float) pos.z;
                float xf = (float) fwd.x;
                float yf = (float) fwd.y + yOffset;
                float zf = (float) fwd.z;

                chunks.computeIfAbsent(chunkIdFromWorldPos(Mth.floor(xc), Mth.floor(zc)),
                                ignored -> new ArrayList<>())
                        .add(getLookAtMat(xc, yc, zc, xf, yf, zf, interval, effectiveReversed));
            }
        }
    }

    private List<Double> computePositions(RailModelPlacement p, double L, float I,
                                          HashSet<Long> renderedNodeModels,
                                          BlockPos canonStart, BlockPos canonEnd) {
        switch (p.placementMode) {
            case STRETCH_INTERVAL: {
                if (L < I * 0.5) {
                    return Collections.singletonList(L / 2);
                }
                int N = Math.max(2, Math.round((float) (L / I)) + 1);
                double actualI = L / (N - 1);
                List<Double> result = new ArrayList<>(N);
                for (int k = 0; k < N; k++) {
                    double t = k * actualI;
                    if (k == 0) {
                        long hash = nodeModelHash(p.modelKey, canonStart);
                        if (!renderedNodeModels.add(hash)) continue;
                        contributedNodeHashes.add(hash);
                    } else if (k == N - 1) {
                        long hash = nodeModelHash(p.modelKey, canonEnd);
                        if (!renderedNodeModels.add(hash)) continue;
                        contributedNodeHashes.add(hash);
                    }
                    result.add(t);
                }
                return result;
            }
            case FIXED_INTERVAL: {
                List<Double> result = new ArrayList<>();
                if (p.offsetFromStart) {
                    for (double t = p.offset; t < L - 0.001; t += I) {
                        result.add(t);
                    }
                } else {
                    for (double t = L - p.offset; t > 0.001; t -= I) {
                        result.add(t);
                    }
                    Collections.reverse(result);
                }
                return result;
            }
            case MANUAL: {
                List<Double> result = new ArrayList<>(p.manualPositions.size());
                for (float pos : p.manualPositions) {
                    if (pos >= 0 && pos <= L) {
                        result.add((double) pos);
                    }
                }
                return result;
            }
            default:
                return Collections.emptyList();
        }
    }

    private static long nodeModelHash(String modelKey, BlockPos pos) {
        return (long) modelKey.hashCode() * 31L + pos.hashCode();
    }

    public static long chunkIdFromWorldPos(float bpX, float bpZ) {
        return ((long) ((int) bpX >> (4 + POS_SHIFT)) << 32) | ((long) ((int) bpZ >> (4 + POS_SHIFT)) & 0xFFFFFFFFL);
    }

    public static long chunkIdFromSectPos(int spX, int spZ) {
        return ((long) (spX >> POS_SHIFT) << 32) | ((long) (spZ >> POS_SHIFT) & 0xFFFFFFFFL);
    }

    public static Matrix4f getLookAtMat(float posX, float posY, float posZ, float tgX, float tgY, float tgZ, float len, boolean reverse) {
        Matrix4f matrix4f = Matrix4f.translation(posX, posY, posZ);

        final float yaw = (float) Mth.atan2(tgX - posX, tgZ - posZ);
        final float pitch = (float) Mth.atan2(tgY - posY, len / 2);

        matrix4f.rotateY((reverse ? (float) Math.PI : 0f) + yaw);
        matrix4f.rotateX(reverse ? pitch : -pitch);

        return matrix4f;
    }
}
