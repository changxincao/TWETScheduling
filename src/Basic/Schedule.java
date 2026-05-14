package Basic;

import java.util.ArrayList;

import Common.PiecewiseLinearFunction;

public class Schedule {
	public ArrayList<Integer> jobSeq;
	public double cost;
	public ArrayList<PiecewiseLinearFunction> fFunctions;
	public ArrayList<PiecewiseLinearFunction> bFunctions;//这俩先留着，不一定有用
	
	public Schedule(ArrayList<Integer> jobSeq,double cost) {
		this.jobSeq=jobSeq;
		this.cost=cost;
	}
	
	public Schedule(ArrayList<Integer> jobSeq,double cost,ArrayList<PiecewiseLinearFunction> fFunctions, ArrayList<PiecewiseLinearFunction> bFunctions) {
		this(jobSeq,cost);
		this.fFunctions=fFunctions;
		this.bFunctions=bFunctions;
	}
}
