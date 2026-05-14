/**
 * GC.java
 *
 * Created on: Mar 11, 2014
 *     Author: zzz
 */
package BPC.GC;
import java.util.*;

import ilog.concert.*;
import Common.*;
import BPC.LP.*;
import Basic.Data;

//mono-directional label setting algorithm

public class GC 
{
	public Data data;
	public Node node;
	double tolerance = -0.00001;
	//Label queue
	public PriorityQueue<Label> UL;
	public ArrayList< ArrayList<Label> > TL;
	//generated route information
	public ArrayList<Integer> gn_index;
	public int sr_number;
	
	public GC(Data d)
	{
		data = d;
	}
	
	public void Initialize(LP lp)
	{
		UL = new PriorityQueue<Label>();
		TL = new ArrayList< ArrayList<Label> >();
		for(int i = 0; i <= data.n; ++i)
		{
			ArrayList<Label> array = new ArrayList<Label>();
			TL.add(array);
		}
		gn_index = new ArrayList<Integer>();
		
		sr_number = lp.sr_active_set.size();
		Label label = new Label(sr_number,data.penaltyFunction[0].shiftY());
		label.jid = 0;
		label.reduced_cost = -lp.mu[0];//任务数的reduced cost,与函数无关
		label.min_time = 0;
		label.low_reach = 1;
		label.low_visit = 1;
		UpdateReach(label);
		UL.add(label);
		TL.get(0).add(label);
	}
	
	private boolean UseSR(ArrayList<Integer> srcut, Label lb, Label label)
	{
		for(int i = 0; i < srcut.size(); ++i)
		{
			int srcid = srcut.get(i);
			if(CheckVisit(lb, srcid))
			{
				continue;
			}
			
			if( CheckReach(label, srcid) )  //can reach
			{
				return true;
			}
		}
		return false;
	}
	
	public boolean IsDominate(Label label, LP lp)
	{
		int jid = label.jid;
		for(int i = 0; i < TL.get(jid).size(); ++i)
		{
			Label lb = TL.get(jid).get(i);
			
			if( lb.m_reduced_cost <= label.m_reduced_cost && lb.m_weight <= label.m_weight && lb.m_time <= label.m_time
			    && ((lb.m_low_reach & label.m_low_reach) == lb.m_low_reach) && ((lb.m_high_reach & label.m_high_reach) == lb.m_high_reach) )
			{
				double mu1 = 0, diff = lb.m_reduced_cost - label.m_reduced_cost - tolerance;
				for(int sr = 0; sr < sr_number; ++sr)
				{
					if(lb.sr_count.get(sr) == 1 && (label.sr_count.get(sr)&1) == 0)
					{
						if(UseSR(lp.sr_pool.GetCut(lp.sr_active_set.get(sr)), lb, label))
						{
							mu1 += lp.sr_mu.get(sr);
							if(mu1 < diff)
							{
								break;
							}
						}
					}
				}
				
				if(lb.m_reduced_cost - mu1 <= label.m_reduced_cost)
				{
					label.Clear();
					return true;
				}
			}
			
			if( label.m_reduced_cost <= lb.m_reduced_cost && label.m_weight <= lb.m_weight && label.m_time <= lb.m_time
				&& ((label.m_low_reach & lb.m_low_reach) == label.m_low_reach) && ((label.m_high_reach & lb.m_high_reach) == label.m_high_reach) )
			{
				double mu2 = 0, diff = label.m_reduced_cost - lb.m_reduced_cost - tolerance;
				for(int sr = 0; sr < sr_number; ++sr)
				{
					if((lb.sr_count.get(sr)&1) == 0 && label.sr_count.get(sr) == 1)
					{
						if(UseSR(lp.sr_pool.GetCut(lp.sr_active_set.get(sr)), label, lb))
						{
							mu2 += lp.sr_mu.get(sr);
							if(mu2 < diff)
							{
								break;
							}
						}
					}
				}
				
				if(label.m_reduced_cost - mu2 <= lb.m_reduced_cost)
				{
					lb.Clear();
					lb.is_dominate = true;
					TL.get(cid).remove(i);
					--i;
				}
			}
				
		}
		
		TL.get(cid).add(label);
		return false;
	}
	
	public boolean Extend(LP lp)throws IloException
	{
		return Extend(lp, lp.node);
	}
	public boolean Extend(LP lp, Node n)throws IloException
	{
		node = n;
		long dpt1 = System.nanoTime();
		Initialize(lp);
		while(!UL.isEmpty())
		{
			Label label = UL.poll();
			if(label.is_dominate)
			{
				continue;
			}
			
			for(int i = 1; i <= data.m_customer_number; ++i)
			{
				if(node.feasible_arc[label.cid][i] == -1 || !CheckReach(label, i))
				{
					continue;
				}
				
				Label lb = new Label(sr_number);
				lb.cid = i;
				lb.father = label;
				lb.m_reduced_cost = label.m_reduced_cost + data.GetDistance(label.cid, i) - lp.arc_mu[label.cid][i] - lp.mu[i];
				for(int sr = 0; sr < sr_number; ++sr)
				{
					int number = label.sr_count.get(sr);
					if(number < 3)
					{
						ArrayList<Integer> srcut = lp.sr_pool.GetCut(lp.sr_active_set.get(sr));
						if(srcut.contains(i))
						{
							++number;
							if(number == 2)
							{
								lb.m_reduced_cost -= lp.sr_mu.get(sr);
							}
						}
					}
					lb.sr_count.set(sr, number);
				}
				if(i == data.m_customer_number) // get a new route
				{
					if(lb.m_reduced_cost < tolerance)  //new route with reduced cost less than 0
					{
						ArrayList<Integer> route = GetRoute(lb);
						if(lp.pool.Route2ID(route) == -1)
						{		
							int index = lp.pool.AddRoute(route);
							gn_index.add(index);

/*
							Utility helper = new Utility(data);
							while(!helper.IsFeasible(lp.pool.GetRoute(index)))   //debug route
							{
								System.out.println(" Check -- " + helper.IsFeasible(lp.pool.GetRoute(index)) + " ---  : " + lb.m_reduced_cost);
								helper.OutputRoute(lp.pool.GetRoute(index));   //debug
							}
*/
							if(gn_index.size() >= data.m_configure.addin_size)
							{
								break;
							}
						}
					}
					continue;
				}
				
				lb.m_weight = label.m_weight + data.m_customer.get(i).m_demand;
				lb.m_time = Math.max(label.m_time + data.m_customer.get(label.cid).m_service_time + data.GetDistance(label.cid, i), data.m_customer.get(i).m_early_time);
				SetVisit(lb, label, i);
				UpdateReach(lb);
				
				if(!IsDominate(lb, lp))
				{
					UL.add(lb);
				}
			}
			
			if(gn_index.size() >= data.m_configure.addin_size)
			{
				break;
			}
		}
		
		long dpt2 = System.nanoTime();
		data.m_configure.dp_time += dpt2 - dpt1;
		
		System.out.println("The generated route size = " + gn_index.size());  //debug
		if(gn_index.size() > 0)
		{
//			//debug
//			for(int g = 0; g < gn_index.size(); ++g)
//			{
//				ArrayList<Integer> tr = lp.pool.GetRoute(gn_index.get(g));
//				for(int r = 0; r < data.solution.size(); ++r)
//				{
//					ArrayList<Integer> tr2 = data.solution.get(r);
//					boolean same = true;
//					for(int k = 0; k < tr.size() && k < tr2.size(); ++k)
//					{
//						if(tr.get(k) != tr2.get(k))
//						{
//							same = false;
//							break;
//						}
//					}
//					if(same)
//					{
//						System.out.println("**********Route  " + r +  "  is added");
//						break;
//					}
//				}
//			}
			lp.AddColumn(gn_index);
			Clear();
			
			long act = System.nanoTime();
			data.m_configure.ac_time += act - dpt2;
			return true;
		}
		
		return false;
	}
	
	public boolean Solve(LP lp)throws IloException
	{	
		long t1 = System.nanoTime();
		boolean not_end = true;
		boolean success = true;
		int run = 0;
		do
		{
			lp.Solve();
			if(data.m_configure.TimeOut())
			{
				success = false;
				break;
			}
			
			lp.GetDual();
			//lp.DebugDual();  //debug
			
//			for(int i = 0; i < data.solution.size(); ++i)
//			{
//				ArrayList<Integer> route = data.solution.get(i);
//				double reduce_cost = -lp.mu[0];
//				for(int j = 1; j < route.size(); ++j)
//				{
//					int cid = route.get(j);
//					reduce_cost += data.GetDistance(route.get(j-1), cid) - lp.arc_mu[route.get(j-1)][cid] - lp.mu[cid];
//				}
//				
//				for(int sr = 0; sr < lp.sr_index.size(); ++sr)
//				{
//					ArrayList<Integer> srcut = lp.sr_pool.GetCut(lp.sr_index.get(sr));
//					int number = 0;
//					for(int c = 0; c < 3; ++c)
//					{
//						if(route.contains(srcut.get(c)))
//						{
//							++number;
//						}
//					}
//					if(number > 1)
//					{
//						System.out.println(srcut.get(0) + "\t" + srcut.get(1) + "\t" + srcut.get(2) + "\t" + lp.sr_mu[sr]);
//						reduce_cost -= lp.sr_mu[sr];
//					}
//				}
//				System.out.println("Route " + i + ": " + reduce_cost + "\n");
//			}
//			
			
			not_end = Extend(lp);
			++ run;
		}while(not_end);
		
		long t2 = System.nanoTime();
		data.m_configure.cg_time += t2 - t1;
		if(run < 2)
		{
			success = false;
		}
		return success;
	}
	
	public ArrayList<Integer> GetRoute(Label label)
	{
		ArrayList<Integer> route = new ArrayList<Integer>();
		Label ft = label;
		while(ft != null)
		{
			route.add(0, ft.cid);
			ft = ft.father;
		}
		
		return route;
	}
	
	public boolean CheckReach(Label label, int cid)
	{
		if(cid < 64)
		{
			if((label.m_low_reach & data.m_mask[cid]) != 0)  //cannot reach
			{
				return false;
			}
		}
		else
		{
			if((label.m_high_reach & data.m_mask[cid]) != 0)
			{
				return false;
			}
		}
		
		return true;
	}
	public void SetNotReach(Label lb, int cid)
	{
		if(cid < 64)
		{
			lb.m_low_reach = lb.m_low_reach | data.m_mask[cid];
		}
		else
		{
			lb.m_high_reach = lb.m_high_reach | data.m_mask[cid];
		}
	}
	
	public boolean CheckVisit(Label label, int cid)
	{
		if(cid < 64)
		{
			if((label.m_low_visit & data.m_mask[cid]) != 0)
			{
				return true;
			}
		}
		else
		{
			if((label.m_high_visit & data.m_mask[cid]) != 0)
			{
				return true;
			}
		}
		
		return false;
	}
	public void SetVisit(Label lb, Label label, int cid)
	{
		if(cid < 64)
		{
			lb.m_low_visit = label.m_low_visit | data.m_mask[cid];
			lb.m_high_visit = label.m_high_visit;
		}
		else
		{
			lb.m_high_visit = label.m_high_visit | data.m_mask[cid];
			lb.m_low_visit = label.m_low_visit;
		}
	}

	public void UpdateReach(Label label)
	{
		for(int i = 1; i <= data.m_customer_number; ++i)
		{
			if( CheckVisit(label, i)
			   || label.m_weight + data.m_customer.get(i).m_demand > data.m_vehicle_capacity
			   || label.m_time + data.m_customer.get(label.cid).m_service_time + data.GetDistance(label.cid, i) > data.m_customer.get(i).m_late_time )
			{
				SetNotReach(label, i);
			}
		}
	}
	
	public void Reset()
	{
	}
	
	public void Clear()
	{
		if(UL != null)
		{
			UL.clear();
			UL = null;
		}
		if(TL != null)
		{
			TL.clear();
			TL = null;
		}
		if(gn_index != null)
		{
			gn_index.clear();
			gn_index = null;
		}
	}
	
	@SuppressWarnings("unused")
	private void DebugLabel(int cid)
	{
		System.out.println("Start to Check the label at cutomer : " + cid);
		Utility helper = new Utility(data);
		for(int i = 0; i < TL.get(cid).size(); ++i)
		{
			Label label = TL.get(cid).get(i);
			ArrayList<Integer> route = GetRoute(label);
			helper.OutputRoute(route);
			System.out.println("  --The reduced cost : " + label.m_reduced_cost);
		}
	}
	
}
