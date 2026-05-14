/**
 * LP.java
 *
 * Created on: Mar 10, 2014
 * Updated on: Oct 26, 2016
 *     Author: zzz
 */
package BPC.LP;
import Common.*;
import java.util.*;

import Basic.Data;
import ilog.concert.*;
import ilog.cplex.IloCplex;
import ilog.cplex.IloCplex.UnknownObjectException;

public class LP 
{
	public Data data;
	public Pool pool;
	public CutPool cut_pool;
	public SRCutPool sr_pool;
//	public CliCutPool cli_pool;//先注释，这玩意似乎用不上 相关信息删了
	public Node node;
	double zero_tolerance = 1e-4;
	//generated route information
	public ArrayList<Integer> schedule_index;
	
	//lp solution information
	public ArrayList<Double> schedule_active_value;
	public ArrayList<Integer> schedule_active_set;
	public double[][] x_map;   //arc value of LP solution
	public double solution_cost;
	
	//cplex model information
	public IloCplex cplex;
	public IloObjective m_cplex_obj;
	public IloRange[] cus2rng;   //constraint mapping
	public HashMap<Integer, IloNumVar> r2var;  //variable mapping
	
	//cut related information
	//这些cut感觉可能也不一定能用得上
	public boolean has_cut;
	public ArrayList< ArrayList< ArrayList<Integer> > > arc2schedule; //arc in which routes
	public ArrayList< ArrayList< ArrayList<Integer> > > arc2cut;   // arc in which cuts
	public HashMap<Integer, IloRange> cut2rng;  //cut mapping
	public ArrayList<Integer> cut_index;
	public HashSet<Integer> cut_map;	//check whether the cut has been added
	
	//SR cut related information
	public boolean has_sr;
	public ArrayList< ArrayList<Integer> > cus2schedule;   // customers in which route
	public HashMap<Integer, IloRange> sr2rng;  //subset-row cut mapping
	public ArrayList<Integer> sr_index;
	public HashSet<Integer> sr_map;     //check whether the cut has been added
	public int[] sr_cus_number;
	
	
	//branch related information
	public HashMap<Integer, IloRange> branch2rng; //branch mapping
	//slack variable for branch
	public boolean has_slack1, has_slack2;
	public IloNumVar slack1, slack2;
	public boolean has_arc_branch;
	public int arc_from_cid, arc_to_cid;
	
	//dual value information
	public double[] mu;  //dual of basic constraints
	public double[][] arc_mu; //dual value of cut constraints
	public ArrayList<Integer> sr_active_set;
	public ArrayList<Double> sr_mu;  //dual value of subset-row
		
	//solution information
	public ArrayList< ArrayList<Integer> > schedule_set;
	
	//for stabilization 
	public boolean first = true;
	public double var_threshold = 1e-3;
	public IloNumVar[] vars;
	public double[] vars_bound;
	public double[] vars_value;
	public double init_value = 2;
	public double step_size = 1;
	
	public LP(Pool p, CutPool cp, SRCutPool sp)
	{
		pool = p;
		data = pool.data;
		cut_pool = cp;
		sr_pool = sp;
		
	}
	
	public void Construct(Node n, ArrayList<Integer> col_set)throws IloException
	{
		node = n;
		//initialize parameters
		schedule_index = new ArrayList<Integer>();
		cut_index = node.cut_index;
		sr_index = node.sr_index;
		
		
		schedule_active_value = new ArrayList<Double>();
		schedule_active_set = new ArrayList<Integer>();
		
		x_map = new double[data.n + 1][data.n + 1];
		
		cplex = new IloCplex();
		cplex.setParam(IloCplex.IntParam.RootAlg, IloCplex.Algorithm.Primal);
		//cplex.setParam(IloCplex.DoubleParam.EpOpt, 1e-2);  //reduced-cost tolerance for optimality
		//cplex.setParam(IloCplex.DoubleParam.EpMrk, 0.1);  //Markowitz tolerance, pivot selection during basis factoring
		cplex.setParam(IloCplex.IntParam.ScaInd, 1);  //More aggressive scaling, to scale the problem matrix
		cplex.setOut(null);
		m_cplex_obj = cplex.addMinimize();
		cus2rng = new IloRange[data.n+1];
		r2var = new HashMap<Integer, IloNumVar>();
		
		has_cut = false;
		arc2schedule = new ArrayList< ArrayList< ArrayList<Integer> > >();
		arc2cut = new ArrayList< ArrayList< ArrayList<Integer> > >();
		cut2rng = new HashMap<Integer, IloRange>();
		cut_map = new HashSet<Integer>();
		for(int i = 0; i <= data.n; ++i)
		{
			arc2schedule.add( new ArrayList< ArrayList<Integer> >() );
			arc2cut.add( new ArrayList< ArrayList<Integer> >() );
			for(int j = 0; j <= data.n; ++j)
			{
				arc2schedule.get(i).add( new ArrayList<Integer>() );
				arc2cut.get(i).add( new ArrayList<Integer>() );
			}
		}
		//不存在到n+1的弧
		
		has_sr = false;
		sr2rng = new HashMap<Integer, IloRange>();
		sr_map = new HashSet<Integer>();
		cus2schedule = new ArrayList< ArrayList<Integer> >();
		sr_cus_number = new int[data.n];
		Arrays.fill(sr_cus_number, 0);
		for(int i = 0; i <= data.n; ++i)
		{
			cus2schedule.add( new ArrayList<Integer>() );
		}
		
		
		
		branch2rng = new HashMap<Integer, IloRange>();
		has_slack1 = false;
		has_slack2 = false;
		has_arc_branch = false;
		
		arc_mu = new double[data.n + 1][data.n + 1];
		mu = new double[data.n + 2];//TODO ?
		sr_active_set = new ArrayList<Integer>();
		sr_mu = new ArrayList<Double>();
		
		schedule_set = new ArrayList< ArrayList<Integer> >();
		//for stabilization
		vars = null;
		vars_bound = null;
		vars_value = null;
		//add basic constraints: vehicle number constraint, customer serve constraint
		cus2rng[0] = cplex.addRange(node.min_vehicle_number, node.max_vehicle_number, "c,0"); //including branch 1
		for(int i = 1; i < data.m_customer_number; ++i)
		{
			double upper = 1.0;
			if(data.m_configure.vrptw)//VRPTW with 1e10, set-covering model
			{
				upper = 1e10;
			}
			cus2rng[i] = cplex.addRange(1.0, upper, "c," + i);
		}
		
		branch2rng.put(0, cus2rng[0]);
		
		AddColumn(col_set);
		AddCut(cut_index, true);
		AddSRCut(sr_index, true);
		
		//for stablization, used for VRPTW
		if(data.m_configure.vrptw)
		{
			add_slacks();
		}
	}
	
	public void add_slacks() throws IloException
	{
		vars = new IloNumVar[data.m_customer_number];
		vars_bound = new double[data.m_customer_number];
		vars_value = new double[data.m_customer_number];
		for(int i = 0; i < data.m_customer_number; ++i)
		{
			vars_bound[i] = 1e5;
		}
		for(int i = 1; i < data.m_customer_number; i++)
		{		
			IloColumn col = cplex.column(m_cplex_obj, vars_bound[i]);
			col = col.and( cplex.column(cus2rng[i], 1) );
			vars[i] = cplex.numVar(col, 0.0, Double.MAX_VALUE, IloNumVarType.Float, "slack," + i);
		}
	}
	
	public boolean is_zero() throws UnknownObjectException, IloException
	{
		if(vars == null)
			return true;
		
		for(int i = 1; i < data.m_customer_number; ++i)
		{
			vars_value[i] = cplex.getValue(vars[i]);
		}
		for(int i = 1; i < data.m_customer_number; ++i)
		{
			if(vars_value[i] > var_threshold)
			{
				return false;
			}
		}
		return true;
	}
	
	public void update_slacks() throws IloException
	{
		if(vars == null)
			return;

		for(int i = 1; i < data.m_customer_number; ++i)
		{
			if(vars_value[i] > var_threshold)
			{
				vars_bound[i] += step_size;
				cplex.setLinearCoef(m_cplex_obj, vars[i], vars_bound[i]);
			}
		}
		cplex.solve();
		solution_cost = cplex.getObjValue();
		GetDual();
	}
	
	public void set_bounds() throws IloException
	{
		if(vars == null)
			return;
		if(first == false)
			return;

		first = false;
		for(int i = 1; i < data.m_customer_number; ++i)
		{
			vars_bound[i] = Math.max(0, mu[i] - init_value);
			cplex.setLinearCoef(m_cplex_obj, vars[i], vars_bound[i]);
		}
		cplex.solve();
		GetDual();
	}
	
	public void close_slacks() throws IloException
	{
		if(vars == null)
			return;
		
		for(int i = 1; i < data.m_customer_number; ++i)
		{
			vars[i].setUB(0.0);
		}
		cplex.solve();
		GetDual();
		GetVaule();
	}
	
	
	
	public void AddColumn(ArrayList<Integer> rset)throws IloException
	{
		for(int i = 0; i < rset.size(); ++i)
		{
			ArrayList<Integer> route = pool.GetRoute(rset.get(i));
			double cost = pool.GetRouteCost(rset.get(i));
			IloColumn col = cplex.column(m_cplex_obj, cost);
			
			//---update the basic customer visited number constraint
			for(int j = 0; j < route.size() - 1; ++j)
			{
				col = col.and( cplex.column(cus2rng[route.get(j)], 1) );
				arc2schedule.get(route.get(j)).get(route.get(j + 1)).add(rset.get(i)); //record for adding cut
			}
			for(int j = 1; j < route.size() - 1; ++j)  //for subset-row
			{
				cus2schedule.get(route.get(j)).add(rset.get(i));
			}
			
			//---update the cut constraint
			if(has_cut)
			{
				int[] coefficient = new int[cut_pool.GetSize()];
				for(int j = 1; j < route.size(); ++j)
				{
					ArrayList<Integer> cuts = arc2cut.get(route.get(j - 1)).get(route.get(j));
					for(int c = 0; c < cuts.size(); ++c)
					{
//						System.out.println("\tadd col -- " + row_number + " " + cuts.get(c) + " " + cut2cindex.get(cuts.get(c)));  //debug
						coefficient[cuts.get(c)] += 1;
					}
				}
				for(int cc = 0; cc < cut_index.size(); ++cc)
				{
					int cutid = cut_index.get(cc);
					if(coefficient[cutid] > 0)
					{
						col = col.and( cplex.column(cut2rng.get(cutid), coefficient[cutid]) );
					}
				}
				coefficient = null;
			}
			
			//---update the subset-row inequality
			if(has_sr)
			{
				for(int sc = 0; sc < sr_index.size(); ++sc)
				{
					ArrayList<Integer> sr_cut = sr_pool.GetCut(sr_index.get(sc));
					int number = 0;
					for(int c = 0; c < sr_cut.size(); ++c)
					{
						if(route.contains(sr_cut.get(c)))
						{
							++number;
							if(number > 1)
							{
								col = col.and( cplex.column(sr2rng.get(sr_index.get(sc)), 1) );
								break;
							}
						}
					}
				}
			}
			
	
			IloNumVar var = cplex.numVar(col, 0.0, Double.MAX_VALUE, IloNumVarType.Float, "t," + rset.get(i));
			r2var.put(rset.get(i), var);
			schedule_index.add(rset.get(i));
		}
	}
	
	public void AddCut(ArrayList<Integer> cset, boolean init)throws IloException
	{	
		has_cut = true;
		for(int c = 0; c < cset.size(); ++c)
		{
			ArrayList<Integer> cut = cut_pool.GetCut(cset.get(c));		
			HashMap<Integer, Integer> route_count = new HashMap<Integer, Integer>();
			for(int i = 0; i < cut.size(); ++i)
			{
				int n1 = cut.get(i);
				for(int n2 = 1; n2 <= data.m_customer_number; ++n2)
				{
					if(cut.contains(n2))
					{
						continue;
					}
					
					ArrayList<Integer> rset = arc2schedule.get(n1).get(n2);
					for(int k = 0; k < rset.size(); ++k)
					{
						int rid = rset.get(k);
						if(route_count.containsKey(rid))
						{
							int number = route_count.get(rid) + 1;
							route_count.put(rid, number);
						}
						else
						{
							route_count.put(rid, 1);
						}
					}
					
					arc2cut.get(n1).get(n2).add(cset.get(c));
				}
			}
			
			//add the route-related variables to constraint
			IloRange range = cplex.addRange(cut_pool.GetVehicleNumber(cset.get(c)), data.m_BigNumber, "cut," + cset.get(c));
			IloNumExpr expr = cplex.numExpr();
			Iterator<Map.Entry<Integer, Integer>> entries = route_count.entrySet().iterator();
			while(entries.hasNext())
			{
				Map.Entry<Integer, Integer> entry = entries.next();				
				IloNumExpr sub_expr = cplex.numExpr();
				sub_expr = cplex.sum(sub_expr, r2var.get(entry.getKey()));
				sub_expr = cplex.prod(sub_expr, entry.getValue());
				expr = cplex.sum(expr, sub_expr);
			}
			
			//add the bid-related variables to constraint
			ArrayList<Integer> bid_coef = cut_pool.GetBidCoef(cset.get(c));
			for(int u = 0; u < data.m_bid_number; ++u)
			{
				if(bid_coef.get(u) != 0)
				{
					IloNumExpr sub_expr = cplex.numExpr();
					sub_expr = cplex.sum(sub_expr, b2var[u]);
					sub_expr = cplex.prod(sub_expr, bid_coef.get(u));
					expr = cplex.sum(expr, sub_expr);
				}
			}
						
			range.setExpr(expr);
			cut2rng.put(cset.get(c), range);
//			System.out.println("\t " + c + "  add cut : " + cset.get(c) + " at row : " + place);  //debug
			if(!init)
			{
				cut_index.add(cset.get(c));
			}
			cut_map.add(cset.get(c));
		}
	}
	
	public void AddSRCut(ArrayList<Integer> cset, boolean init)throws IloException
	{
		has_sr = true;
		for(int c = 0; c < cset.size(); ++c)
		{
			ArrayList<Integer> cut = sr_pool.GetCut(cset.get(c));		
			HashMap<Integer, Integer> route_count = new HashMap<Integer, Integer>();
			for(int i = 0; i < cut.size(); ++i)
			{
				int cid = cut.get(i);
				for(int r = 0; r < cus2schedule.get(cid).size(); ++r)
				{
					int rid = cus2schedule.get(cid).get(r);
					if(route_count.containsKey(rid))
					{
						int number = route_count.get(rid) + 1;
						route_count.put(rid, number);
					}
					else
					{
						route_count.put(rid, 1);
					}
				}
			}
			
			//add the route-related variables to constraint
			IloRange range = cplex.addRange(0.0, 1.0, "SRcut," + cset.get(c));
			IloNumExpr expr = cplex.numExpr();
			Iterator<Map.Entry<Integer, Integer>> entries = route_count.entrySet().iterator();
			while(entries.hasNext())
			{
				Map.Entry<Integer, Integer> entry = entries.next();
				if(entry.getValue() > 1) //at least two customers
				{
					expr = cplex.sum(expr, r2var.get(entry.getKey()));
				}
			}
			
			//add the bid-related variables to constraint
			for(int u = 0; u < data.m_bid_number; ++u)
			{
				if(data.m_bid_customer[u][cut.get(0)] + data.m_bid_customer[u][cut.get(1)] + data.m_bid_customer[u][cut.get(2)] > 1)
				{
					expr = cplex.sum(expr, b2var[u]);
				}
			}
			
			range.setExpr(expr);
			sr2rng.put(cset.get(c), range);
			if(!init)
			{
				sr_index.add(cset.get(c));
			}
			sr_map.add(cset.get(c));
		}
	}


	
	public void ForceArcValue(int cid1, int cid2, int lb, int ub)throws IloException
	{
		has_arc_branch = true;
		arc_from_cid = cid1;
		arc_to_cid = cid2;
		IloRange range = cplex.addRange(lb, ub, "brarc," + cid1 + "," + cid2);
		IloNumExpr expr = cplex.numExpr();
		ArrayList<Integer> rset = arc2schedule.get(cid1).get(cid2);
		for(int i = 0; i < rset.size(); ++i)
		{
			expr = cplex.sum(expr, r2var.get(rset.get(i)) );
		}
		range.setExpr(expr);
		branch2rng.put( (cid1 + 2) * data.m_customer_number + cid2, range);  //avoid dupicate with other branch
	}
	
	public void AddSlack(int cid, int sid)throws IloException
	{
		if(sid == 0)
		{
			has_slack1 = true;
			IloColumn col = cplex.column(m_cplex_obj, data.m_BigNumber);
			col = col.and( cplex.column(branch2rng.get(cid), -1) );
			slack1 = cplex.numVar(col, 0.0, data.m_vehicle_number, "sm," + cid);
		}
		else
		{
			has_slack2 = true;
			IloColumn col = cplex.column(m_cplex_obj, data.m_BigNumber);
			col = col.and( cplex.column(branch2rng.get(cid), 1) );
			slack2 = cplex.numVar(col, 0.0, data.m_vehicle_number, "sp," + cid);
		}
	}
	public boolean IsNoSlack()throws IloException
	{
		if(has_slack1)
		{
			double value = cplex.getValue(slack1);
			if(value > zero_tolerance)
			{
				return false;
			}
		}
		if(has_slack2)
		{
			double value = cplex.getValue(slack2);
			if(value > zero_tolerance)
			{
				return false;
			}
		}
		
		return true;
	}
	
	public void GetVaule()throws IloException
	{
		//route value
		schedule_active_value.clear();
		schedule_active_set.clear();
		for(int i = 0; i < schedule_index.size(); ++i)
		{
			int rid = schedule_index.get(i);
			double value = cplex.getValue(r2var.get(rid));
			if(value > zero_tolerance)
			{
				schedule_active_value.add(value);
				schedule_active_set.add(rid);
			}
		}
		
		for(int i = 0; i <= data.m_customer_number; ++i)
		{
			Arrays.fill(x_map[i], 0);
		}
		for(int i = 0; i < schedule_active_value.size(); ++i)
		{
			double value = schedule_active_value.get(i);
			ArrayList<Integer> route = pool.GetRoute(schedule_active_set.get(i));
			for(int j = 1; j < route.size(); ++j)
			{
				x_map[route.get(j - 1)][route.get(j)] += value;
			}
		}
		
		
	}
	
	public void DebugValue()
	{
		//debug output solution information
		for(int i = 0; i < schedule_active_value.size(); ++i)
		{
			System.out.println("Route active value : " + schedule_active_value.get(i) + " -- " + schedule_active_set.get(i));
		}
		
		
		
		//output active route information
		for(int i = 0; i < schedule_active_set.size(); ++i)
		{
			ArrayList<Integer> route = pool.GetRoute(schedule_active_set.get(i));
			for(int j = 0; j < route.size(); ++j)
			{
				System.out.print(route.get(j) + " ");
			}
			System.out.println();
 		}
	}
	
	public void GetDual()throws IloException
	{
		for(int i = 0; i < data.m_customer_number; ++i)
		{
			mu[i] = cplex.getDual(cus2rng[i]);
		}
		mu[data.m_customer_number] = 0;
		
		//cut dual
		for(int i = 0; i <= data.m_customer_number; ++i)
		{
			Arrays.fill(arc_mu[i], 0);
			if(!has_cut)
			{
				continue;
			}
			for(int j = 0; j <= data.m_customer_number; ++j)
			{
				for(int k = 0; k < arc2cut.get(i).get(j).size(); ++k)
				{
					int cutid = arc2cut.get(i).get(j).get(k);
					arc_mu[i][j] += cplex.getDual(cut2rng.get(cutid));
				}
			}
		}
		
		
		
		if(has_arc_branch)
		{
			arc_mu[arc_from_cid][arc_to_cid] += cplex.getDual(branch2rng.get( (arc_from_cid+2)*data.m_customer_number+arc_to_cid ));
		}
		
		if(has_sr)
		{
			sr_active_set.clear();
			sr_mu.clear();
			for(int i = 0; i < sr_index.size(); ++i)
			{
				double value = cplex.getDual(sr2rng.get(sr_index.get(i)));
				if(value < -zero_tolerance)
				{
					sr_active_set.add(sr_index.get(i));
					sr_mu.add(value);
				}
			}
		}
	}
	public void DebugDual()
	{
		System.out.println("\n\n Debug Dual :");
		for(int i = 0; i < data.m_customer_number; ++i)
		{
			System.out.println(i + "\t" + mu[i]);
		}
		
		for(int i = 0; i < sr_active_set.size(); ++i)
		{
			ArrayList<Integer> srcut = sr_pool.GetCut(sr_active_set.get(i));
			System.out.println("SR_" + sr_active_set.get(i) + "\t" + sr_mu.get(i) + "\t -- : " + srcut.get(0) + "\t" + srcut.get(1) + "\t" + srcut.get(2));
		}
	}
	
	public boolean IsInteger()
	{
		return IsInteger(schedule_set, bid_select);
	}
	@SuppressWarnings("unchecked")
	public boolean IsInteger(ArrayList< ArrayList<Integer> > routes, ArrayList<Boolean> bids)
	{
		Utility helper = new Utility(data);
		
		for(int i = 0; i < schedule_active_value.size(); ++i)
		{
			double value = schedule_active_value.get(i);
			if(!helper.IsInteger(value))
			{
				return false;
			}
		}
		for(int u = 0; u < bid_active_value.size(); ++u)
		{
			double value = bid_active_value.get(u);
			if(!helper.IsInteger(value))
			{
				return false;
			}
		}
		
		//get the solution information
		routes.clear();
		for(int i = 0; i < schedule_active_set.size(); ++i)
		{
			ArrayList<Integer> route = pool.GetRoute(schedule_active_set.get(i));
			routes.add((ArrayList<Integer>)route.clone());
		}
		bids.clear();
		bids.addAll(Collections.nCopies(data.m_bid_number, false));
		for(int u = 0; u < bid_active_set.size(); ++u)
		{
			bids.set(bid_active_set.get(u), true);
		}
		return true;
	}
	
	public boolean Solve()throws IloException
	{
		boolean success = cplex.solve();
		if(success)
		{
			solution_cost = cplex.getObjValue();
		}
		
		//cplex.exportModel("model.lp");   //debug
		return success;
	}
	
	public void Clear()throws IloException
	{
		node = null;

		if(schedule_index != null)
		{
			schedule_index.clear();
			schedule_index = null;
		}
		
		if(schedule_active_value != null)
		{
			schedule_active_value.clear();
			schedule_active_value = null;
		}
		if(schedule_active_set != null)
		{
			schedule_active_set.clear();
			schedule_active_set = null;
		}
		
		x_map = null;
		solution_cost = data.m_BigNumber;
		
		if(cplex != null)
		{
			cplex.clearModel();
			cplex.end();
			cplex = null;
		}
		cus2rng = null;
		if(r2var != null)
		{
			r2var.clear();
			r2var = null;
		}
		
		mu = null;
		
		if(schedule_set != null)
		{
			schedule_set.clear();
			schedule_set = null;
		}
		
		if(arc2schedule != null)
		{
			arc2schedule.clear();
			arc2schedule = null;
		}
		if(arc2cut != null)
		{
			arc2cut.clear();
			arc2cut = null;
		}
		if(cut2rng != null)
		{
			cut2rng.clear();
			cut2rng = null;
		}
		if(cut_map != null)
		{
			cut_map.clear();
			cut_map = null;
		}
		if(sr2rng != null)
		{
			sr2rng.clear();
			sr2rng = null;
		}
		if(sr_map != null)
		{
			sr_map.clear();
			sr_map = null;
		}
		if(cus2schedule != null)
		{
			cus2schedule.clear();
			cus2schedule = null;
		}
		arc_mu = null;
		sr_mu = null;
		sr_cus_number = null;
		
		
	}
	
}
