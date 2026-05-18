package cn.zbx1425.mtrsteamloco.render.rail;

import cn.zbx1425.sowcer.batch.BatchManager;
import cn.zbx1425.sowcer.batch.ShaderProp;
import cn.zbx1425.sowcer.math.Matrix4f;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.HashMap;

public abstract class RailChunkBase implements Closeable {

    public Long chunkId;
    public AABB boundingBox;
    public HashMap<BakedRail, RailTranformList> containingRails = new HashMap<>();

    public final ModelRef modelRef;

    protected float modelYMin;
    protected float modelYMax;

    public boolean isDirty = false;
    public boolean bufferBuilt = false;
    public double cameraDistManhattanXZ = 0;

    public RailChunkBase(long chunkId, ModelRef modelRef) {
        this.chunkId = chunkId;
        this.modelRef = modelRef;
        Long boundary = modelRef.getBoundingBox();
        modelYMin = Float.intBitsToFloat((int)(boundary >> 32));
        modelYMax = Float.intBitsToFloat((int)(boundary & 0xFFFFFFFFL));
        setBoundingBox(0, 0);
    }

    protected void setBoundingBox(float yMin, float yMax) {
        int posXMin = (int)(chunkId >> 32) << (4 + BakedRail.POS_SHIFT);
        int posZMin = (int)(chunkId & 0xFFFFFFFFL) << (4 + BakedRail.POS_SHIFT);
        int span = 1 << (4 + BakedRail.POS_SHIFT);
        boundingBox = new AABB(posXMin, yMin + modelYMin - 1, posZMin,
                posXMin + span, yMax + modelYMax + 1, posZMin + span);
    }

    public boolean isEven() {
        return ((int)(chunkId >> 32) + (int)(chunkId & 0xFFFFFFFFL)) % 2 == 0;
    }

    public ChunkPos getChunkPos() {
        return new ChunkPos((int)(chunkId >> 32) << BakedRail.POS_SHIFT, (int)(chunkId & 0xFFFFFFFFL) << BakedRail.POS_SHIFT);
    }

    public boolean containsYSection(int yMin, int yMax) {
        return (yMin << 4) < boundingBox.minY || (yMax << 4) > boundingBox.maxY;
    }

    public double getCameraDistManhattanXZ(Vec3 cameraPos) {
        cameraDistManhattanXZ = Math.abs(cameraPos.x - (boundingBox.minX + boundingBox.maxX) / 2)
                + Math.abs(cameraPos.z - (boundingBox.minZ + boundingBox.maxZ) / 2);
        return cameraDistManhattanXZ;
    }

    public void addRail(BakedRail rail, ModelRef modelRef) {
        ArrayList<Matrix4f> interior = new ArrayList<>();
        ArrayList<BakedRail.TransformOnBoundary> boundary = new ArrayList<>();

        HashMap<Long, ArrayList<Matrix4f>> interiorChunks = rail.interiorModelsByChunks.get(modelRef);
        if (interiorChunks != null) {
            ArrayList<Matrix4f> matrices = interiorChunks.get(chunkId);
            if (matrices != null) interior.addAll(matrices);
        }
        HashMap<Long, ArrayList<BakedRail.TransformOnBoundary>> boundaryChunks = rail.boundaryModelsByChunks.get(modelRef);
        if (boundaryChunks != null) {
            ArrayList<BakedRail.TransformOnBoundary> boundaryMatrices = boundaryChunks.get(chunkId);
            if (boundaryMatrices != null) boundary.addAll(boundaryMatrices);
        }

        if (!interior.isEmpty() || !boundary.isEmpty()) {
            containingRails.put(rail, new RailTranformList(interior, boundary));
            isDirty = true;
        }
    }

    public void removeRail(BakedRail rail, ModelRef modelRef) {
        containingRails.remove(rail);
        isDirty = true;
    }

    public void rebuildBuffer(Level world) {
        isDirty = false;
        bufferBuilt = true;
    }
    public abstract void enqueue(BatchManager batchManager, ShaderProp shaderProp);

    @Override
    public void close() {

    }

    public record RailTranformList(ArrayList<Matrix4f> interiorTransforms, ArrayList<BakedRail.TransformOnBoundary> boundaryTransforms) {

    }
}
