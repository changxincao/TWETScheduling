package Test;
//
//import java.util.ArrayList;
//import java.util.HashSet;
//import java.util.List;
//import java.util.Set;
//
//public class Test {
//	
//	public static void main(String[] args) {
//		ArrayList<Integer> list1=new ArrayList<Integer>();
//		for(int i=0;i<10;i++) {
//			list1.add(i);
//		}
//		ArrayList<Integer> list2=new ArrayList<Integer>();
//		for(int i=0;i<20;i++) {
//			list2.add(i);
//		}
//		
////		Set<List<Integer>> set=Set.of(List.copyOf(list1),List.copyOf(list2));
//		List<List<Integer>> set=List.of(List.copyOf(list1),List.copyOf(list2));
////		list1.clear();
//		ArrayList<Integer> list3=new ArrayList<Integer>(list1);
//		HashSet<List<List<Integer>>> sets=new HashSet<List<List<Integer>>>();
//		sets.add(set);
//		System.out.println(set);
//		System.out.println(sets.contains(List.of(list3,list2)));
//		
//	}
//	
//	
//}

import java.util.*;

public class Test  {
    public static void main(String[] args) {
        // 原列表，初始 [1,2,3]
        List<Integer> seq = new ArrayList<>(Arrays.asList(1, 2, 3));
        List<Integer> other = new ArrayList<>(Arrays.asList(9, 8, 7));
        
        // 存：用快照把当时的内容固定下来
        Set<Set<List<Integer>>> noImprove = new HashSet<>();
        noImprove.add(Set.of(
            List.copyOf(seq), 
            List.copyOf(other)
        ));
        
        // 修改原 seq
        seq.add(4);  // 现在 seq 是 [1,2,3,4]
        
        // ① 用“变了的”原列表去查 —— 一定查不到
        boolean hitRaw = noImprove.contains(Set.of(seq, other));
        System.out.println("hitRaw = " + hitRaw);  // false
        
        // ② 用“变了后再快照”去查 —— 命中
        //    因为存进去的那份快照，正好也是改完之后（[1,2,3,4]）的内容
        //    你如果在存的时候，是在 seq.add(4) 之后再存的话，这步就 true
        boolean hitSnap = noImprove.contains(Set.of(
            List.copyOf(seq), 
            List.copyOf(other)
        ));
        System.out.println("hitSnap = " + hitSnap);  // true
    }
}
