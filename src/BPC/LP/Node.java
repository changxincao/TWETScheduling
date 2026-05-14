/**
 * Node.java
 *
 * Created on: Mar 9, 2014
 *     Author: zzz
 */
package BPC.LP;

import java.util.ArrayList;
import java.util.Arrays;

import Basic.Data;
import Common.*;

public class Node implements Comparable<Node> {
	public Data data;
	public int id;
	public double sudo_cost; // used to bound
	public int depth;

	public ArrayList<ArrayList<Integer>> schedule_set;
	public ArrayList<Integer> cut_index;
	public ArrayList<Integer> sr_index;
	

	// strategy 1: branch on machine number?这个对调度应该没用,因为显然机器数越多结果会越好
	public int min_machine_number;
	public int max_machine_number;

	// strategy 4: branch on arc
	public int[][] feasible_arc; // feasible_arc[i][j] = 0, the arc (i,j) is feasible. -1 infeasible, 1 must
									// travel

	public Node() {
	}

	public Node(Data d) {
		data = d;
		id = 0;
		sudo_cost = data.m_BigNumber;
		depth = 0;
		schedule_set = new ArrayList<ArrayList<Integer>>();
		cut_index = new ArrayList<Integer>();
		sr_index = new ArrayList<Integer>();
		

		min_machine_number = 1;
		max_machine_number = data.m;
		// TODO 待确定，应该没用

		feasible_arc = new int[data.n + 1][data.n + 1];// 不包括到0的
	}

	public Node(Data d, ArrayList<ArrayList<Integer>> sset) {
		data = d;
		id = 0;
		sudo_cost = 0;
		depth = 0;
		schedule_set = sset;
		cut_index = new ArrayList<Integer>();
		sr_index = new ArrayList<Integer>();
		

		min_machine_number = 0;
		max_machine_number = data.m;

		feasible_arc = new int[data.n + 1][data.n + 1];
		for (int i = 0; i <= data.n; i++) {
			Arrays.fill(feasible_arc[i], 0);// 这段有用吗？
		}
	}

	@SuppressWarnings("unchecked")
	public Node Copy() {
		Node new_node = new Node();
		new_node.data = data;
		new_node.sudo_cost = sudo_cost;
		new_node.depth = depth;
		new_node.schedule_set = null;
		new_node.cut_index = (ArrayList<Integer>) cut_index.clone();
		new_node.sr_index = (ArrayList<Integer>) sr_index.clone();
		
		new_node.min_machine_number = min_machine_number;
		new_node.max_machine_number = max_machine_number;

		
		new_node.feasible_arc = new int[data.n+1][data.n + 1];
		for (int i = 0; i <= data.n; i++) {
			System.arraycopy(feasible_arc[i], 0, new_node.feasible_arc[i], 0, feasible_arc[i].length);
		}

		return new_node;
	}

	public void Clear() {
		if (schedule_set != null) {
			schedule_set .clear();
			schedule_set  = null;
		}
		if (cut_index != null) {
			cut_index.clear();
			cut_index = null;
		}
		if (sr_index != null) {
			sr_index.clear();
			sr_index = null;
		}
		
		feasible_arc = null;
	}

	@Override
	public int compareTo(Node node) {
		if (sudo_cost < node.sudo_cost) {
			return -1;
		} else if (sudo_cost > node.sudo_cost) {
			return 1;
		}

		return 0;
	}

}
