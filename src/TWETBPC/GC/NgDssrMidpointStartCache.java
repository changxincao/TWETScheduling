package TWETBPC.GC;

import java.util.List;

/** 单条标量缓存；节点身份或active cuts不同即不命中，不缓存任何搜索状态。 */
final class NgDssrMidpointStartCache {
    private Object node;
    private List<Integer> cuts = List.of();
    private double midpoint = Double.NaN;

    double lookup(Object currentNode, List<Integer> currentCuts) {
        return currentNode != null && currentNode == node && cuts.equals(currentCuts)
                ? midpoint : Double.NaN;
    }

    void remember(Object currentNode, List<Integer> currentCuts, double value) {
        node = currentNode;
        cuts = List.copyOf(currentCuts);
        midpoint = Double.isFinite(value) && value > 0 ? value : Double.NaN;
    }
}
