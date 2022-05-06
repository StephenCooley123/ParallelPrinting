package gCodeInterpreter;

import java.util.ArrayList;

public class DoubleMove {
	
	PrintPoint start1;
	PrintPoint start2;
	PrintPoint end1;
	PrintPoint end2;
	boolean isTravel = false;
	
	
	double x1;
	double y1;
	double x2;
	double y2;
	
	public DoubleMove(PrintPoint start1, PrintPoint end1, PrintPoint start2, PrintPoint end2) {
		this.start1 = start1;
		this.start2 = start2;
		this.end1 = end1;
		this.end2 = end2;
		
		this.x1 = (start1.x + start2.x) / 2.0;
		this.y1 = (start1.y + start2.y) / 2.0;
		this.x2 = (end1.x + end2.x) / 2.0;
		this.y2 = (end1.y + end2.y) / 2.0;
	}
	
	public DoubleMove reverse() {
		return new DoubleMove(end1, start1, end2, start2);
	}
	
	public ArrayList<PrintPoint> points() {
		ArrayList<PrintPoint> points = new ArrayList<PrintPoint>();
		points.add(start1);
		points.add(end1);
		points.add(start2);
		points.add(end2);
		return points; 
	}
	public void updateMidpoints() {
		x1 = (start1.x + start2.x) / 2.0;
		y1 = (start1.y + start2.y) / 2.0;
		x2 = (end1.x + end2.x) / 2.0;
		y2 = (end1.y + end2.y) / 2.0;
	}
	
	
	
}
