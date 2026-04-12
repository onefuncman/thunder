package auto;

public class CheeseTrayFiller {
    public static final int TRAY_CAPACITY = 4;

    public interface Env {
	boolean isCancelled();
	boolean handEmpty();
	int trayCount();

	/**
	 * Current curd count in the tray. Must block until item-info is loaded —
	 * the algorithm relies on this being an authoritative count.
	 */
	int trayFill(int trayIndex);

	/**
	 * Acquire a curd onto the cursor. Blocks until the outcome is known.
	 * @return true if a curd is now held; false if no curds remain.
	 */
	boolean pickupCurd();

	/**
	 * Send the place action and wait for the server round-trip to settle.
	 * Whether the tray accepted the curd is determined by re-reading
	 * {@link #trayFill}, not by any signal from this method.
	 */
	void placeIntoTray(int trayIndex);

	void dropHeld();
    }

    public static class Result {
	public final int[] perTray;
	public final int placed;
	public final String cancelReason;

	Result(int[] perTray, String cancelReason) {
	    this.perTray = perTray;
	    int sum = 0;
	    for (int n : perTray) sum += n;
	    this.placed = sum;
	    this.cancelReason = cancelReason;
	}
    }

    public static Result run(Env env) {
	int count = env.trayCount();
	int[] placed = new int[count];

	outer:
	for (int i = 0; i < count; i++) {
	    while (true) {
		if(env.isCancelled()) { break outer; }

		int fill = env.trayFill(i);
		if(fill >= TRAY_CAPACITY) { break; }

		if(env.handEmpty()) {
		    if(!env.pickupCurd()) {
			if(!env.handEmpty()) { env.dropHeld(); }
			return new Result(placed, null);
		    }
		}

		env.placeIntoTray(i);
		int fillAfter = env.trayFill(i);
		if(fillAfter > fill) { placed[i]++; }
		else { break; }
	    }
	}

	if(!env.handEmpty()) { env.dropHeld(); }
	return new Result(placed, env.isCancelled() ? "cancelled" : null);
    }
}
