package auto;

public class CheeseTrayFiller {
    public static final int TRAY_CAPACITY = 4;

    public interface Env {
	boolean isCancelled();
	boolean handEmpty();
	int trayCount();

	/**
	 * Current curd count in the given tray, read from its item-info
	 * (e.g. "Contents: 2 curds of goat" → 2). Returns -1 if unknown
	 * (info not yet loaded).
	 */
	int trayFill(int trayIndex);

	/**
	 * Acquire a curd onto the cursor. Blocks until the outcome is known.
	 * @return true if a curd is now held; false if no curds remain.
	 */
	boolean pickupCurd();

	/**
	 * Send the place action and return whether the tray's fill count
	 * actually increased. This is the authoritative signal — we're not
	 * inferring from timeouts; we compare tray state before and after
	 * the server has had a chance to reply.
	 * @return true if tray accepted the curd (count went up); false otherwise.
	 */
	boolean placeIntoTray(int trayIndex);

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
		if(fill >= 0 && fill >= TRAY_CAPACITY) { break; }

		if(env.handEmpty()) {
		    if(!env.pickupCurd()) {
			if(!env.handEmpty()) { env.dropHeld(); }
			return new Result(placed, null);
		    }
		}

		if(!env.placeIntoTray(i)) { break; }
		placed[i]++;
	    }
	}

	if(!env.handEmpty()) { env.dropHeld(); }
	return new Result(placed, env.isCancelled() ? "cancelled" : null);
    }
}
