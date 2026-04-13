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
	    if(env.isCancelled()) { break outer; }

	    int initial = env.trayFill(i);
	    if(initial >= TRAY_CAPACITY) { continue; }
	    int toPlace = TRAY_CAPACITY - initial;

	    for (int n = 0; n < toPlace; n++) {
		if(env.isCancelled()) { break outer; }

		if(env.handEmpty()) {
		    if(!env.pickupCurd()) {
			if(!env.handEmpty()) { env.dropHeld(); }
			placed[i] = Math.max(0, env.trayFill(i) - initial);
			return new Result(placed, null);
		    }
		}

		env.placeIntoTray(i);
	    }

	    placed[i] = Math.max(0, env.trayFill(i) - initial);
	}

	if(!env.handEmpty()) { env.dropHeld(); }
	return new Result(placed, env.isCancelled() ? "cancelled" : null);
    }
}
