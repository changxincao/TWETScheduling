/**
 * CutPool.java
 *
 * Created on: Mar 17, 2014
 *     Author: zzz
 */
package BPC.LP;
import Common.*;
import java.util.*;

import Basic.Data;

public class CutPool 
{
	Data data;
	
	public ArrayList< ArrayList<Integer> > cut_pool;
	public ArrayList<Integer> cut_type;  	//0 means capacity cut, 1 for strengthened capacity cut, 2 as time cut, 3 as bid partition cut
	//cut type 没几个能用得上的 0 1 3都用不上
	public ArrayList<Integer> cut_vehicle_number;
	public HashMap< ArrayList<Integer>, Integer > cut_check_map;
	
	public CutPool(Data d)
	{
		data = d;
		cut_pool = new ArrayList< ArrayList<Integer> >();
		cut_type = new ArrayList<Integer>();
		cut_vehicle_number = new ArrayList<Integer>();
		cut_check_map = new HashMap< ArrayList<Integer>, Integer >();
	}
	
	public int AddCut(ArrayList<Integer> cut, int type, int vehicle)
	{
		if(!cut_check_map.containsKey(cut))
		{
			cut_pool.add(cut);
			cut_type.add(type);
			cut_vehicle_number.add(vehicle);
			cut_check_map.put(cut, cut_pool.size() - 1);
			
			if(type == 2)
			{
				++data.configure.tpath_number;
			}

			return cut_pool.size() - 1;
		}
		return cut_check_map.get(cut);
	}
	
	public int GetCutType(int index)
	{
		return cut_type.get(index);
	}
	
	public int GetVehicleNumber(int index)
	{
		return cut_vehicle_number.get(index);
	}
	
	public ArrayList<Integer> GetCut(int index)
	{
		return cut_pool.get(index);
	}
	

	
	public int GetCutIndex(ArrayList<Integer> cut)
	{
		if(cut_check_map.containsKey(cut))
		{
			return cut_check_map.get(cut);
		}
		return -1;
	}
	
	public int GetSize()
	{
		return cut_pool.size();
	}
	
	public void Clear()
	{
		if(cut_pool != null)
		{
			cut_pool.clear();
			cut_pool = null;
		}
		if(cut_type != null)
		{
			cut_type.clear();
			cut_type = null;
		}
		if(cut_vehicle_number != null)
		{
			cut_vehicle_number.clear();
			cut_vehicle_number = null;
		}
	
		if(cut_check_map != null)
		{
			cut_check_map.clear();
			cut_check_map = null;
		}
	}
	
}
