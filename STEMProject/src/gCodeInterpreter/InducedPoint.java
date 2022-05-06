package gCodeInterpreter;

import java.awt.geom.Line2D;
import java.awt.geom.Point2D;

public class InducedPoint extends PrintPoint {
	PrintPoint inductionSource;
	PrintPoint start;
	PrintPoint end;

	public InducedPoint(double x, double y, PrintPoint start, PrintPoint end, PrintPoint inductionSource) {
		super(x, y, start.z + (end.z - start.z) * ((start.x - x) / (start.x - end.x)), start.speed,
				start.extrusion + (end.extrusion - start.extrusion) * ((start.x - x) / (start.x - end.x)),
				start.layerNumber, start.type);
		this.start = start;
		this.end = end;

		this.inductionSource = inductionSource;
	}

	public static InducedPoint induce(PrintPoint start, PrintPoint end, PrintPoint source, double dist) {
		if ((source.distance(start) <= dist && source.distance(end) >= dist)
				|| (source.distance(start) >= dist && source.distance(end) <= dist)) { // if there is a valid point
																						// somewhere on the segment

			PrintPoint closestPoint = getClosestPointOnLine(start, end, source);
			double ptLineDist = Line2D.ptLineDist(start.x, start.y, end.x, end.y, source.x, source.y);
			double legDistance = Math.sqrt(dist * dist - ptLineDist * ptLineDist);
			double slope = (end.y - start.y) / (end.x - start.x);

			double deltaX = Math.sqrt((legDistance * legDistance) / (slope * slope + 1));
			double deltaY = slope * deltaX;

			InducedPoint testPoint1 = new InducedPoint(closestPoint.x + deltaX, closestPoint.y + deltaY, start, end, source);
			InducedPoint testPoint2 = new InducedPoint(closestPoint.x + deltaX, closestPoint.y + deltaY, start, end, source);

			PrintPoint inducedPoint = null;
			
			// check for within bounds of line. This can use AABB, or axis aligned bounding
			// boxes.
			if (lessEqualsE(testPoint1.x, Math.max(start.x, end.x))
					&& greatEqualsE(testPoint1.x, Math.min(start.x, end.x))
					&& lessEqualsE(testPoint1.y, Math.max(start.y, end.y))
					&& greatEqualsE(testPoint1.y, Math.min(start.y, end.y))) {

				inducedPoint = testPoint1;
			} else if (lessEqualsE(testPoint2.x, Math.max(start.x, end.x))
					&& greatEqualsE(testPoint2.x, Math.min(start.x, end.x))
					&& lessEqualsE(testPoint2.y, Math.max(start.y, end.y))
					&& greatEqualsE(testPoint2.y, Math.min(start.y, end.y))) {

				inducedPoint = testPoint2;
			}
			
			if(inducedPoint == null) { //if there is no valid point\
				System.out.println("Fail on inducedpoint after check");
				return null;
			}
			return new InducedPoint(inducedPoint.x, inducedPoint.y, start, end, source);
		} else { // there are no induced points possible on the segment at the given distance
			return null;
		}
	}

	// adapted From
	// http://www.java2s.com/Code/Java/2D-Graphics-GUI/Returnsclosestpointonsegmenttopoint.htm
	public static PrintPoint getClosestPointOnLine(PrintPoint start, PrintPoint end, PrintPoint p) {
		double sx1 = start.x;
		double sy1 = start.y;
		double sx2 = end.x;
		double sy2 = end.y;
		double px = p.x;
		double py = p.y;
		
		double xDelta = sx2 - sx1;
		double yDelta = sy2 - sy1;
		double zDelta = end.z - start.z;
		double eDelta = end.extrusion - start.extrusion;

		if ((xDelta == 0) && (yDelta == 0)) {
			throw new IllegalArgumentException("Segment start equals segment end");
		}

		double u = ((px - sx1) * xDelta + (py - sy1) * yDelta) / (xDelta * xDelta + yDelta * yDelta);
		
		double finX = sx1 + u * xDelta;
		double finY = sy1 + u * yDelta;
		
		double fraction = (finX - start.x) / (end.x - start.x);
		double finZ = start.z + fraction * zDelta;
		double finE = start.extrusion + fraction * eDelta;

		final PrintPoint closestPoint = new PrintPoint(finX, finY, finZ, start.speed, finE, start.layerNumber, start.type);

		return closestPoint;
	}

	public static boolean equalsE(double a, double b) {
		double epsilon = 0.00001;
		return a - b < epsilon;
	}

	public static boolean lessEqualsE(double a, double b) {
		return (a < b || equalsE(a, b));
	}

	public static boolean greatEqualsE(double a, double b) {
		return (a > b || equalsE(a, b));
	}

	public static double dist3D(double x1, double y1, double z1, double x2, double y2, double z2) {
		return Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1));
	}
}
