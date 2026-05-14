/**
 * Pool.java
 *
 * Created on: Mar 9, 2014
 *     Author: zzz
 */
package BPC.LP;
import Common.*;
import java.util.*;

import Basic.Data;
import Basic.Schedule;

public class Pool 
{
	public Data data;
	
	public ArrayList<Schedule> schedule_pool;
	public HashMap< ArrayList<Integer>, Integer > schedule_check_map;
	
	public Pool(Data d, ArrayList<Schedule> sset)
	{
		data = d;
		
		schedule_pool = new ArrayList<Schedule>();
		schedule_check_map = new HashMap< ArrayList<Integer>, Integer >();
		
		for(int i = 0; i < sset.size(); ++i)
		{
			AddRoute(sset.get(i));
		}
	}
	
	public int AddRoute(Schedule s)
	{
		if(!schedule_check_map.containsKey(s.jobSeq))
		{
			schedule_pool.add(s);
			schedule_check_map.put(s.jobSeq, schedule_pool.size() - 1);
			
			return schedule_pool.size() - 1;
		}
		else
		{
			return schedule_check_map.get(s.jobSeq);
		}
	}
	
	public double GetRouteCost(int rid)
	{
		return schedule_pool.get(rid).cost;
	}
	
	public ArrayList<Integer> GetRoute(int rid)
	{
		return schedule_pool.get(rid).jobSeq;
	}
	
	public int Route2ID(ArrayList<Integer> route)
	{
		int rid = -1;
		if(schedule_check_map.containsKey(route))
		{
			rid = schedule_check_map.get(route);
		}
		
		return rid;
	}
	
	public void Clear()
	{
		if(schedule_pool != null)
		{
			schedule_pool.clear();
			schedule_pool = null;
		}
		
		if(schedule_check_map != null)
		{
			schedule_check_map.clear();
			schedule_check_map = null;
		}
	}
	
}
