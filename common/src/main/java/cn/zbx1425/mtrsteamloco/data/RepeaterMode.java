package cn.zbx1425.mtrsteamloco.data;

public enum RepeaterMode {
    STRETCH_INTERVAL,
    FIXED_INTERVAL,
    MANUAL;

    public static RepeaterMode fromIndex(int index) {
        RepeaterMode[] values = values();
        return (index >= 0 && index < values.length) ? values[index] : STRETCH_INTERVAL;
    }
}
