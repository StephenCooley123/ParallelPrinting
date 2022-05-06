package gCodeInterpreter;

import java.util.ArrayList;

public class Layer {
	
	int layerNumber = 0;
	ArrayList<String> lines = new ArrayList<String>();
	ArrayList<ContinuousSection> sections = new ArrayList<ContinuousSection>();
	
	ArrayList<DoubleMove> doublePath;
	ArrayList<DoubleMove> quadPath;
	
	ArrayList<DoubleMove[]> parallelMoves = new ArrayList<DoubleMove[]>();
	ArrayList<DoubleMove> singularMoves = new ArrayList<DoubleMove>();

}
