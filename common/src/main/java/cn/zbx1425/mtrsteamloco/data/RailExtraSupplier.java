package cn.zbx1425.mtrsteamloco.data;

import mtr.data.Rail;
import net.minecraft.util.Mth;

import java.util.List;

public interface RailExtraSupplier {

    boolean getIsSecondaryDir();

    void setIsSecondaryDir(boolean value);

    float getVerticalCurveRadius();

    void setVerticalCurveRadius(float value);

    int getHeight();

    List<RailModelRepeater> getRepeaters();

    void setRepeaters(List<RailModelRepeater> repeaters);

    static float getVTheta(Rail rail, double verticalCurveRadius) {
        double H = Math.abs(((RailExtraSupplier)rail).getHeight());
        double L = rail.getLength();
        double R = verticalCurveRadius;
        return 2 * (float) Mth.atan2(Math.sqrt(H * H - 4 * R * H + L * L) - L, H - 4 * R);
    }

}
