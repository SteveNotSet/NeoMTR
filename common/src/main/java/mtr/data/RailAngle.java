package mtr.data;

import java.util.Arrays;

public final class RailAngle {

	public static final RailAngle E = new RailAngle(0);
	public static final RailAngle SEE = new RailAngle(22.5F);
	public static final RailAngle SE = new RailAngle(45);
	public static final RailAngle SSE = new RailAngle(67.5F);
	public static final RailAngle S = new RailAngle(90);
	public static final RailAngle SSW = new RailAngle(112.5F);
	public static final RailAngle SW = new RailAngle(135);
	public static final RailAngle SWW = new RailAngle(157.5F);
	public static final RailAngle W = new RailAngle(180);
	public static final RailAngle NWW = new RailAngle(202.5F);
	public static final RailAngle NW = new RailAngle(225);
	public static final RailAngle NNW = new RailAngle(247.5F);
	public static final RailAngle N = new RailAngle(270);
	public static final RailAngle NNE = new RailAngle(292.5F);
	public static final RailAngle NE = new RailAngle(315);
	public static final RailAngle NEE = new RailAngle(337.5F);

	public static final int QUADRANT_COUNT = 16;
	/** Tolerance for matching a power-of-22.5 canonical direction */
	public static final float CANONICAL_MATCH_EPSILON = 0.1F;
	/** Tolerance for {@link #nearlySameDirection(RailAngle, RailAngle)}, {@link #isParallel(RailAngle)} */
	public static final float DIRECTION_COMPARE_EPSILON = 0.5F;
	/** All exact angles are rounded to this step (degrees) */
	public static final float QUANTIZATION_STEP = 0.01F;

	private static final RailAngle[] CANONICAL = {
			E, SEE, SE, SSE, S, SSW, SW, SWW, W, NWW, NW, NNW, N, NNE, NE, NEE
	};

	private static final int DEGREES_IN_CIRCLE = 360;
	private static final float ANGLE_INCREMENT = (float) DEGREES_IN_CIRCLE / QUADRANT_COUNT;

	public final float angleDegrees;
	public final double angleRadians;
	public final double sin;
	public final double cos;
	public final double tan;
	public final double halfTan;

	private RailAngle(float angleDegrees) {
		this.angleDegrees = normalizeAngle(angleDegrees);
		angleRadians = Math.toRadians(this.angleDegrees);
		sin = Math.sin(angleRadians);
		cos = Math.cos(angleRadians);
		tan = Math.tan(angleRadians);
		halfTan = Math.tan(angleRadians / 2);
	}

	/**
	 * Quantized to one of 16 directions; used by legacy {@link mtr.block.BlockNode}.
	 */
	public static RailAngle fromAngle(float angleDegrees) {
		return CANONICAL[getQuadrant(angleDegrees, true)];
	}

	/**
	 * Preserves arbitrary bearing (quantized to {@link #QUANTIZATION_STEP}) when not near a
	 * 22.5° canonical step; otherwise returns the shared canonical instance.
	 */
	public static RailAngle fromExactAngle(float angleDegrees) {
		final float quantized = quantizeAngle(angleDegrees);
		for (final RailAngle c : CANONICAL) {
			if (Math.abs(normalizeAngle(quantized - c.angleDegrees)) < CANONICAL_MATCH_EPSILON) {
				return c;
			}
		}
		return new RailAngle(quantized);
	}

	/**
	 * Round to the nearest {@link #QUANTIZATION_STEP} and normalize to [-180, 180).
	 */
	public static float quantizeAngle(float angleDegrees) {
		final float normalized = normalizeAngle(angleDegrees);
		final float quantized = (float) (Math.round((double) normalized * 100.0) / 100.0);
		return normalizeAngle(quantized);
	}

	public static RailAngle[] values() {
		return Arrays.copyOf(CANONICAL, CANONICAL.length);
	}

	public RailAngle getOpposite() {
		return fromExactAngle(angleDegrees + 180);
	}

	public RailAngle add(RailAngle railAngle) {
		return fromExactAngle(angleDegrees + railAngle.angleDegrees);
	}

	public RailAngle sub(RailAngle railAngle) {
		return fromExactAngle(angleDegrees - railAngle.angleDegrees);
	}

	public boolean isParallel(RailAngle railAngle) {
		final float diff = Math.abs(normalizeAngle(angleDegrees - railAngle.angleDegrees));
		return diff < DIRECTION_COMPARE_EPSILON || Math.abs(diff - 180) < DIRECTION_COMPARE_EPSILON;
	}

	public boolean similarFacing(float newAngleDegrees) {
		return similarFacing(angleDegrees, newAngleDegrees);
	}

	public static boolean similarFacing(float angleDegrees1, float angleDegrees2) {
		return Math.abs(normalizeAngle(angleDegrees1 - angleDegrees2)) < DEGREES_IN_CIRCLE / 4F;
	}

	public static boolean nearlySameDirection(RailAngle a, RailAngle b) {
		if (a == b) {
			return true;
		}
		if (a == null || b == null) {
			return false;
		}
		return Math.abs(normalizeAngle(a.angleDegrees - b.angleDegrees)) < DIRECTION_COMPARE_EPSILON;
	}

	public static int getQuadrant(float angleDegrees, boolean include225) {
		final int factor = include225 ? 1 : 2;
		return (Math.round((normalizeAngle(angleDegrees) + DEGREES_IN_CIRCLE) / ANGLE_INCREMENT / factor) % (QUADRANT_COUNT / factor));
	}

	public static float normalizeAngle(float angleDegrees) {
		int additional = 0;
		while (angleDegrees + additional < -DEGREES_IN_CIRCLE / 2F) {
			additional += DEGREES_IN_CIRCLE;
		}
		while (angleDegrees + additional >= DEGREES_IN_CIRCLE / 2F) {
			additional -= DEGREES_IN_CIRCLE;
		}
		return angleDegrees + additional;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof RailAngle)) {
			return false;
		}
		return angleDegrees == ((RailAngle) o).angleDegrees;
	}

	@Override
	public int hashCode() {
		return Float.floatToIntBits(angleDegrees);
	}

	@Override
	public String toString() {
		for (final RailAngle c : CANONICAL) {
			if (c == this || nearlySameDirection(this, c)) {
				return nameForCanonical(c);
			}
		}
		return "RailAngle{" + angleDegrees + "°}";
	}

	private static String nameForCanonical(RailAngle c) {
		if (c == E) return "E";
		if (c == SEE) return "SEE";
		if (c == SE) return "SE";
		if (c == SSE) return "SSE";
		if (c == S) return "S";
		if (c == SSW) return "SSW";
		if (c == SW) return "SW";
		if (c == SWW) return "SWW";
		if (c == W) return "W";
		if (c == NWW) return "NWW";
		if (c == NW) return "NW";
		if (c == NNW) return "NNW";
		if (c == N) return "N";
		if (c == NNE) return "NNE";
		if (c == NE) return "NE";
		if (c == NEE) return "NEE";
		return "?";
	}
}
