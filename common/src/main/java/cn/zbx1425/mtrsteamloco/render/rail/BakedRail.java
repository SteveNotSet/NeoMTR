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

    public record TransformOnBoundary(long blockPosHash, boolean reversed, Matrix4f matrix) {}

    public Map<String, HashMap<Long, ArrayList<Matrix4f>>> interiorModelsByChunks = new HashMap<>();
    public Map<String, HashMap<Long, ArrayList<TransformOnBoundary>>> boundaryModelsByChunks = new HashMap<>();

    public static final int POS_SHIFT = 1;

    public int color;

    public BakedRail(Rail rail, BlockPos posStart, BlockPos posEnd) {
        color = AttrUtil.argbToBgr(rail.railType.color | 0xFF000000);
        boolean isCanonical = posStart.asLong() <= posEnd.asLong();
        BlockPos canonStart = isCanonical ? posStart : posEnd;
        BlockPos canonEnd = isCanonical ? posEnd : posStart;

        boolean isSecondaryDir = ((RailExtraSupplier) rail).getIsSecondaryDir();
        List<RailModelRepeater> repeaters = ((RailExtraSupplier) rail).getRepeaters();
        double railLength = rail.getLength();

        for (RailModelRepeater repeater : repeaters) {
            String resolvedKey = RailRenderDispatcher.getModelKeyForRender(rail, repeater.modelKey);
            if (resolvedKey.equals("null") || resolvedKey.isEmpty()) continue;

            RailModelProperties props = RailModelRegistry.getProperty(resolvedKey);
            float interval = repeater.resolveInterval(props);

            boolean effectiveReversed = isSecondaryDir ^ repeater.reversed;

            PositionResult posResult = computePositions(
                    repeater, railLength, interval, canonStart, canonEnd);

            HashMap<Long, ArrayList<Matrix4f>> chunks =
                    interiorModelsByChunks.computeIfAbsent(resolvedKey, k -> new HashMap<>());
            HashMap<Long, ArrayList<TransformOnBoundary>> nChunks =
                    boundaryModelsByChunks.computeIfAbsent(resolvedKey, k -> new HashMap<>());

            for (double tCanon : posResult.interior) {
                addInteriorMatrix(rail, tCanon, isCanonical, railLength,
                    props, interval, effectiveReversed, chunks);
            }
            for (BoundaryPosition np : posResult.boundary) {
                Matrix4f mat = computeMatrix(rail, np.tCanon, isCanonical, railLength,
                    props, interval, effectiveReversed);
                Vec3 pos = rail.getPosition(isCanonical ? np.tCanon : (railLength - np.tCanon));
                long chunkId = chunkIdFromWorldPos(Mth.floor((float) pos.x), Mth.floor((float) pos.z));
                nChunks.computeIfAbsent(chunkId, ignored -> new ArrayList<>())
                        .add(new TransformOnBoundary(np.blockPosHash, effectiveReversed, mat));
            }
        }
    }

    private record BoundaryPosition(double tCanon, long blockPosHash) {}
    private record PositionResult(List<Double> interior, List<BoundaryPosition> boundary) {}

    private void addInteriorMatrix(Rail rail, double tCanon, boolean isCanonical,
                                   double railLength, RailModelProperties props, float interval,
                                   boolean effectiveReversed,
                                   HashMap<Long, ArrayList<Matrix4f>> chunks) {
        Matrix4f mat = computeMatrix(rail, tCanon, isCanonical, railLength,
            props, interval, effectiveReversed);
        double tLocal = isCanonical ? tCanon : (railLength - tCanon);
        Vec3 pos = rail.getPosition(tLocal);
        long chunkId = chunkIdFromWorldPos(Mth.floor((float) pos.x), Mth.floor((float) pos.z));
        chunks.computeIfAbsent(chunkId, ignored -> new ArrayList<>()).add(mat);
    }

    private Matrix4f computeMatrix(Rail rail, double tCanon, boolean isCanonical,
                                   double railLength, RailModelProperties props, float interval,
                                   boolean effectiveReversed) {
        double tLocal = isCanonical ? tCanon : (railLength - tCanon);
        Vec3 pos = rail.getPosition(tLocal);

        double tA, tB;
        if (tLocal + 0.01 <= railLength) {
            tA = tLocal;
            tB = tLocal + 0.01;
        } else if (tLocal - 0.01 >= 0) {
            tA = tLocal - 0.01;
            tB = tLocal;
        } else {
            tA = 0;
            tB = railLength;
        }
        Vec3 pA = rail.getPosition(tA);
        Vec3 pB = rail.getPosition(tB);

        float xc = (float) pos.x;
        float yc = (float) pos.y + props.yOffset;
        float zc = (float) pos.z;
        float xf = xc + (float)(pB.x - pA.x);
        float yf = yc + (props.tiltToGradient ? (float)(pB.y - pA.y) : 0);
        float zf = zc + (float)(pB.z - pA.z);

        return getLookAtMat(xc, yc, zc, xf, yf, zf, interval, effectiveReversed);
    }

    private PositionResult computePositions(RailModelRepeater p, double L, float I,
                                            BlockPos canonStart, BlockPos canonEnd) {
        List<Double> interior = new ArrayList<>();
        List<BoundaryPosition> boundary = new ArrayList<>();

        switch (p.repeaterMode) {
            case STRETCH_INTERVAL: {
                if (L < I * 0.5) {
                    interior.add(L / 2);
                    break;
                }
                int N = Math.max(2, Math.round((float) (L / I)) + 1);
                double actualI = L / (N - 1);
                for (int k = 0; k < N; k++) {
                    double t = k * actualI;
                    if (k == 0) {
                        boundary.add(new BoundaryPosition(t, canonStart.asLong()));
                    } else if (k == N - 1) {
                        boundary.add(new BoundaryPosition(t, canonEnd.asLong()));
                    } else {
                        interior.add(t);
                    }
                }
                break;
            }
            case FIXED_INTERVAL: {
                if (p.offsetFromStart) {
                    for (double t = p.offset; t < L - 0.001; t += I) {
                        interior.add(t);
                    }
                } else {
                    for (double t = L - p.offset; t > 0.001; t -= I) {
                        interior.add(t);
                    }
                    Collections.reverse(interior);
                }
                break;
            }
            case MANUAL: {
                for (float pos : p.manualPositions) {
                    if (pos >= 0 && pos <= L) {
                        interior.add((double) pos);
                    }
                }
                break;
            }
        }
        return new PositionResult(interior, boundary);
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
