package auto;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure helpers for Hurricane-style inventory auto-stack / unstack. */
public final class ItemStacking {
    public static final String STACK_SUFFIX = ", stack of";

    private ItemStacking() {}

    /**
     * Grouping key for auto-stack. Returns null when the item should be skipped
     * (unloaded name, rings, or quantity liquids/powders whose names contain a
     * decimal, matching Hurricane).
     */
    public static String stackKey(String name) {
	if(name == null || name.isEmpty() || "???".equals(name))
	    return null;
	if(name.contains("Ring"))
	    return null;
	if(name.indexOf('.') >= 0)
	    return null;
	if(name.endsWith(STACK_SUFFIX))
	    name = name.substring(0, name.length() - STACK_SUFFIX.length());
	name = name.trim();
	return name.isEmpty() ? null : name;
    }

    public static boolean isStackName(String name) {
	return name != null && name.contains("stack of");
    }

    /**
     * The server does not expose a general "stackable" flag for loose items.
     * Restrict probing to the one-slot items stacks can contain and exclude
     * the liquid containers that are commonly stored in duplicate.
     */
    public static boolean mayStack(String name, String resname, int slotsWide, int slotsHigh) {
	if(stackKey(name) == null || slotsWide != 1 || slotsHigh != 1)
	    return false;
	if(resname == null)
	    return true;
	return !(resname.endsWith("/waterskin") ||
		 resname.endsWith("/waterflask") ||
		 resname.contains("/glassjug") ||
		 resname.contains("/kuksa") ||
		 resname.contains("/bucket"));
    }

    /**
     * Pick the pair whose combined quality range is smallest. The returned
     * indices are ordered source, destination; the smaller pile is held so a
     * full pile is not selected as the source when a partial pile is present.
     * Unknown quality is represented by NaN and falls back to pile size.
     */
    public static int[] closestQualityPair(double[] mins, double[] maxs, int[] amounts) {
	return closestQualityPair(mins, maxs, amounts, null);
    }

    static int[] closestQualityPair(double[] mins, double[] maxs, int[] amounts, boolean[][] blocked) {
	if(mins == null || maxs == null || amounts == null || mins.length < 2 ||
	   mins.length != maxs.length || mins.length != amounts.length)
	    return null;
	int bestA = -1, bestB = -1;
	double bestSpan = Double.POSITIVE_INFINITY;
	int bestAmount = Integer.MAX_VALUE;
	for(int i = 0; i < mins.length; i++) {
	    for(int j = i + 1; j < mins.length; j++) {
		if(blocked != null && blocked[i][j])
		    continue;
		double span = qualitySpan(mins[i], maxs[i], mins[j], maxs[j]);
		int amount = amounts[i] + amounts[j];
		if(bestA < 0 || Double.compare(span, bestSpan) < 0 ||
		   (Double.compare(span, bestSpan) == 0 && amount < bestAmount)) {
		    bestA = i;
		    bestB = j;
		    bestSpan = span;
		    bestAmount = amount;
		}
	    }
	}
	if(bestA < 0)
	    return null;
	if(amounts[bestB] < amounts[bestA]) {
	    int t = bestA;
	    bestA = bestB;
	    bestB = t;
	}
	return new int[] {bestA, bestB};
    }

    private static double qualitySpan(double minA, double maxA, double minB, double maxB) {
	if(!Double.isFinite(minA) || !Double.isFinite(maxA) ||
	   !Double.isFinite(minB) || !Double.isFinite(maxB))
	    return Double.POSITIVE_INFINITY;
	return Math.max(maxA, maxB) - Math.min(minA, minB);
    }

    /** On a failed same-type merge, the larger destination is full. */
    public static boolean failedSourceIsAlsoFull(int sourceAmount, int destinationAmount) {
	return sourceAmount >= destinationAmount;
    }

    /** Number of known-quality items that are outside their final sorted band. */
    public static int qualityMisplacementCount(double[][] qualities) {
	return qualityMoves(qualities).size();
    }

    /**
     * Plan one closed redistribution cycle. Stacks are assumed to be in their
     * desired low-to-high order. Each row is {source stack, item index,
     * destination stack}. Rotating a returned cycle puts every listed item in
     * its final quality band while keeping every stack the same size.
     *
     * Equal qualities are assigned to their current stack first, preventing
     * pointless exchanges where either copy would satisfy the same band.
     */
    public static int[][] nextQualityCycle(double[][] qualities) {
	List<QualityMove> moves = qualityMoves(qualities);
	if(moves.isEmpty())
	    return null;
	@SuppressWarnings("unchecked")
	ArrayDeque<QualityMove>[] outgoing = new ArrayDeque[qualities.length];
	for(int i = 0; i < outgoing.length; i++)
	    outgoing[i] = new ArrayDeque<>();
	for(QualityMove move : moves)
	    outgoing[move.source].addLast(move);

	int[] firstVisit = new int[qualities.length];
	Arrays.fill(firstVisit, -1);
	List<QualityMove> path = new ArrayList<>();
	int current = moves.get(0).source;
	while(firstVisit[current] < 0) {
	    firstVisit[current] = path.size();
	    QualityMove edge = outgoing[current].pollFirst();
	    if(edge == null)
		return null;
	    path.add(edge);
	    current = edge.target;
	}
	int start = firstVisit[current];
	int[][] cycle = new int[path.size() - start][];
	for(int i = start; i < path.size(); i++) {
	    QualityMove move = path.get(i);
	    cycle[i - start] = new int[] {move.source, move.item, move.target};
	}
	return cycle;
    }

    private static List<QualityMove> qualityMoves(double[][] qualities) {
	List<QualityMove> moves = new ArrayList<>();
	if(qualities == null || qualities.length < 2)
	    return moves;
	int[] capacities = new int[qualities.length];
	Map<Double, List<QualityItem>> actual = new TreeMap<>();
	List<QualityItem> sorted = new ArrayList<>();
	for(int stack = 0; stack < qualities.length; stack++) {
	    double[] items = qualities[stack];
	    if(items == null)
		continue;
	    for(int item = 0; item < items.length; item++) {
		double quality = items[item];
		if(!Double.isFinite(quality))
		    continue;
		QualityItem ref = new QualityItem(stack, item, quality);
		actual.computeIfAbsent(quality, ignored -> new ArrayList<>()).add(ref);
		sorted.add(ref);
		capacities[stack]++;
	    }
	}
	if(sorted.size() < 2)
	    return moves;
	sorted.sort(Comparator.comparingDouble((QualityItem item) -> item.quality)
	    .thenComparingInt(item -> item.source)
	    .thenComparingInt(item -> item.item));

	Map<Double, int[]> desired = new TreeMap<>();
	int target = 0;
	int remaining = capacities[0];
	for(QualityItem item : sorted) {
	    while(remaining == 0 && target + 1 < capacities.length)
		remaining = capacities[++target];
	    desired.computeIfAbsent(item.quality, ignored -> new int[qualities.length])[target]++;
	    remaining--;
	}

	for(Map.Entry<Double, List<QualityItem>> entry : actual.entrySet()) {
	    @SuppressWarnings("unchecked")
	    List<QualityItem>[] bySource = new List[qualities.length];
	    for(int i = 0; i < bySource.length; i++)
		bySource[i] = new ArrayList<>();
	    for(QualityItem item : entry.getValue())
		bySource[item.source].add(item);
	    int[] need = desired.get(entry.getKey()).clone();
	    for(int source = 0; source < bySource.length; source++) {
		int stay = Math.min(bySource[source].size(), need[source]);
		if(stay > 0) {
		    bySource[source].subList(0, stay).clear();
		    need[source] -= stay;
		}
	    }
	    int nextTarget = 0;
	    for(int source = 0; source < bySource.length; source++) {
		for(QualityItem item : bySource[source]) {
		    while(nextTarget < need.length && need[nextTarget] == 0)
			nextTarget++;
		    if(nextTarget >= need.length)
			return new ArrayList<>();
		    moves.add(new QualityMove(source, item.item, nextTarget));
		    need[nextTarget]--;
		}
	    }
	}
	return moves;
    }

    private static final class QualityItem {
	final int source;
	final int item;
	final double quality;

	QualityItem(int source, int item, double quality) {
	    this.source = source;
	    this.item = item;
	    this.quality = quality;
	}
    }

    private static final class QualityMove {
	final int source;
	final int item;
	final int target;

	QualityMove(int source, int item, int target) {
	    this.source = source;
	    this.item = item;
	    this.target = target;
	}
    }

    /**
     * Indices of the two smallest amounts in {@code amounts}. Null if fewer
     * than two entries. Ties keep earlier indices, matching a stable
     * smallest-then-next-smallest pick.
     */
    public static int[] twoSmallest(int[] amounts) {
	if(amounts == null || amounts.length < 2)
	    return null;
	int a = 0, b = 1;
	if(amounts[b] < amounts[a]) {
	    a = 1;
	    b = 0;
	}
	for(int i = 2; i < amounts.length; i++) {
	    if(amounts[i] < amounts[a]) {
		b = a;
		a = i;
	    } else if(amounts[i] < amounts[b]) {
		b = i;
	    }
	}
	return new int[] {a, b};
    }

    /**
     * True when a take+itemact actually stacked something: the taken pile
     * vanished, or the target's count went up. False means a full or
     * incompatible stack — retrying that pair just pick-up/drop-loops.
     */
    public static boolean stacked(boolean sourceGone, int destBefore, int destAfter) {
	return sourceGone || destAfter > destBefore;
    }
}
