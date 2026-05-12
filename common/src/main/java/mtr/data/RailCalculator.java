package mtr.data;

import java.util.ArrayList;
import java.util.List;

public final class RailCalculator {

	public static final double PRECISION = 1e-3;
	/**
	 * Two parallel tangent lines closer than this (in blocks) are treated as collinear,
	 * producing a straight rail. Covers 0.01° quantization error for rails up to ~1000 blocks.
	 */
	private static final double COLLINEAR_TOLERANCE = 0.1;

	private RailCalculator() {
	}

	public static class Vec2 {
		public final double x, z;

		public Vec2(double x, double z) {
			this.x = x;
			this.z = z;
		}

		public Vec2 add(Vec2 other) {
			return new Vec2(x + other.x, z + other.z);
		}

		public Vec2 sub(Vec2 other) {
			return new Vec2(x - other.x, z - other.z);
		}

		public Vec2 rotateRad(double angle) {
			final double cos = Math.cos(angle);
			final double sin = Math.sin(angle);
			return new Vec2(x * cos - z * sin, x * sin + z * cos);
		}

		public double length() {
			return Math.sqrt(x * x + z * z);
		}

		public double distance(Vec2 other) {
			return sub(other).length();
		}

		public double radian() {
			return Math.atan2(z, x);
		}

		public double degree() {
			return Math.toDegrees(radian());
		}

		public Vec2 scale(double factor) {
			return new Vec2(x * factor, z * factor);
		}

		public Vec2 normalize() {
			final double length = length();
			if (length < PRECISION) {
				return new Vec2(0, 0);
			}
			return new Vec2(x / length, z / length);
		}
	}

	public static class Line {
		public final double A, B, C; // Ax + Bz + C = 0

		public Line(Vec2 p1, Vec2 p2) {
			A = p1.z - p2.z;
			B = p2.x - p1.x;
			C = p1.x * p2.z - p2.x * p1.z;
		}

		public Vec2 intersection(Line other) {
			final double det = A * other.B - other.A * B;
			if (Math.abs(det) < PRECISION) {
				return null;
			}
			final double x = (B * other.C - other.B * C) / det;
			final double z = (other.A * C - A * other.C) / det;
			return new Vec2(x, z);
		}

		public boolean parallel(Line other) {
			return Math.abs(A * other.B - other.A * B) < PRECISION;
		}

		public double distance(Vec2 p) {
			return Math.abs(A * p.x + B * p.z + C) / Math.sqrt(A * A + B * B);
		}

		public Line perpendicular(Vec2 p) {
			final Vec2 d = direction().rotateRad(Math.toRadians(90));
			return new Line(p, p.add(d));
		}

		public Vec2 direction() {
			return new Vec2(B, -A).normalize();
		}

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof Line)) {
				return false;
			}
			final Line other = (Line) obj;
			return Math.abs(A * other.B - other.A * B) < PRECISION
					&& Math.abs(A * other.C - other.A * C) < PRECISION
					&& Math.abs(B * other.C - other.B * C) < PRECISION;
		}
	}

	public static class Circle {
		public final Vec2 center;
		public final double radius;

		public Circle(Vec2 center, double radius) {
			this.center = center;
			this.radius = radius;
		}

		public List<Vec2> intersections(Line line) {
			final List<Vec2> result = new ArrayList<>();
			final double d = line.distance(center);
			if (d > radius + PRECISION) {
				return result;
			}
			final Line perp = line.perpendicular(center);
			final Vec2 foot = line.intersection(perp);
			if (foot == null) {
				return result;
			}
			if (Math.abs(d - radius) <= PRECISION) {
				result.add(foot);
				return result;
			}
			final double t = Math.sqrt(radius * radius - d * d);
			final Vec2 dir = line.direction();
			final double dirLength = dir.length();
			if (dirLength < PRECISION) {
				return result;
			}
			final Vec2 unitDir = new Vec2(dir.x / dirLength, dir.z / dirLength);
			final Vec2 delta = new Vec2(unitDir.x * t, unitDir.z * t);
			result.add(foot.add(delta));
			result.add(foot.sub(delta));
			return result;
		}
	}

	public static class Section {
		public final double h, k, r, tStart, tEnd;
		public final boolean reverseT, isStraight;

		public Section() {
			this(0, 0, 0, 0, 0, false, true);
		}

		public Section(double h, double k, double r, double tStart, double tEnd, boolean reverseT, boolean isStraight) {
			this.h = h;
			this.k = k;
			this.r = r;
			this.tStart = tStart;
			this.tEnd = tEnd;
			this.reverseT = reverseT;
			this.isStraight = isStraight;
		}

		public boolean isValid() {
			return Double.isFinite(h) && Double.isFinite(k) && Double.isFinite(r) && Double.isFinite(tStart) && Double.isFinite(tEnd);
		}
	}

	private interface Shape {
		Section toSection();
	}

	private static class Arc implements Shape {
		private final Vec2 center, start, end;
		private final double radius;

		Arc(Vec2 center, Vec2 start, Vec2 end) {
			this.center = center;
			this.radius = center.distance(start);
			this.start = start;
			this.end = end;
		}

		@Override
		public Section toSection() {
			final double h = center.x;
			final double k = center.z;
			final double r = radius;

			final Vec2 startRel = start.sub(center);
			final double thetaStart = Math.atan2(startRel.z, startRel.x);

			final Vec2 endRel = end.sub(center);
			final double thetaEnd = Math.atan2(endRel.z, endRel.x);

			double deltaTheta = thetaEnd - thetaStart;
			deltaTheta = (deltaTheta + Math.PI) % (2 * Math.PI);
			if (deltaTheta < 0) {
				deltaTheta += 2 * Math.PI;
			}
			deltaTheta -= Math.PI;

			final double tStart = thetaStart * r;
			final double tEnd = tStart + deltaTheta * r;
			final boolean reverseT = deltaTheta < 0;

			return new Section(h, k, r, tStart, tEnd, reverseT, false);
		}
	}

	private static class Segment implements Shape {
		private final Vec2 start;
		private final Vec2 end;

		Segment(Vec2 start, Vec2 end) {
			this.start = start;
			this.end = end;
		}

		@Override
		public Section toSection() {
			final Vec2 delta = end.sub(start);
			final double len = delta.length();
			if (len < PRECISION) {
				return new Section(Double.NaN, Double.NaN, Double.NaN, 0, 0, false, true);
			}
			final double h = delta.x / len;
			final double k = delta.z / len;
			final boolean isForm1 = Math.abs(h) >= 0.5 && Math.abs(k) >= 0.5;

			final double r, tStart, tEnd;
			if (isForm1) {
				r = (h * start.z - k * start.x) / (h * h);
				tStart = start.x / h;
				tEnd = end.x / h;
			} else {
				final double div = 2 * h * h - 1;
				r = (h * start.z - k * start.x) / div;
				tStart = (h * start.x - k * start.z) / div;
				tEnd = (h * end.x - k * end.z) / div;
			}

			final boolean reverseT = tStart > tEnd;
			return new Section(h, k, r, tStart, tEnd, reverseT, true);
		}
	}

	public static class Group {
		public final Section first;
		public final Section second;

		public Group(Section first, Section second) {
			this.first = first;
			this.second = second;
		}
	}

	/**
	 * Compute rail geometry (arc + straight segments) connecting two positions with specified tangent directions.
	 *
	 * @param startAngle tangent angle at start, in radians
	 * @param endAngle   tangent angle at end, in radians
	 * @return geometry group, or null if no valid path exists
	 */
	public static Group calculate(double startX, double startZ, double endX, double endZ, double startAngle, double endAngle) {
		return calculateInternal(startX, startZ, endX, endZ, startAngle, endAngle);
	}

	/**
	 * Determine the tangent angle at an undetermined endpoint such that a maximum-radius
	 * single arc connects the two points, tangent to the known direction at the start.
	 *
	 * @param startAngle tangent angle at start in radians (raw, not necessarily oriented toward end)
	 * @return tangent angle at end in degrees, or null if no valid arc exists
	 */
	public static Double calculateMaxRadiusAngle(double startX, double startZ, double endX, double endZ, double startAngle) {
		final Vec2 S = new Vec2(startX, startZ);
		final Vec2 E = new Vec2(endX, endZ);

		final Vec2 vSS1 = new Vec2(1, 0).rotateRad(startAngle);
		final Vec2 S1 = S.add(vSS1);
		final Line SS1 = new Line(S, S1);

		final Line SE = new Line(S, E);

		if (SS1.equals(SE)) {
			return Math.toDegrees(startAngle);
		}

		final Line SD = SS1.perpendicular(S);

		final Vec2 vSE = E.sub(S);
		final Vec2 vSF = vSE.scale(0.5);
		final Vec2 F = S.add(vSF);

		final Vec2 D = SD.intersection(SE.perpendicular(F));

		if (D == null) {
			return null;
		}

		final Line DE = new Line(D, E);
		final Line tangentAtE = DE.perpendicular(E);
		final double dir = tangentAtE.direction().degree();

		final Group group = calculateInternal(startX, startZ, endX, endZ, startAngle, Math.toRadians(dir));

		if (group == null) {
			return null;
		}

		return dir;
	}

	private static Group calculateInternal(double startX, double startZ, double endX, double endZ, double startAngle, double endAngle) {
		final Vec2 S = new Vec2(startX, startZ);
		final Vec2 E = new Vec2(endX, endZ);

		final Vec2 vSS1 = new Vec2(1, 0).rotateRad(startAngle);
		final Vec2 S1 = S.add(vSS1);

		final Vec2 vEE1 = new Vec2(1, 0).rotateRad(endAngle);
		final Vec2 E1 = E.add(vEE1);

		final Line SS1 = new Line(S, S1);
		final Line EE1 = new Line(E, E1);

		if (SS1.parallel(EE1)) {
			if (SS1.equals(EE1) || SS1.distance(E) < COLLINEAR_TOLERANCE) {
				return new Group(new Segment(S, E).toSection(), new Section());
			}

			final Line SE = new Line(S, E);
			final Vec2 vSE = E.sub(S);
			final Vec2 vSD = vSE.scale(0.25);
			final Vec2 D = S.add(vSD);

			final Line l1 = SE.perpendicular(D);
			final Line l2 = SS1.perpendicular(S);
			final Vec2 O1 = l1.intersection(l2);

			final Vec2 vEF = vSD.scale(-1);
			final Vec2 F = E.add(vEF);

			final Line l3 = SE.perpendicular(F);
			final Line l4 = EE1.perpendicular(E);
			final Vec2 O2 = l3.intersection(l4);

			if (l2.equals(l4)) {
				return null;
			}

			if (O1 == null || O2 == null) {
				return null;
			}

			final Vec2 vSM = vSE.scale(0.5);
			final Vec2 M = S.add(vSM);

			return new Group(new Arc(O1, S, M).toSection(), new Arc(O2, M, E).toSection());
		}

		final Vec2 M = SS1.intersection(EE1);

		if (M == null) {
			return null;
		}

		final Vec2 vMS = S.sub(M);
		final Vec2 vME = E.sub(M);
		final double theta = vME.degree() - vMS.degree();
		final double dME = M.distance(E);
		final double dMS = M.distance(S);
		final double diff = dME - dMS;

		if (diff > PRECISION) {
			final Line p1 = SS1.perpendicular(S);
			final Vec2 vMF = vMS.rotateRad(Math.toRadians(theta));
			final Vec2 arcEnd = M.add(vMF);
			final Line p2 = EE1.perpendicular(arcEnd);

			final Vec2 O = p1.intersection(p2);
			if (O == null) {
				return null;
			}

			return new Group(new Arc(O, S, arcEnd).toSection(), new Segment(arcEnd, E).toSection());
		} else if (diff < -PRECISION) {
			final Line p1 = EE1.perpendicular(E);
			final Vec2 vMF = vME.rotateRad(Math.toRadians(-theta));
			final Vec2 arcStart = M.add(vMF);
			final Line p2 = SS1.perpendicular(arcStart);

			final Vec2 O = p1.intersection(p2);
			if (O == null) {
				return null;
			}

			return new Group(new Segment(S, arcStart).toSection(), new Arc(O, arcStart, E).toSection());
		} else {
			final Line p1 = SS1.perpendicular(S);
			final Line p2 = EE1.perpendicular(E);
			final Vec2 O = p1.intersection(p2);
			if (O == null) {
				return null;
			}

			return new Group(new Arc(O, S, E).toSection(), new Section());
		}
	}
}
