/**
 * SRCutPool.java
 *
 * Created on: Jul 18, 2014
 *     Author: zzz
 */
package BPC.LP;
import Common.*;
import java.util.*;

import Basic.Data;

public class SRCutPool 
{
	Data data;
	
	public ArrayList< ArrayList<Integer> > sr_pool;
	public HashMap< ArrayList<Integer>, Integer > sr_check_map;
	
	public SRCutPool(Data d)
	{
		data = d;
		sr_pool = new ArrayList< ArrayList<Integer> >();
		sr_check_map = new HashMap< ArrayList<Integer>, Integer >();
	}
	
	public int AddCut(ArrayList<Integer> cut)
	{
		if(!sr_check_map.containsKey(cut))
		{
			sr_pool.add(cut);
			sr_check_map.put(cut, sr_pool.size() - 1);
			++data.configure.ssrow_number;
			
			return sr_pool.size() - 1;
		}
		return sr_check_map.get(cut);
	}
	
	public ArrayList<Integer> GetCut(int index)
	{
		return sr_pool.get(index);
	}
	
	public int GetCutIndex(ArrayList<Integer> cut)
	{
		if(sr_check_map.containsKey(cut))
		{
			return sr_check_map.get(cut);
		}
		return -1;
	}
	
	public int GetSize()
	{
		return sr_pool.size();
	}
	
	public void Clear()
	{
		if(sr_pool != null)
		{
			sr_pool.clear();
			sr_pool = null;
		}
		if(sr_check_map != null)
		{
			sr_check_map.clear();
			sr_check_map = null;
		}
	}
}
