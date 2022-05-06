package gCodeInterpreter;

import java.awt.geom.Point2D;
import java.util.ArrayList;

public class ContinuousSection {

	ArrayList<PrintPoint> points = new ArrayList<PrintPoint>();

	public ContinuousSection clone() {
		ContinuousSection c = new ContinuousSection();
		c.points = (ArrayList<PrintPoint>) points.clone();
		return c;
	}

	// adds a point at the point specified.
	public void splitAtPoint(double x, double y, PrintPoint start, PrintPoint end) {

		double fraction = dist2D(x, y, start.x, start.y) / dist2D(start, end); // calculate the fraction of the way the
																				// point is along the path

		double extrusion = start.extrusion + (end.extrusion - start.extrusion) * fraction;
		double z = start.z + (end.z - start.z) * fraction;
		// adjust the extrusion to be the correct portion

		PrintPoint p = new PrintPoint(x, y, z, start.speed, extrusion, start.layerNumber, start.type);
		splitAtPoint(p, start, end);
	}

	// adds a point at the point specified.
	public void splitAtPoint(PrintPoint p, PrintPoint start, PrintPoint end) {
		points.add(points.indexOf(end), p);
		System.out.println(
				" Start: " + start.x + " " + start.y + " P: " + p.x + " " + p.y + " End: " + end.x + " " + end.y);
	}

	

	

	public static double dist3D(double x1, double y1, double z1, double x2, double y2, double z2) {
		return Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1));
	}

	public static double dist2D(double x1, double y1, double x2, double y2) {
		double dist = Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1));
		// System.out.println(dist);
		return dist;
	}

	public static double dist2D(PrintPoint p1, PrintPoint p2) {
		return dist2D(p1.x, p1.y, p2.x, p2.y);
	}

	public void setTimes() {
		for (int i = 1; i < points.size(); i++) {
			points.get(i).time = dist2D(points.get(i - 1), points.get(i)) / (points.get(i - 1).speed / 60);
		}
	}
}
