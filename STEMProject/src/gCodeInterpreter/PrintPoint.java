package gCodeInterpreter;

import java.awt.geom.Point2D;

public class PrintPoint extends Point2D.Double {
	double z;
	double speed;
	double extrusion;
	int layerNumber;
	String type;
	double time;
	/**
	 * 
	 * @param x
	 * @param y
	 * @param z
	 * @param speed
	 * @param extrusion
	 * @param layerNumber
	 * @param type
	 */
	public PrintPoint(double x, double y, double z, double speed, double extrusion, int layerNumber, String type) {
		super.x = x;
		super.y = y;
		this.z = z;
		this.speed = speed;
		this.extrusion = extrusion;
		this.layerNumber = layerNumber;
		this.type = type;
	}
	
	public String toString() {
		return "(" + x + ", " + y + ")";
	}
	
	public String toStringVerbose() {
		return "(" + x + ", " + y + ", " + z + ") F: " + speed + " E: " + extrusion;
	}
	
	public PrintPoint clone() {
		return new PrintPoint(this.x, this.y, this.z, this.speed, this.extrusion, this.layerNumber, this.type);
	}

}
