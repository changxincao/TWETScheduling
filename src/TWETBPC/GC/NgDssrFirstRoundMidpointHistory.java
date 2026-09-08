package TWETBPC.GC;

import java.util.List;
import java.util.WeakHashMap;

/** 节点首轮中点双池；Node使用对象身份相等，弱键不延长已释放节点的生命期。 */
final class NgDssrFirstRoundMidpointHistory {
    private final WeakHashMap<Object, Pools> nodes = new WeakHashMap<>();

    static final class Pools {
        private final List<Integer> cuts;
        private double sum, filteredSum;
        private long count, filteredCount;

        Pools(List<Integer> cuts) { this.cuts = List.copyOf(cuts); }

        double midpoint() {
            return filteredCount > 0 ? filteredSum / filteredCount
                    : count > 0 ? sum / count : Double.NaN;
        }

        String source() {
            return "firstRoundMean:" + (filteredCount > 0 ? "filtered" : "fallback")
                    + ":all" + count + ":qualified" + filteredCount;
        }

        void record(double midpoint, double forwardMillis, double backwardMillis) {
            if (!Double.isFinite(midpoint) || midpoint <= 0) return;
            sum += midpoint;
            count++;
            if (Double.isFinite(forwardMillis) && Double.isFinite(backwardMillis)
                    && forwardMillis > 0 && backwardMillis > 0
                    && Math.max(forwardMillis, backwardMillis)
                            / Math.min(forwardMillis, backwardMillis) <= 4.0) {
                filteredSum += midpoint;
                filteredCount++;
            }
        }
    }

    Pools forNode(Object node, List<Integer> cuts) {
        if (node == null) throw new IllegalArgumentException("Missing pricing node");
        Pools pools = nodes.get(node);
        if (pools == null || !pools.cuts.equals(cuts)) {
            pools = new Pools(cuts);
            nodes.put(node, pools);
        }
        return pools;
    }
}
