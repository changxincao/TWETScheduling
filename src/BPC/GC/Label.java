package BPC.GC;
import java.util.*;

import Common.PiecewiseLinearFunction;
import Common.Utility;

public class Label implements Comparable<Label>
{
	public double reduced_cost;//从function中提取最小值
	public PiecewiseLinearFunction reduced_cost_function;//可正向可负向
	public double min_time;//最小的执行完成时间 辅助作用
	public long low_reach;  //0 indicates the customer can reach
	public long high_reach;
	
	public long low_visit; //1 indicates the customer is visited
	public long high_visit;
	
	public boolean is_dominate;
	public int jid;
	public Label father;
	
	public ArrayList<Integer> sr_count;  //count the number of jobs in the subset-row
	
	public Label()
	{
		reduced_cost = 0;//取一个上界，0没有用
		min_time = 0;
		low_reach = 0;
		high_reach = 0;
		low_visit = 0;
		high_visit = 0;
		
		is_dominate = false;
		jid = 0;
		father = null;
	}
	public Label(PiecewiseLinearFunction f)
	{
		reduced_cost = 0;//取一个上界，0没有用
		reduced_cost_function=f;
		min_time = 0;
		low_reach = 0;
		high_reach = 0;
		low_visit = 0;
		high_visit = 0;
		
		is_dominate = false;
		jid = 0;
		father = null;
	}
	
	public Label(int sr_size,PiecewiseLinearFunction f)
	{
		//如果存在与弧相关的cut的话，那这些弧相关的reduced cost应该也要放到函数里，也好处理，只是要对原始函数对具体的扩展弧进行纵向平移？
		//应该可以不参与计算，比较的时候用进来就行，但似乎没有这种cut
		reduced_cost = 0;
		reduced_cost_function=f;//只标记任务
		min_time = 0;
		low_reach = 0;
		high_reach = 0;
		low_visit = 0;
		high_visit = 0;
		
		is_dominate = false;
		jid = 0;
		father = null;
		
		sr_count = new ArrayList<Integer>();
		sr_count.addAll(Collections.nCopies(sr_size, 0));
	}
	

	@Override
	public int compareTo(Label x) 
	{
		if(Utility.compareLt(reduced_cost,x.reduced_cost))
		{
			return -1;
		}
		else if(Utility.compareLt(x.reduced_cost,reduced_cost))
		{
			return 1;
		}
		
		return 0;
	}
	
	public void Clear()
	{
		reduced_cost_function=null;
		if(sr_count != null)
		{
			sr_count.clear();
			sr_count = null;
		}
	}

}
