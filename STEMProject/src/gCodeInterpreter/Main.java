package gCodeInterpreter;

import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.JFrame;

import java.awt.BasicStroke;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.RenderingHints.Key;
import java.awt.image.BufferedImage;
import java.awt.geom.*;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Main extends Thread {
	double primaryDistance = 10;
	double secondaryDistance = 20;
	ArrayList<String> lines = new ArrayList<String>();
	ArrayList<Layer> layers = new ArrayList<Layer>();
	File f;

	public static void main(String[] args) {
		long startTime = System.currentTimeMillis();

		if (args.length > 0) {
			int cores = Math.max(2, Runtime.getRuntime().availableProcessors() - 4);
			System.out.println(cores + " Logical Threads Available");
			for (String s : args) {

				Main main = new Main(new File(s));
				main.run();
			}
			
		} else {
			Main main = new Main(readFile());
			main.run();
		}
		System.out.println("Total Execution Time: " + (System.currentTimeMillis() - startTime));

	}

	public Main(File f) {
		this.f = f;
	}

	/**
	 *
	 */
	
	public void run() {

		try {
			BufferedReader br = new BufferedReader(new FileReader(f));
			String line = br.readLine();

			line = br.readLine();

			while (line != null) {
				lines.add(line + " ");
				line = br.readLine();
			}

			// System.out.println("Read All Lines");
			br.close();

		} catch (IOException e) {
			System.err.println("File Not Found.");
		}
		// go to first layer

		layers.add(new Layer());
		while (!lines.get(0).contains(";LAYER:0")) {
			lines.remove(0);
		}
		lines.remove(0);// remove the first LAYER:0 line
		lines.remove(0);// remove the M127 S0 line

		// separates the layers by line using the layer comments
		String startType = "SETUP";
		int layer = 0;
		for (String l : lines) {
			if (l.equals("M140 S0")) { // M140 S0 is end of gcode
				break;
			}
			if (l.contains("LAYER")) {
				layer++;
				// System.out.println("Layer: " + layer);

				layers.add(new Layer());
			}
			layers.get(layer).lines.add(l);
			if (startType.equals("SETUP") && l.contains("TYPE")) {
				startType = l.substring(l.indexOf("TYPE") + 5, l.length() - 1);
			}
		}
		for (Layer l : layers) {
			l.layerNumber = layers.indexOf(l);
		}

		// System.out.println("Assigned Layers");

		// splits layers into continuous segments
		/// System.out.println(layers.get(0).lines.get(0));
		PrintPoint previous = new PrintPoint(0, 0, 0, 1000, 0, 0, "START"); // default
		for (Layer l : layers) {

			previous = parseLayer(l, previous);
			// System.out.println("Parsing layer " + l.layerNumber);
			int numPointsInLayer = 0;
			int numSectionsInLayer = 0;
			for (ContinuousSection c : l.sections) {
				numSectionsInLayer++;
				numPointsInLayer += c.points.size();
			}
			// System.out.println("Layer has " + numSectionsInLayer + " sections and " +
			// numPointsInLayer + " points");
		}
		// System.out.println("Layers Parsed!");

		double dist = 0;
		for (Layer l : layers) {
			PrintPoint lastPoint = null;
			for (ContinuousSection c : l.sections) {
				c.setTimes();
				if (lastPoint != null) {
					dist += dist2D(lastPoint.x, lastPoint.y, c.points.get(0).x, c.points.get(0).y);
				}
				for (int i = 1; i < c.points.size(); i++) {
					dist += dist2D(c.points.get(i - 1).x, c.points.get(i - 1).y, c.points.get(i).x, c.points.get(i).y);
				}
			}
		}
		// System.out.println("Total Print Distance: " + dist);

		// generate point cloud of endpoints of segments for the layer specified

		// remove any lines which have 0 length
		int nullLines = 0;
		for (Layer l : layers) {
			for (ContinuousSection c : l.sections) {
				for (int i = c.points.size() - 1; i > 0; i--) {
					PrintPoint first = c.points.get(i);
					PrintPoint second = c.points.get(i - 1);
					if (Math.abs(first.x - second.x) < 0.0001 && Math.abs(first.y - second.y) < 0.0001
							&& Math.abs(first.z - second.z) < 0.0001) {
						c.points.remove(first);
						nullLines++;
					}
				}
			}
		}

		// MAKING PARALLEL PRINTS
		double sumDoubleDistance = 0;
		double sumQuadDistance = 0;
		for (Layer l : layers) { // for each layer
			// System.out.println("PARALLELIZING LAYER " + l.layerNumber);
			ArrayList<DoubleMove> moves = generateDoubleMoves(l, primaryDistance);
			l.doublePath = moves;

			Layer quadLayer = movesToLayer(moves, l, l.layerNumber);
			quadLayer.layerNumber = l.layerNumber;

			// System.out.println(quadLayer.sections.size());

			ArrayList<DoubleMove> quadMoves = generateDoubleMoves(quadLayer, secondaryDistance);
			l.quadPath = quadMoves;
			for (DoubleMove m : moves) {
				m.points();
			}
			for (DoubleMove m : quadMoves) {
				m.points();
			}

			sumDoubleDistance += getTotalDistanceTraveledMoves(moves);
			sumQuadDistance += getTotalDistanceTraveledMoves(quadMoves);
		}

		// System.out.println("Total Distance Double: " + sumDoubleDistance);
		// System.out.println("Total Distance Single: " + dist);
		// System.out.println("RATIO:" + (sumDoubleDistance / dist));
		double ratio2 = (sumDoubleDistance / dist);
		double ratio4 = (sumQuadDistance / dist);
		System.out.println(
				f.toString().substring(f.toString().lastIndexOf('\\')) + " RATIO2: " + ratio2 + " RATIO4: " + ratio4);

		for (Layer l : layers) {

			double maxX = Double.MIN_VALUE;
			double maxY = Double.MIN_VALUE;
			for (ContinuousSection c : l.sections) {
				for (PrintPoint p : c.points) {
					// find the minimum centered box the thing fits in
					if (Math.abs(p.x) > maxX) {
						maxX = Math.abs(p.x);
					}
					if (Math.abs(p.y) > maxY) {
						maxY = Math.abs(p.y);
					}
				}
			}
			double xScalar = 30;
			double yScalar = 30;
			double xOffset = maxX * xScalar * 2;
			double yOffset = maxY * yScalar * 2;
			BufferedImage output = new BufferedImage((int) (maxX * xScalar * 4), (int) (maxY * yScalar * 4),
					BufferedImage.TYPE_INT_RGB);
			Graphics2D g = output.createGraphics();

			g.setStroke(new BasicStroke(17));

			for (DoubleMove d : l.quadPath) {

				if (d.isTravel) {
					g.setColor(new Color(0, 128, 0));
				} else {
					g.setColor(Color.GREEN);
				}

				// g.setColor(new Color(255, 0, 0));
				g.drawLine((int) (d.start1.x * xScalar + xOffset), (int) (d.start1.y * yScalar + yOffset),
						(int) (d.end1.x * xScalar + xOffset), (int) (d.end1.y * yScalar + yOffset)); // //
																										// g.setColor(new
																										// Color(0, 255,
																										// 0));
				if (d.isTravel) {
					g.setColor(new Color(128, 0, 0));
				} else {
					g.setColor(Color.RED);
				}
				g.drawLine((int) (d.start2.x * xScalar + xOffset), (int) (d.start2.y * yScalar + yOffset),
						(int) (d.end2.x * xScalar + xOffset), (int) (d.end2.y * yScalar + yOffset));

			}

			// double parallel moves
			g.setStroke(new BasicStroke(10));
			for (DoubleMove d : l.doublePath) {

				if (d.isTravel) {
					g.setColor(new Color(128, 128, 128));
				} else {
					g.setColor(Color.WHITE);
				}

				g.drawLine((int) (d.x1 * xScalar + xOffset), (int) (d.y1 * yScalar + yOffset),
						(int) (d.x2 * xScalar + xOffset), (int) (d.y2 * yScalar + yOffset));

				g.setColor(Color.YELLOW);
				g.drawLine((int) (d.start1.x * xScalar + xOffset), (int) (d.start1.y * yScalar + yOffset),
						(int) (d.end1.x * xScalar + xOffset), (int) (d.end1.y * yScalar + yOffset)); // g.setColor(new
																										// Color(0,255,
				g.setColor(Color.MAGENTA);																						// 0));
				g.drawLine((int) (d.start2.x * xScalar + xOffset), (int) (d.start2.y * yScalar + yOffset),
						(int) (d.end2.x * xScalar + xOffset), (int) (d.end2.y * yScalar + yOffset));
			}
			g.setColor(Color.BLUE);
			g.setStroke(new BasicStroke(3));
			for (ContinuousSection c : l.sections) {
				for (int i = 0; i < c.points.size() - 1; i++) {
					g.drawLine((int) (c.points.get(i).x * xScalar + xOffset),
							(int) (c.points.get(i).y * yScalar + yOffset),
							(int) (c.points.get(i + 1).x * xScalar + xOffset),
							(int) (c.points.get(i + 1).y * yScalar + yOffset));

				}
			}

			boolean generateImage = true;
			if (generateImage) {

				File imageFile = new File("AllParallel" + l.layerNumber + ".png");
				try {
					ImageIO.write(output, "PNG", imageFile);
					System.out.println("Image Written");
				} catch (IOException e) {

					e.printStackTrace();
				}
				System.out.println("Image Generated");
			}
		}
	}

	private Layer movesToLayer(ArrayList<DoubleMove> moves, Layer originalLayer, int indexOf) {
		ArrayList<ContinuousSection> sections = new ArrayList<ContinuousSection>();

		// this is an arraylist of continuous chains of double moves
		ArrayList<ArrayList<DoubleMove>> sectionsToParse = new ArrayList<ArrayList<DoubleMove>>();
		sectionsToParse.add(new ArrayList<DoubleMove>());
		int ex = 1;
		for (DoubleMove[] d : originalLayer.parallelMoves) {
			ContinuousSection c = new ContinuousSection();
			PrintPoint p0 = new PrintPoint(d[0].x1, d[0].y1, 0, 0, ex++, indexOf, "doublemove");
			PrintPoint p1 = new PrintPoint(d[2].x2, d[2].y2, 0, 0, ex++, indexOf, "doublemove");
			c.points.add(p0);
			c.points.add(p1);
			sections.add(c);
		}

		for (DoubleMove d : originalLayer.singularMoves) {
			ContinuousSection c = new ContinuousSection();
			PrintPoint p0 = new PrintPoint(d.x1, d.y1, 0, 0, ex++, indexOf, "doublemove");
			PrintPoint p1 = new PrintPoint(d.x2, d.y2, 0, 0, ex++, indexOf, "doublemove");
			c.points.add(p0);
			c.points.add(p1);
			sections.add(c);
		}

		// convert moves to continuous sections
		/*
		 * for (int m = moves.size() - 1; m >= 0; m--) { if (moves.get(m).isTravel) {
		 * moves.remove(m); } }
		 */

		int nullSections = 0;
		for (int i = 0; i < sections.size(); i++) {
			ContinuousSection c = sections.get(i);
			if (c.points.size() <= 1) {
				nullSections++;
				sections.remove(c);
				i--;
			}
		}
		// System.out.println("nullsections: " + nullSections);

		/*
		 * for(int i = 0; i < moves.size(); i+= 0) { if(moves.get(i).isTravel) {
		 * PrintPoint p2 = new PrintPoint(moves.get(0).x2, moves.get(0).y2, 0, 0, 0,
		 * indexOf, "doublemove");
		 * 
		 * s.points.add(p2); sections.add(s); s = new ContinuousSection(); }
		 * 
		 * else { PrintPoint p1 = new PrintPoint(moves.get(0).x1, moves.get(0).y1, 0, 0,
		 * 0, indexOf, "doublemove"); s.points.add(p1); } moves.remove(i); }
		 */

		Layer l = new Layer();
		l.sections = sections;

		double maxX = Double.MIN_VALUE;
		double maxY = Double.MIN_VALUE;
		for (ContinuousSection s : l.sections) {
			for (PrintPoint p : s.points) {
				// find the minimum centered box the thing fits in
				if (Math.abs(p.x) > maxX) {
					maxX = Math.abs(p.x);
				}
				if (Math.abs(p.y) > maxY) {
					maxY = Math.abs(p.y);
				}
			}
		}

		double xScalar = 30;
		double yScalar = 30;
		double xOffset = maxX * xScalar * 2;
		double yOffset = maxY * yScalar * 2;

		BufferedImage output = new BufferedImage((int) (maxX * xScalar * 4), (int) (maxY * yScalar * 4),
				BufferedImage.TYPE_INT_RGB);
		Graphics2D g = output.createGraphics();

		g.setColor(new Color(0, 0, 255));
		g.setStroke(new BasicStroke(3));
		for (ContinuousSection s : l.sections) {
			g.setColor(
					new Color((int) (Math.random() * 256), (int) (Math.random() * 256), (int) (Math.random() * 256)));
			for (int i = 0; i < s.points.size() - 1; i++) {
				g.drawLine((int) (s.points.get(i).x * xScalar + xOffset), (int) (s.points.get(i).y * yScalar + yOffset),
						(int) (s.points.get(i + 1).x * xScalar + xOffset),
						(int) (s.points.get(i + 1).y * yScalar + yOffset));

			}
		}

		boolean generateImage = false;
		if (generateImage) {

			File imageFile = new File("QuadParallel" + indexOf + ".png");
			try {
				ImageIO.write(output, "PNG", imageFile);
				System.out.println("Image Written");
			} catch (IOException e) {

				e.printStackTrace();
			}
			System.out.println("Image Generated");
		}

		return l;
	}

	private double getTotalDistanceTraveledMoves(ArrayList<DoubleMove> moves) {
		// TODO Auto-generated method stub
		double sum = 0;
		for (int i = 0; i < moves.size(); i++) {
			sum += dist2D(moves.get(i).x1, moves.get(i).y1, moves.get(i).x2, moves.get(i).y2);
		}
		return sum;
	}

	private double getTotalDistanceTraveledSections(ArrayList<ContinuousSection> sections) {

		double sum = 0;
		for (ContinuousSection c : sections) {
			ArrayList<PrintPoint> points = c.points;
			for (int i = 0; i < points.size() - 1; i++) {
				sum += dist2D(points.get(i).x, points.get(i).y, points.get(i + 1).x, points.get(i + 1).x);
			}
		}
		return sum;
	}

	private ArrayList<DoubleMove> generateDoubleMoves(Layer l, double distance) {

		ArrayList<DoubleMove> moves = new ArrayList<DoubleMove>();
		ArrayList<ContinuousSection> pSections = new ArrayList<ContinuousSection>();
		ArrayList<ContinuousSection> otherSections = new ArrayList<ContinuousSection>();

		// only gets the straight sections which could be eligible for parallelization,
		// ie. those in the infill or top or bottom layers.
		for (ContinuousSection c : l.sections) {

			if (c.points.get(1).type.equals("SKIN") || c.points.get(1).type.equals("FILL")
					|| c.points.get(1).type.equals("doublemove")) { // the two types of

				// potentially parallel
				// lines
				double angle = Math
						.abs(Math.atan2(c.points.get(1).y - c.points.get(0).y, c.points.get(1).x - c.points.get(0).x));
				// System.out.println("Target Angle: " + angle);
				boolean isStraight = true;
				for (int i = 1; i < c.points.size(); i++) {
					double measuredAngle = Math.abs(Math.atan2(c.points.get(i).y - c.points.get(i - 1).y,
							c.points.get(i).x - c.points.get(i - 1).x));
					// System.out.println("Measured Angle: " + measuredAngle);
					if (!(Math.abs(measuredAngle - angle) < 1)) { // if the section is not straight
						isStraight = false;

						break;
					}
				}
				if (isStraight) {
					pSections.add(c);

					// System.out.println("Straight Section");
				} else {
					otherSections.add(c);
				}

			}

		}
		// System.out.println("sections: " + l.sections.size());
		// System.out.println("pSections: " + pSections.size());
		// System.out.println("otherSections: " + otherSections.size());

		ArrayList<ContinuousSection> backupSections = (ArrayList<ContinuousSection>) pSections.clone();

		ArrayList<DoubleMove[]> moveCandidates = new ArrayList<DoubleMove[]>();
		ArrayList<ContinuousSection> leftoverMoves = new ArrayList<ContinuousSection>();

		while (pSections.size() > 0) {
			ContinuousSection c1 = pSections.get(0);
			double minDist = Double.MAX_VALUE;

			for (ContinuousSection c : pSections) {
				if (Line2D.ptSegDist(c.points.get(0).x, c.points.get(0).y, c.points.get(1).x, c.points.get(0).y,
						-100000, -100000) < minDist) {
					c1 = c;
					minDist = Line2D.ptSegDist(c.points.get(0).x, c.points.get(0).y, c.points.get(1).x,
							c.points.get(0).y, -100000, -100000);
				}
			}
			// System.out.println("Got Closest");

			DoubleMove[] bestPair = { null, null, null };
			double bestRatio = 0;
			ContinuousSection bestOther = null;
			for (ContinuousSection c2 : pSections) {

				if (c1 != c2) { // get all pairs of sections which are not the same
					double angle1 = Math.abs(Math.atan2(c1.points.get(1).y - c1.points.get(0).y,
							c1.points.get(1).x - c1.points.get(0).x));
					double angle2 = Math.abs(Math.atan2(c2.points.get(1).y - c2.points.get(0).y,
							c2.points.get(1).x - c2.points.get(0).x));
					if (Math.abs(angle1 - angle2) < 0.01 && c1.points.get(0).speed == c2.points.get(0).speed) {
						// the lines are parallel, with a tolerance, and the same speed
						// generate potential move
						// the lines start at c.get(0).x, c.get(0).y, and end at c.get(size).x,
						// c.get(size).y
						// now the lines have to overlap on the parallel

						// the lines are 1-2 and 3-4
						PrintPoint p1 = c1.points.get(0);
						PrintPoint p2 = c1.points.get(c1.points.size() - 1);

						PrintPoint p3 = c2.points.get(0);
						PrintPoint p4 = c2.points.get(c2.points.size() - 1);

						// do the lines have a section which can be printed in parallel. Necessary for
						// the order of the printing segments later on

						double slope = (p1.y - p2.y) / (p1.x - p2.x);
						double extremeX;
						double extremeY;
						double yIntercept1 = -1 * slope * p1.x + p1.y;
						double yIntercept2 = -1 * slope * p3.x + p3.y;
						double yIntercept = (yIntercept1 + yIntercept2) / 2.0;
						if (slope == Float.NaN) {
							extremeX = (p1.x + p2.x) / 2.0;
							extremeY = -100000;
						} else {
							extremeX = -100000;
							extremeY = slope * extremeX + yIntercept;
						}

						// reorder the points so p1 is closer than p2 to the extreme, and p3 is closer
						// than p4 to the extreme.
						// this way the lines point in the same direction.

						ArrayList<PrintPoint> compareTwo = new ArrayList<PrintPoint>();

						compareTwo.add(p1);
						compareTwo.add(p2);
						if (returnClosestPoint(compareTwo, extremeX, extremeY) == p2) {
							PrintPoint temp = p1;
							p1 = p2;
							p2 = temp;
						}
						compareTwo.clear();
						// p1 always before p2

						compareTwo.add(p3);
						compareTwo.add(p4);
						if (returnClosestPoint(compareTwo, extremeX, extremeY) == p4) {
							PrintPoint temp = p3;
							p3 = p4;
							p4 = temp;
						}
						compareTwo.clear();
						// p3 always before p4

						compareTwo.add(p1);
						compareTwo.add(p3);
						if (returnClosestPoint(compareTwo, extremeX, extremeY) == p3) {
							PrintPoint temp = p3;
							p3 = p1;
							p1 = temp;

							temp = p4;
							p4 = p2;
							p2 = temp;
						}
						compareTwo.clear();

						ArrayList<PrintPoint> findClosestPoint = new ArrayList<PrintPoint>();
						findClosestPoint.add(p1);
						findClosestPoint.add(p2);
						findClosestPoint.add(p3);
						findClosestPoint.add(p4);

						PrintPoint startPoint = returnClosestPoint(findClosestPoint, extremeX, extremeY);

						PrintPoint otherStartPoint = getOtherStartPoint(p1, p2, p3, p4, distance);
						// always starts printing on p1

						boolean parallelOverlap = Line2D.ptLineDist(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y) < distance
								&& (Line2D.ptSegDist(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y)
										- Line2D.ptLineDist(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y) <= 0.001
										|| Line2D.ptSegDist(p1.x, p1.y, p2.x, p2.y, p4.x, p4.y)
												- Line2D.ptLineDist(p1.x, p1.y, p2.x, p2.y, p4.x, p4.y) <= 0.001
										|| Line2D.ptSegDist(p3.x, p3.y, p4.x, p4.y, p1.x, p1.y)
												- Line2D.ptLineDist(p3.x, p3.y, p4.x, p4.y, p1.x, p1.y) <= 0.001
										|| Line2D.ptSegDist(p3.x, p3.y, p4.x, p4.y, p1.x, p1.y)
												- Line2D.ptLineDist(p3.x, p3.y, p4.x, p4.y, p1.x, p1.y) <= 0.001)
								&& (dist2D(otherStartPoint.x, otherStartPoint.y, p3.x, p3.y) <= dist2D(p1.x, p1.y, p2.x,
										p2.y));

						compareTwo.clear();

						if (parallelOverlap) { // if the lines are near enough and they overlap

							// start point starts on one of the lines

							DoubleMove firstPart;
							DoubleMove midPart;
							DoubleMove endPart;

							// first part
							PrintPoint new1 = p1.clone();
							double deltaX1 = p3.x - otherStartPoint.x;
							double deltaY1 = p3.y - otherStartPoint.y;
							new1.x += deltaX1;
							new1.y += deltaY1;
							new1.extrusion = p1.extrusion + (p2.extrusion - p1.extrusion)
									* (dist2D(new1.x, new1.y, p2.x, p2.y) / dist2D(p1.x, p1.y, p2.x, p2.y));

							firstPart = new DoubleMove(startPoint, new1, otherStartPoint, p3);

							if (dist2D(new1.x, new1.y, p2.x, p2.y) >= dist2D(p3.x, p3.y, p4.x, p4.y)) { // p4
																										// farther
																										// than
																										// p2
								// print new1 to new2, p3 to p4
								PrintPoint new2 = new1.clone();
								double deltaX2 = p4.x - p3.x;
								double deltaY2 = p4.y - p3.y;
								new2.x += deltaX2;
								new2.y += deltaY2;
								new2.extrusion = new1.extrusion + (p2.extrusion - new1.extrusion)
										* (dist2D(new2.x, new2.y, p2.x, p2.y) / dist2D(new1.x, new1.y, p2.x, p2.y));

								midPart = new DoubleMove(new1, new2, p3, p4);

								// print from new2 to p2, travel p4 to new3

								PrintPoint new3 = p4.clone();
								double deltaX3 = p2.x - new2.x;
								double deltaY3 = p2.y - new2.y;
								new3.x += deltaX3;
								new3.y += deltaY3;

								endPart = new DoubleMove(new2, p2, p4, new3);
								DoubleMove[] thisMove = { firstPart, midPart, endPart };

								if (getDoubleRatio(thisMove) > bestRatio) {
									bestRatio = getDoubleRatio(thisMove);
									bestPair = thisMove;
									bestOther = c2;
									// System.out.println("New best pair " + bestPair + " ratio: " + bestRatio);
								}

							} else {

								// print new1 to p2, p3 to new2
								PrintPoint new2 = p3.clone();
								double deltaX2 = p2.x - new1.x;
								double deltaY2 = p2.y - new1.y;
								new2.x += deltaX2;
								new2.y += deltaY2;
								new2.extrusion = p3.extrusion + (p4.extrusion - p3.extrusion)
										* (dist2D(new2.x, new2.y, p3.x, p3.y) / dist2D(p3.x, p3.y, p4.x, p4.y));

								midPart = new DoubleMove(new1, p2, p3, new2);

								// print from new2 to p4, travel p2 to new3

								PrintPoint new3 = p2.clone();
								double deltaX3 = p4.x - new2.x;
								double deltaY3 = p4.y - new2.y;
								new3.x += deltaX3;
								new3.y += deltaY3;

								endPart = new DoubleMove(p2, new3, new2, p4);
								DoubleMove[] thisMove = { firstPart, midPart, endPart };
								if (getDoubleRatio(thisMove) > bestRatio) {
									bestRatio = getDoubleRatio(thisMove);
									bestPair = thisMove;
									bestOther = c2;
									// System.out.println("New best pair " + bestPair + " ratio: " + bestRatio);
								}

							}

						}
					}
				}
			}
			if (bestOther != null) {

				pSections.remove(bestOther);
				moveCandidates.add(bestPair);
			} else {
				leftoverMoves.add(c1);

			}
			pSections.remove(c1);

		}

		/*
		 * if (distance == secondaryDistance) {
		 * System.out.println(moveCandidates.size()); }
		 */
		// System.out.println(moveCandidates.size());

		// replace the null moves with moves which only cover one line
		ArrayList<DoubleMove> leftoverDoubleMoves = new ArrayList<DoubleMove>();
		for (ContinuousSection c : leftoverMoves) {
			PrintPoint p1 = c.points.get(0);
			PrintPoint p2 = c.points.get(c.points.size() - 1);
			PrintPoint p3 = p1.clone();
			p3.y += distance;
			PrintPoint p4 = p2.clone();
			p4.y += distance;
			DoubleMove move = new DoubleMove(p1, p2, p3, p4);
			// if (distance == primaryDistance) {

			leftoverDoubleMoves.add(move);

			// }

		}

		// remove this for loop if the one below active
		for (int i = 0; i < moveCandidates.size(); i++) {
			if (moveCandidates.get(i)[0] == null) {
				moveCandidates.remove(i);

			}
		}

		/*
		 * if(distance == secondaryDistance) {
		 * System.out.println(moveCandidates.size()); }
		 */

		/*
		 * for (int i = 0; i < moveCandidates.size(); i++) { if
		 * (moveCandidates.get(i)[0] == null) { ContinuousSection c =
		 * leftoverMoves.remove(0); PrintPoint p1 = c.points.get(0); PrintPoint p2 =
		 * c.points.get(c.points.size() - 1); PrintPoint p3 = p1.clone(); p3.y +=
		 * distance; PrintPoint p4 = p2.clone(); p4.y += distance; DoubleMove move = new
		 * DoubleMove(p1, p2, p3, p4); DoubleMove move2 = new DoubleMove(p2, p2, p4,
		 * p4); DoubleMove move3 = new DoubleMove(p2, p2, p4, p4); moveCandidates.set(i,
		 * new DoubleMove[] { move, move2, move3 }); //
		 * System.out.println("Null Move Replaced"); } }
		 */

		// get the best combo

		// long startTime = System.currentTimeMillis();

		//

		for (int i = 0; i < moveCandidates.size(); i++) {
			DoubleMove[] d = moveCandidates.get(i);
			moves.add(d[0]);
			moves.add(d[1]);
			moves.add(d[2]);
			l.parallelMoves.add(d);
		}
		for (DoubleMove d : leftoverDoubleMoves) {
			moves.add(d);
			l.singularMoves.add(d);
		}

		ArrayList<DoubleMove> orderedMoves = new ArrayList<DoubleMove>();

		DoubleMove lastMove = moves.get(0);
		lastMove.updateMidpoints();
		orderedMoves.add(moves.remove(0));

		while (moves.size() > 0) {
			DoubleMove closest = moves.get(0);
			DoubleMove toRemove = closest;
			double minDist = Double.MAX_VALUE;
			for (DoubleMove d : moves) {
				d.updateMidpoints();
				if (dist2D(d.x1, d.y1, lastMove.x2, lastMove.y2) < minDist) {
					closest = d;
					toRemove = d;
					minDist = dist2D(d.x1, d.y1, lastMove.x2, lastMove.y2);
				}

				DoubleMove reversed = d.reverse();
				if (dist2D(reversed.x1, reversed.y1, lastMove.x2, lastMove.y2) < minDist) {
					closest = reversed;
					toRemove = d;
					minDist = dist2D(reversed.x1, reversed.y1, lastMove.x2, lastMove.y2);
				}

			}
			moves.remove(toRemove);
			orderedMoves.add(closest);
			lastMove = closest;

		}

		for (ContinuousSection c : otherSections) {

			for (int i = 1; i < c.points.size(); i++) {
				PrintPoint p1 = c.points.get(i - 1);
				PrintPoint p2 = c.points.get(i);
				PrintPoint p3 = p1.clone();
				PrintPoint p4 = p2.clone();
				p3.y += distance;
				p4.y += distance;
				orderedMoves.add(new DoubleMove(p1, p2, p3, p4));
			}
		}

		ArrayList<DoubleMove> travels = new ArrayList<DoubleMove>();

		for (int i = 1; i < orderedMoves.size(); i++) {
			DoubleMove m1 = orderedMoves.get(i - 1);
			DoubleMove m2 = orderedMoves.get(i);
			// if (addTravel) {
			DoubleMove toAdd = new DoubleMove(m1.end1, m2.start1, m1.end2, m2.start2);
			toAdd.isTravel = true;
			travels.add(toAdd);

			// }
		}
		ArrayList<DoubleMove> orderedClone = (ArrayList<DoubleMove>) orderedMoves.clone();
		orderedMoves.clear();
		for (int i = 0; i < orderedClone.size() - 1; i++) {
			orderedMoves.add(orderedClone.get(i));
			orderedMoves.add(travels.get(i));
		}
		orderedMoves.add(orderedClone.get(orderedClone.size() - 1));

		// if (l.layerNumber == 10) {
		double maxX = Double.MIN_VALUE;
		double maxY = Double.MIN_VALUE;
		for (ContinuousSection c : l.sections) {
			for (PrintPoint p : c.points) {
				// find the minimum centered box the thing fits in
				if (Math.abs(p.x) > maxX) {
					maxX = Math.abs(p.x);
				}
				if (Math.abs(p.y) > maxY) {
					maxY = Math.abs(p.y);
				}
			}
		}

		double xScalar = 30;
		double yScalar = 30;
		double xOffset = maxX * xScalar * 2;
		double yOffset = maxY * yScalar * 2;

		BufferedImage output = new BufferedImage((int) (maxX * xScalar * 4), (int) (maxY * yScalar * 4),
				BufferedImage.TYPE_INT_RGB);
		Graphics2D g = output.createGraphics();

		g.setColor(new Color(0, 0, 255));
		g.setStroke(new BasicStroke(3));
		for (ContinuousSection c : backupSections) {
			for (int i = 0; i < c.points.size() - 1; i++) {
				g.drawLine((int) (c.points.get(i).x * xScalar + xOffset), (int) (c.points.get(i).y * yScalar + yOffset),
						(int) (c.points.get(i + 1).x * xScalar + xOffset),
						(int) (c.points.get(i + 1).y * yScalar + yOffset));

			}
		}

		g.setStroke(new BasicStroke(10));

		for (DoubleMove d : orderedMoves) {

			if (d.isTravel) {
				g.setColor(Color.RED);
			} else {
				g.setColor(Color.WHITE);
			}

			// g.setColor(new Color(255, 0, 0));
			g.drawLine((int) (d.start1.x * xScalar + xOffset), (int) (d.start1.y * yScalar + yOffset),
					(int) (d.end1.x * xScalar + xOffset), (int) (d.end1.y * yScalar + yOffset));
			// g.setColor(new Color(0, 255, 0));
			g.drawLine((int) (d.start2.x * xScalar + xOffset), (int) (d.start2.y * yScalar + yOffset),
					(int) (d.end2.x * xScalar + xOffset), (int) (d.end2.y * yScalar + yOffset));

			// generate image for every 10 moves in a layer
			boolean generateImage = false;
			if (generateImage && orderedMoves.indexOf(d) % 10 == 0 && l.layerNumber == 10) {

				File imageFile = new File("orderedMoves" + orderedMoves.indexOf(d) + ".png");
				try {
					ImageIO.write(output, "PNG", imageFile);
					System.out.println("Image Written");
				} catch (IOException e) {
					e.printStackTrace();
				}
				System.out.println("Image Generated");
			}

		}
		boolean generateImage = false;
		if (generateImage) {

			File imageFile = new File("orderedMoves" + l.layerNumber + ".png");
			try {
				ImageIO.write(output, "PNG", imageFile);
				System.out.println("Image Written");
			} catch (IOException e) {
				e.printStackTrace();
			}
			System.out.println("Image Generated");
		}

		// else {
		// Color color = new Color(0, 0, 0);
		// output.setRGB(x, y, color.getRGB());
		// }

		// }
		// System.out.println(pSections.size() + " out of " + l.sections.size());

		return orderedMoves;
	}

	private void convertToRelative(DoubleMove[] moves) {
		PrintPoint p1 = moves[0].start1;
		PrintPoint p2 = moves[1].start1;
		PrintPoint p3 = moves[2].start1;
		PrintPoint p4 = moves[2].end1;
		PrintPoint p5 = moves[0].start1;
		PrintPoint p6 = moves[1].start1;
		PrintPoint p7 = moves[2].start1;
		PrintPoint p8 = moves[2].end1;

		double deltaE1 = p2.extrusion - p1.extrusion;
		double deltaE2 = p3.extrusion - p2.extrusion;
		double deltaE3 = p4.extrusion - p3.extrusion;
		double deltaE4 = p6.extrusion - p5.extrusion;
		double deltaE5 = p7.extrusion - p6.extrusion;
		double deltaE6 = p8.extrusion - p7.extrusion;

		p1.extrusion = 0;
		p2.extrusion = Math.abs(deltaE1);
		moves[0].end1.extrusion = p2.extrusion;
		p3.extrusion = Math.abs(deltaE1 + deltaE2);
		moves[1].end1.extrusion = p3.extrusion;
		p4.extrusion = Math.abs(deltaE1 + deltaE2 + deltaE3);

		p5.extrusion = 0;
		p6.extrusion = Math.abs(deltaE4);
		moves[0].end1.extrusion = p6.extrusion;
		p7.extrusion = Math.abs(deltaE4 + deltaE5);
		moves[1].end1.extrusion = p8.extrusion;
		p8.extrusion = Math.abs(deltaE4 + deltaE5 + deltaE6);

	}

	private DoubleMove reverseDoubleMove(DoubleMove d) {
		DoubleMove reversed = new DoubleMove(d.end1, d.start1, d.end2, d.start2);
		return reversed;
	}

	private double getDoubleRatio(DoubleMove[] moves) {
		return (dist2D(moves[0].x1, moves[0].y1, moves[0].x2, moves[0].y2)
				+ 2 * dist2D(moves[1].x1, moves[1].y1, moves[1].x2, moves[1].y2)
				+ dist2D(moves[2].x1, moves[2].y1, moves[2].x2, moves[2].y2))
				/ (2 * dist2D(moves[0].x1, moves[0].y1, moves[2].x2, moves[2].y2));
	}

	private double getDoubleRatio(DoubleMove[] moves, double travelLength) {
		return (dist2D(moves[0].x1, moves[0].y1, moves[0].x2, moves[0].y2)
				+ 2 * dist2D(moves[1].x1, moves[1].y1, moves[1].x2, moves[1].y2)
				+ dist2D(moves[2].x1, moves[2].y1, moves[2].x2, moves[2].y2))
				/ (2 * dist2D(moves[0].x1, moves[0].y1, moves[2].x2, moves[2].y2 + travelLength));
	}

	private void removeContaining() {

	}

	// for two parallel sections lines p1->p2 and p3->p4; return the point off the
	// farther line where the extrusion has to start.
	private PrintPoint getOtherStartPoint(PrintPoint p1, PrintPoint p2, PrintPoint p3, PrintPoint p4, double distance) {
		double slope = (p1.y - p2.y) / (p1.x - p2.x);
		double extremeX;
		double extremeY;
		double midX1 = (p1.x + p3.x) / 2.0;
		double midY1 = (p1.y + p3.y) / 2.0;
		double midX2 = (p2.x + p4.x) / 2.0;
		double midY2 = (p2.y + p4.y) / 2.0;
		double yIntercept = -1 * (slope * (midY1) - midY2);
		if (slope == Float.NaN) {
			extremeX = midX1;
			extremeY = -100000;
		} else {
			extremeX = -100000;
			extremeY = slope * extremeX + yIntercept;
		}

		ArrayList<PrintPoint> pts = new ArrayList<PrintPoint>();

		pts.add(p1);
		pts.add(p2);
		pts.add(p3);
		pts.add(p4);
		PrintPoint closest = returnClosestPoint(pts, extremeX, extremeY);
		pts.add(closest);

		// PrintPoint closest = returnClosestPoint(pts, new PrintPoint(extremeX,
		// extremeY));

		double lDist = Math.max(
				Math.sqrt(distance * distance - Line2D.ptLineDist(p1.x, p1.y, p2.x, p2.y, closest.x, closest.y)),
				Math.sqrt(distance * distance - Line2D.ptLineDist(p3.x, p3.y, p4.x, p4.y, closest.x, closest.y)));

		double lDistX = Math.abs(lDist * Math.cos(Math.atan(slope)));
		double lDistY = slope * lDistX;

		PrintPoint other1;
		PrintPoint other2;
		if (closest == p1 || closest == p2) {
			other1 = p3;
			other2 = p4;
		} else {
			other1 = p1;
			other2 = p2;
		}
		double correspondingX = getClosestPointOnLineX(other1.x, other1.y, other2.x, other2.y, closest.x, closest.y);
		double correspondingY = getClosestPointOnLineY(other1.x, other1.y, other2.x, other2.y, closest.x, closest.y);

		PrintPoint other;
		if (other1.extrusion < other2.extrusion) { // other is the first of the two in the printing order
			other = other1;
		} else {
			other = other2;
		}
		PrintPoint candidate1 = other.clone();
		candidate1.x = correspondingX + lDistX;
		candidate1.y = correspondingY + lDistY;
		PrintPoint candidate2 = other.clone();
		candidate2.x = correspondingX - lDistX;
		candidate2.y = correspondingY - lDistY;
		// these are the two points on the other line where it could start printing.
		PrintPoint otherPoint;
		if (candidate1.x > Math.min(other1.x, other2.x) || candidate1.x < Math.max(other1.x, other2.x)) {
			// if the first candidate is outside the bounds. One always must be inside the
			// bounds, so just test if it meets on one axis and you know it is inside.
			otherPoint = candidate2;

		} else {
			otherPoint = candidate1;
		}

		return otherPoint;
	}

	private static PrintPoint returnClosestPoint(ArrayList<PrintPoint> points, double x, double y) {
		PrintPoint closest = points.get(0);
		for (PrintPoint p : points) {
			if (Point2D.distance(x, y, p.x, p.y) < Point2D.distance(x, y, closest.x, closest.y)) {
				closest = p;
			}
		}
		return closest;
	}

	// from
	// https://stackoverflow.com/questions/3365171/calculating-the-angle-between-two-lines-without-having-to-calculate-the-slope
	// returns the angle between the lines in radians. d * cos(theta) is the cross
	// section.
	public static double angleBetween2Lines(double x1, double y1, double x2, double y2, double x3, double y3, double x4,
			double y4) {

		double angle1 = Math.atan2(y1 - y2, x1 - x2);
		double angle2 = Math.atan2(y3 - y4, x3 - x4);
		return angle1 - angle2;
	}
	// private ArrayList<Move> createSingularMoves(ContinuousSection first,
	// ContinuousSection second) {

	// adapted from
	// http://www.java2s.com/Code/Java/2D-Graphics-GUI/Returnsclosestpointonsegmenttopoint.htm
	private static double getClosestPointOnLineX(double sx1, double sy1, double sx2, double sy2, double px, double py) {
		double xDelta = sx2 - sx1;
		double yDelta = sy2 - sy1;

		if ((xDelta == 0) && (yDelta == 0)) {
			throw new IllegalArgumentException("Segment start equals segment end");
		}

		double u = ((px - sx1) * xDelta + (py - sy1) * yDelta) / (xDelta * xDelta + yDelta * yDelta);

		final double closestPoint = (sx1 + u * xDelta);
		return closestPoint;
	}

	// http://www.java2s.com/Code/Java/2D-Graphics-GUI/Returnsclosestpointonsegmenttopoint.htm
	private static double getClosestPointOnLineY(double sx1, double sy1, double sx2, double sy2, double px, double py) {
		double xDelta = sx2 - sx1;
		double yDelta = sy2 - sy1;

		if ((xDelta == 0) && (yDelta == 0)) {
			throw new IllegalArgumentException("Segment start equals segment end");
		}

		double u = ((px - sx1) * xDelta + (py - sy1) * yDelta) / (xDelta * xDelta + yDelta * yDelta);

		final double closestPoint = (sy1 + u * yDelta);

		return closestPoint;
	}

	private double dist2D(double startX, double startY, double startX2, double startY2) {
		return Math.sqrt((startX - startX2) * (startX - startX2) + (startY - startY2) * (startY - startY2));
	}

	private BufferedImage generateImage(Layer layer) {

		double maxX = Double.MIN_VALUE;
		double maxY = Double.MIN_VALUE;
		for (ContinuousSection c : layer.sections) {
			for (PrintPoint p : c.points) {
				// find the minimum centered box the thing fits in
				if (Math.abs(p.x) > maxX) {
					maxX = Math.abs(p.x);
				}
				if (Math.abs(p.y) > maxY) {
					maxY = Math.abs(p.y);
				}
			}
		}

		double xScalar = 30;
		double yScalar = 30;
		double xOffset = maxX * xScalar * 2;
		double yOffset = maxY * yScalar * 2;
		int dotSize = 5;
		/*
		 * for (ContinuousSection c : layer.sections) { for (Segment s : c.segments) {
		 * System.out.println( "StartX: " + s.startX + " StartY: " + s.startY +
		 * " endX: " + s.endX + " endY: " + s.endY); }
		 * System.out.println("NEW SECTION"); }
		 */
		final int LINE_TYPE = 1;
		final int CONT_SECTIONS = 2;
		int sortBy = LINE_TYPE;

		BufferedImage output = new BufferedImage((int) (maxX * xScalar * 4), (int) (maxY * yScalar * 4),
				BufferedImage.TYPE_INT_RGB);
		Graphics2D g = output.createGraphics();
		g.drawImage(output, 0, 0, null);
		// g.addRenderingHints(new RenderingHints(RenderingHints.KEY_ANTIALIASING,
		// RenderingHints.VALUE_ANTIALIAS_ON));
		if (sortBy == LINE_TYPE) {
			for (ContinuousSection c : layer.sections) {
				for (int i = 0; i < c.points.size() - 1; i++) {
					PrintPoint p = c.points.get(i);
					PrintPoint p2 = c.points.get(i + 1);
					// Segment s = new Segment(false, false, 15.5, 20.5, 15.3, 20.3, 1, 1, 1500,
					// 500, 501, 5);
					Color color;
					switch (p.type) {
					case "SKIN": // RED
						color = new Color(255, 0, 0);
						break;
					case "FILL": // BLUE
						color = new Color(0, 0, 255);
						break;
					case "SUPPORT": // GREEN
						color = new Color(0, 255, 0);
						break;
					case "WALL-INNER": // YELLOW
						color = new Color(255, 255, 0);
						break;
					case "WALL-OUTER": // ORANGE
						color = new Color(255, 128, 0);
						break;
					default:// WHITE
						// System.out.println(s.type);
						color = new Color(255, 255, 255);

						break;
					}
					g.setColor(color);
					g.drawLine((int) (p.x * xScalar + xOffset), (int) (p.y * yScalar + yOffset),
							(int) (p2.x * xScalar + xOffset), (int) (p2.y * yScalar + yOffset));
				} // else {
					// Color color = new Color(0, 0, 0);
					// output.setRGB(x, y, color.getRGB());
					// }

			}
		} else if (sortBy == CONT_SECTIONS) {
			for (ContinuousSection c : layer.sections) {
				Color color = new Color((int) (Math.random() * 256), (int) (Math.random() * 256),
						(int) (Math.random() * 256));
				g.setColor(color);
				for (int i = 0; i < c.points.size() - 1; i++) {
					PrintPoint p = c.points.get(i);
					PrintPoint p2 = c.points.get(i + 1);

					g.drawLine((int) (p.x * xScalar + xOffset), (int) (p.y * yScalar + yOffset),
							(int) (p2.x * xScalar + xOffset), (int) (p2.y * yScalar + yOffset));

				}
			}
		}

		return output;

	}

	private PrintPoint parseLayer(Layer l, PrintPoint previous) {
		ArrayList<PrintPoint> points = new ArrayList<PrintPoint>();
		String type = previous.type;
		for (String line : l.lines) {// generate all the segments for the layer
			if (line.contains("TYPE")) {
				type = line.substring(6, line.length() - 1);
			}
			if (line.substring(0, 2).equals("G0") || line.substring(0, 2).equals("G1")) { // only for printing lines
				previous = parsePoint(previous, line);
				previous.type = type;
				points.add(previous);
			}
		}

		// System.out.println("Split"); } }

		// splits the layer up into continuous sections and adds them to the layer
		ContinuousSection c = new ContinuousSection();

		for (int i = 0; i < points.size() - 1; i++) {

			double deltaE = points.get(i + 1).extrusion - points.get(i).extrusion;
			Boolean currentlyTraveling = false;
			c.points.add(points.get(i));
			if (!currentlyTraveling) {
				if (deltaE < 0.00001) { // its a travel move
					// System.out.println("NEW SECTION");
					currentlyTraveling = true;
					l.sections.add(c);

					c = new ContinuousSection();

				} else { // not a travel move

				}
			} else {
				if (deltaE > 0.00001) {
					currentlyTraveling = false;
					i--;
				}
			}

		}
		if (!l.sections.contains(c)) {
			l.sections.add(c);
		}

		for (int i = 0; i < l.sections.size(); i++) {
			if (l.sections.get(i).points.size() <= 1) { // the section has no length, ie. 0-1 points
				l.sections.remove(i);
				i--;
			}
		}
		return previous; // return the last segment, which is where the layer will be the next time

	}

	private PrintPoint parsePoint(PrintPoint previous, String line) {
		int G = -1;
		double X = 0;
		double Y = 0;
		double Z = 0;
		double E = 0;
		double F = 0;

		double x = previous.x;
		double y = previous.y;
		double z = previous.z;
		double speed = previous.speed; // mm/min
		double e = previous.extrusion;
		int layerNumber = previous.layerNumber;
		String type = previous.type;

		int xIndex = -1;
		if (line.contains("X")) {
			xIndex = line.indexOf("X") + 1;
			X = Double.parseDouble(
					line.substring(xIndex, Math.min(line.substring(xIndex).indexOf(' ') + xIndex, line.length())));
			x = X;
		}
		int yIndex = -1;
		if (line.contains("Y")) {
			yIndex = line.indexOf("Y") + 1;
			Y = Double.parseDouble(
					line.substring(yIndex, Math.min(line.substring(yIndex).indexOf(' ') + yIndex, line.length())));
			y = Y;
		}
		int zIndex = -1;
		if (line.contains("Z")) {
			zIndex = line.indexOf("Z") + 1;
			Z = Double.parseDouble(
					line.substring(zIndex, Math.min(line.substring(zIndex).indexOf(' ') + zIndex, line.length())));
			z = Z;
		}
		int eIndex = -1;
		if (line.contains("E")) {
			eIndex = line.indexOf("E") + 1;
			E = Double.parseDouble(
					line.substring(eIndex, Math.min(line.substring(eIndex).indexOf(' ') + eIndex, line.length())));
			e = E;
		}
		int fIndex = -1;
		if (line.contains("F")) {
			fIndex = line.indexOf("F") + 1;
			F = Double.parseDouble(
					line.substring(fIndex, Math.min(line.substring(fIndex).indexOf(' ') + fIndex, line.length())));
			speed = F;
		}

		return new PrintPoint(x, y, z, speed, e, layerNumber, type);
	}

	private static File readFile() {
		// TODO Auto-generated method stub
		JFileChooser chooser = new JFileChooser(".");
		if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
			return chooser.getSelectedFile();
		}
		return null;
	}
}
