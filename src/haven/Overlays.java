package haven;

/**
 * What a gob overlay is, without waiting for its sprite.
 *
 * <h3>Why this exists</h3>
 *
 * An overlay's {@code spr} is built lazily from its {@link Sprite.Mill}, so it
 * is null for the first moments after the overlay arrives — which is exactly
 * when anything reacting to a new overlay comes looking. Asking the mill
 * instead gives the answer immediately, and keeps giving it if the sprite
 * fails to construct at all.
 *
 * <h3>The trap this is here to stop repeating</h3>
 *
 * There are <b>two</b> mills carrying a resource and an sdt, and which one you
 * get depends on who made the overlay:
 *
 * <ul>
 *   <li>{@link OCache.OlSprite} — overlays the <b>server</b> puts on a gob.</li>
 *   <li>{@link Sprite.Mill.FromRes} — overlays the <b>client</b> adds, via
 *       {@code Gob.addol(res, sdt)}. Placement-ghost decorations and the like.</li>
 * </ul>
 *
 * They have identical shape and no common supertype that exposes it, so code
 * that checks for one silently never matches the other. Ask here instead of
 * picking a mill.
 */
public class Overlays {
    private Overlays() {}

    /** The resource behind an overlay's mill, or null if it does not carry one. */
    public static Indir<Resource> res(Sprite.Mill<?> sm) {
	if(sm instanceof OCache.OlSprite)
	    return(((OCache.OlSprite)sm).res);
	if(sm instanceof Sprite.Mill.FromRes)
	    return(((Sprite.Mill.FromRes)sm).res);
	return(null);
    }

    /** The sdt behind an overlay's mill, or null if it does not carry one. */
    public static byte[] sdt(Sprite.Mill<?> sm) {
	if(sm instanceof OCache.OlSprite)
	    return(((OCache.OlSprite)sm).sdt);
	if(sm instanceof Sprite.Mill.FromRes)
	    return(((Sprite.Mill.FromRes)sm).sdt);
	return(null);
    }

    public static Indir<Resource> res(Gob.Overlay ol) {
	return((ol == null) ? null : res(ol.sm));
    }

    public static byte[] sdt(Gob.Overlay ol) {
	return((ol == null) ? null : sdt(ol.sm));
    }
}
