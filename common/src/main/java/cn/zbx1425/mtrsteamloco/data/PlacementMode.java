package cn.zbx1425.mtrsteamloco.data;

public enum PlacementMode {
    STRETCH_INTERVAL,
    FIXED_INTERVAL,
    MANUAL;

    public static PlacementMode fromIndex(int index) {
        PlacementMode[] values = values();
        return (index >= 0 && index < values.length) ? values[index] : STRETCH_INTERVAL;
    }
}
