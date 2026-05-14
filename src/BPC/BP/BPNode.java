/**
 * BPNode.java
 *
 * Created on: Oct 22, 2016
 *     Author: zzz
 */

package BPC.BP;

public class BPNode implements Comparable< BPNode >
{
	public double reduce_cost;
	public int rid;
	
	public BPNode()
	{
	}
	
	public BPNode(int _id, double rc)
	{
		rid = _id;
		reduce_cost = rc;
	}
	
	public void SetValue(int _id, double rc)
	{
		rid = _id;
		reduce_cost = rc;
	}
	
	@Override
	public int compareTo(BPNode node) 
	{
		if(reduce_cost < node.reduce_cost)
		{
			return -1;
		}
		else if(reduce_cost > node.reduce_cost)
		{
			return 1;
		}
		return 0;
	}

}
