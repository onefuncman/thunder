package auto;

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
}
