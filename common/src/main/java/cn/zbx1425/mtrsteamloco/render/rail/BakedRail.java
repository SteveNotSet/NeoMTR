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

    public record TransformOnBoundary(long dedupHash, Matrix4f matrix) {}

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

            double chordHalfSpan = switch (repeater.repeaterMode) {
                case STRETCH_INTERVAL -> {
                    int N = Math.max(2, Math.round((float) (railLength / interval)) + 1);
                    yield (railLength / (N - 1)) / 2.0;
                }
                case FIXED_INTERVAL -> interval / 2.0;
                case MANUAL -> 0;
            };

            for (IndexedPosition ip : posResult.interior) {
                RailModelRepeater.InstanceModelOverride override =
                        repeater.instanceOverrides.get(ip.originalIndex);

                String instanceKey = resolvedKey;
                RailModelProperties instanceProps = props;
                if (override != null && !override.modelKeyOverride.isEmpty()) {
                    String overrideResolved = RailRenderDispatcher.getModelKeyForRender(rail, override.modelKeyOverride);
                    if (!overrideResolved.equals("null") && !overrideResolved.isEmpty()) {
                        instanceKey = overrideResolved;
                        instanceProps = RailModelRegistry.getProperty(overrideResolved);
                    }
                }

                boolean instanceEffectiveReversed = effectiveReversed ^ (override != null && override.reversed);
                HashMap<Long, ArrayList<Matrix4f>> chunks =
                        interiorModelsByChunks.computeIfAbsent(instanceKey, k -> new HashMap<>());
                addInteriorMatrix(rail, ip.tCanon, isCanonical, railLength,
                    instanceProps, interval, instanceEffectiveReversed, chordHalfSpan, override, chunks);
            }
            for (BoundaryPosition np : posResult.boundary) {
                RailModelRepeater.InstanceModelOverride override =
                        repeater.instanceOverrides.get(np.originalIndex);

                String instanceKey = resolvedKey;
                RailModelProperties instanceProps = props;
                if (override != null && !override.modelKeyOverride.isEmpty()) {
                    String overrideResolved = RailRenderDispatcher.getModelKeyForRender(rail, override.modelKeyOverride);
                    if (!overrideResolved.equals("null") && !overrideResolved.isEmpty()) {
                        instanceKey = overrideResolved;
                        instanceProps = RailModelRegistry.getProperty(overrideResolved);
                    }
                }

                boolean instanceReversed = effectiveReversed ^ (override != null && override.reversed);
                Matrix4f mat = computeMatrix(rail, np.tCanon, isCanonical, railLength,
                    instanceProps, interval, instanceReversed, chordHalfSpan, override);
                Vec3 pos = rail.getPosition(isCanonical ? np.tCanon : (railLength - np.tCanon));
                long chunkId = chunkIdFromWorldPos(Mth.floor((float) pos.x), Mth.floor((float) pos.z));
                HashMap<Long, ArrayList<TransformOnBoundary>> nChunks =
                        boundaryModelsByChunks.computeIfAbsent(instanceKey, k -> new HashMap<>());
                nChunks.computeIfAbsent(chunkId, ignored -> new ArrayList<>())
                        .add(new TransformOnBoundary(
                            np.blockPosHash ^ (repeater.reversed ? Long.MIN_VALUE : 0), mat));
            }
        }
    }

    private record IndexedPosition(double tCanon, int originalIndex) {}
    private record BoundaryPosition(double tCanon, long blockPosHash, int originalIndex) {}
    private record PositionResult(List<IndexedPosition> interior, List<BoundaryPosition> boundary) {}

    private void addInteriorMatrix(Rail rail, double tCanon, boolean isCanonical,
                                   double railLength, RailModelProperties props, float interval,
                                   boolean effectiveReversed, double chordHalfSpan,
                                   RailModelRepeater.InstanceModelOverride override,
                                   HashMap<Long, ArrayList<Matrix4f>> chunks) {
        Matrix4f mat = computeMatrix(rail, tCanon, isCanonical, railLength,
            props, interval, effectiveReversed, chordHalfSpan, override);
        double tLocal = isCanonical ? tCanon : (railLength - tCanon);
        Vec3 pos = rail.getPosition(tLocal);
        long chunkId = chunkIdFromWorldPos(Mth.floor((float) pos.x), Mth.floor((float) pos.z));
        chunks.computeIfAbsent(chunkId, ignored -> new ArrayList<>()).add(mat);
    }

    private Matrix4f computeMatrix(Rail rail, double tCanon, boolean isCanonical,
                                   double railLength, RailModelProperties props, float interval,
                                   boolean effectiveReversed, double chordHalfSpan,
                                   RailModelRepeater.InstanceModelOverride override) {
        double tLocal = isCanonical ? tCanon : (railLength - tCanon);
        Vec3 pos = rail.getPosition(tLocal);

        double tA, tB;
        if (chordHalfSpan > 0) {
            tA = Math.max(0, tLocal - chordHalfSpan);
            tB = Math.min(railLength, tLocal + chordHalfSpan);
        } else {
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
        }
        Vec3 pA = rail.getPosition(tA);
        Vec3 pB = rail.getPosition(tB);

        float xc = (float) pos.x;
        float yc = (float) pos.y + props.yOffset;
        float zc = (float) pos.z;
        float xf = xc + (float)(pB.x - pA.x);
        float yf = yc + (props.tiltToGradient ? (float)(pB.y - pA.y) : 0);
        float zf = zc + (float)(pB.z - pA.z);

        Matrix4f mat = getLookAtMat(xc, yc, zc, xf, yf, zf, effectiveReversed);
        if (override != null) {
            mat.translate(override.offsetX, override.offsetY, override.offsetZ);
        }
        return mat;
    }

    private static final double BOUNDARY_EPSILON = 0.01;

    private PositionResult computePositions(RailModelRepeater p, double L, float I,
                                            BlockPos canonStart, BlockPos canonEnd) {
        List<IndexedPosition> interior = new ArrayList<>();
        List<BoundaryPosition> boundary = new ArrayList<>();

        switch (p.repeaterMode) {
            case STRETCH_INTERVAL: {
                if (L < I * 0.5) {
                    interior.add(new IndexedPosition(L / 2, 0));
                    break;
                }
                int N = Math.max(2, Math.round((float) (L / I)) + 1);
                double actualI = L / (N - 1);
                for (int k = 0; k < N; k++) {
                    double t = k * actualI;
                    if (k == 0) {
                        boundary.add(new BoundaryPosition(t, canonStart.asLong(), k));
                    } else if (k == N - 1) {
                        boundary.add(new BoundaryPosition(t, canonEnd.asLong(), k));
                    } else {
                        interior.add(new IndexedPosition(t, k));
                    }
                }
                break;
            }
            case FIXED_INTERVAL: {
                List<double[]> rawPositions = new ArrayList<>();
                if (p.offsetFromStart) {
                    for (double t = p.offset; t < L - 0.001; t += I) {
                        rawPositions.add(new double[]{t});
                    }
                } else {
                    for (double t = L - p.offset; t > 0.001; t -= I) {
                        rawPositions.add(new double[]{t});
                    }
                    Collections.reverse(rawPositions);
                }
                for (int i = 0; i < rawPositions.size(); i++) {
                    double t = rawPositions.get(i)[0];
                    classifyPosition(t, i, L, canonStart, canonEnd, interior, boundary);
                }
                break;
            }
            case MANUAL: {
                for (int i = 0; i < p.manualPositions.size(); i++) {
                    float pos = p.manualPositions.get(i);
                    if (pos >= 0 && pos <= L) {
                        classifyPosition(pos, i, L, canonStart, canonEnd, interior, boundary);
                    }
                }
                break;
            }
        }
        return new PositionResult(interior, boundary);
    }

    private static void classifyPosition(double t, int idx, double L,
                                         BlockPos canonStart, BlockPos canonEnd,
                                         List<IndexedPosition> interior,
                                         List<BoundaryPosition> boundary) {
        if (t < BOUNDARY_EPSILON) {
            boundary.add(new BoundaryPosition(t, canonStart.asLong(), idx));
        } else if (t > L - BOUNDARY_EPSILON) {
            boundary.add(new BoundaryPosition(t, canonEnd.asLong(), idx));
        } else {
            interior.add(new IndexedPosition(t, idx));
        }
    }

    public static long chunkIdFromWorldPos(float bpX, float bpZ) {
        return ((long) ((int) bpX >> (4 + POS_SHIFT)) << 32) | ((long) ((int) bpZ >> (4 + POS_SHIFT)) & 0xFFFFFFFFL);
    }

    public static long chunkIdFromSectPos(int spX, int spZ) {
        return ((long) (spX >> POS_SHIFT) << 32) | ((long) (spZ >> POS_SHIFT) & 0xFFFFFFFFL);
    }

    public static Matrix4f getLookAtMat(float posX, float posY, float posZ, float tgX, float tgY, float tgZ, boolean reverse) {
        Matrix4f matrix4f = Matrix4f.translation(posX, posY, posZ);

        float dx = tgX - posX;
        float dy = tgY - posY;
        float dz = tgZ - posZ;
        float hDist = (float) Math.sqrt(dx * dx + dz * dz);
        final float yaw = (float) Mth.atan2(dx, dz);
        final float pitch = (float) Mth.atan2(dy, Math.max(hDist, 0.0001f));

        matrix4f.rotateY((reverse ? (float) Math.PI : 0f) + yaw);
        matrix4f.rotateX(reverse ? pitch : -pitch);

        return matrix4f;
    }
}
