package mtr.data;

import net.minecraft.core.BlockPos;

public final class RailNodeGeometry {

	private RailNodeGeometry() {
	}

	public static RailAngle railFacingAtStartTowardEnd(float rawDegrees, BlockPos posStart, BlockPos posEnd) {
		final float angleDifference = (float) Math.toDegrees(Math.atan2(posEnd.getZ() - posStart.getZ(), posEnd.getX() - posStart.getX()));
		return RailAngle.fromExactAngle(rawDegrees + (RailAngle.similarFacing(angleDifference, rawDegrees) ? 0 : 180));
	}

	public static RailAngle railFacingAtEndTowardStart(float rawDegrees, BlockPos posStart, BlockPos posEnd) {
		final float angleDifference = (float) Math.toDegrees(Math.atan2(posEnd.getZ() - posStart.getZ(), posEnd.getX() - posStart.getX()));
		return RailAngle.fromExactAngle(rawDegrees + (RailAngle.similarFacing(angleDifference, rawDegrees) ? 180 : 0));
	}

}
