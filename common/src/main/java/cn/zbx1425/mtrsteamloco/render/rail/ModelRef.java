package cn.zbx1425.mtrsteamloco.render.rail;

import cn.zbx1425.mtrsteamloco.data.RailModelProperties;
import cn.zbx1425.mtrsteamloco.data.RailModelRegistry;
import cn.zbx1425.sowcer.model.Model;

/**
 * Immutable reference to a specific model within a RailModelType.
 * Pre-computes hash and caches the resolved model/bounding box for fast rendering lookups.
 */
public final class ModelRef {

    public final String typeKey;
    public final int modelIndex;
    private final int hash;

    private Model cachedModel;
    private Long cachedBoundingBox;

    public ModelRef(String typeKey, int modelIndex) {
        this.typeKey = typeKey;
        this.modelIndex = modelIndex;
        this.hash = typeKey.hashCode() * 31 + modelIndex;
        resolve();
    }

    private void resolve() {
        RailModelProperties props = RailModelRegistry.getProperty(typeKey);
        if (props.getModelCount() == 0) {
            cachedModel = null;
            cachedBoundingBox = 0L;
        } else {
            int idx = modelIndex % props.getModelCount();
            cachedModel = props.uploadedModels.get(idx);
            cachedBoundingBox = props.boundingBoxes.get(idx);
        }
    }

    public Model getModel() {
        return cachedModel;
    }

    public Long getBoundingBox() {
        return cachedBoundingBox;
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ModelRef other)) return false;
        return modelIndex == other.modelIndex && typeKey.equals(other.typeKey);
    }

    @Override
    public String toString() {
        return typeKey + "#" + modelIndex;
    }
}
