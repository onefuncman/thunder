package thunder.cookbook;

import haven.Config;
import haven.PUtils;
import haven.Resource;
import haven.TexI;
import haven.Tex;
import haven.CharWnd;
import haven.Coord;
import haven.Loading;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared icon-loading logic for the three windows that display item icons
 * (CookbookWnd, CookbookPlanWnd, IngredientIconTestWnd): composites one of
 * the game's own generic per-category raw-meat backdrops behind bare
 * per-animal/per-fish/per-bird designation badges.
 *
 * Confirmed directly by decompiling real .res files fetched from
 * brodgar.io: most "meat-<name>" resources (land animals, fish, and birds
 * alike -- e.g. "meat-badger", "meat-perch", "meat-chicken") are just a
 * small ~800-byte designation badge on a transparent background, meant to
 * be drawn over a shared template at render time (real in-game compositing
 * happens via server-published sprite code we can't run outside a live
 * session, not from static resource data). Three such templates exist,
 * each a plain shape with a "%"-format tooltip confirming it's a shared
 * backdrop, not a real item:
 *  - "gfx/invobjs/meat-raw" ("Raw %") -- land mammals, default/fallback
 *  - "gfx/invobjs/meat-filet" ("Filet of %") -- fish
 *  - "gfx/invobjs/meat-poultry" ("% Meat") -- birds
 * Species are bucketed into FISH_SPECIES/BIRD_SPECIES by hand (no
 * in-resource category metadata exists to read this from); anything not
 * listed falls back to the land-mammal "Raw %" backdrop, which is also
 * harmless for non-animal badges since compositing is a no-op when the
 * base image already covers the backdrop entirely. See
 * docs/cookbook-integration.md.
 */
public class ItemIconUtil {
    private static final String RAW_BACKDROP_RES = "gfx/invobjs/meat-raw";
    private static final String FISH_BACKDROP_RES = "gfx/invobjs/meat-filet";
    private static final String POULTRY_BACKDROP_RES = "gfx/invobjs/meat-poultry";
    private static final String BUG_BACKDROP_RES = "gfx/invobjs/meat-weird";

    // The "meat-*" resources that are themselves shared backdrop templates
    // (format-string tooltips like "Raw %"/"Filet of %"/"% Meat"/"Smoked %"),
    // not real per-animal badges -- never composite anything behind these.
    private static final Set<String> TEMPLATE_BASES = new HashSet<>(Arrays.asList(
        "meat-raw", "meat-filet", "meat-poultry", "meat-weird",
        "meat-dfilet", "meat-kfilet", "meat-rfilet", "meat-sfilet", "meat-kpoultry",
        "meat-crust", "meat-smoke", "meat-smoked", "meat-roast", "meat-spitroast"
    ));

    private static final Set<String> FISH_SPECIES = new HashSet<>(Arrays.asList(
        "asp", "bass", "bream", "brill", "burbot", "carp", "catfish", "caveangler", "chub", "cod", "eel",
        "grayling", "haddock", "herring", "ide", "lavaret", "mullet", "perch", "pike", "plaice", "pomfret",
        "roach", "rosefish", "ruffe", "saithe", "salmon", "silverbream", "smelt", "sturgeon", "trout",
        "whiting", "zander", "zope"
    ));

    private static final Set<String> BIRD_SPECIES = new HashSet<>(Arrays.asList(
        "bullfinch", "chicken", "crane", "dovechick", "duck", "eagleowl", "garefowl", "goldeneagle",
        "goshawk", "magpie", "mallard", "pelican", "ptarmigan", "quail", "rockdove", "seagull", "swan", "woodgrouse"
    ));

    // Confirmed directly by the user (real in-game item, tooltip "Ant Meat"): bugs use
    // "gfx/invobjs/meat-weird", not "meat-crust" as first guessed from its shape alone.
    // meat-weird's own tooltip is "% Meat" ("Ant" -> "Ant Meat"), matching exactly, and
    // it's a smooth olive-green blob -- meat-crust's spiky shell shape was a red herring
    // (maybe meant for actual crustaceans -- crab, lobster, clam -- but that's untested,
    // so it stays unused rather than guessed at again).
    private static final Set<String> BUG_SPECIES = new HashSet<>(Arrays.asList(
        "ant", "bee", "boreworm", "cavelouse"
    ));

    private static String baseName(String resName) {
        int slash = resName.lastIndexOf('/');
        return (slash >= 0) ? resName.substring(slash + 1) : resName;
    }

    /** True if this resource is a bare per-animal badge that wants a generic raw-meat backdrop behind it. */
    private static boolean needsBackdrop(String resName) {
        String base = baseName(resName);
        return base.startsWith("meat-") && !TEMPLATE_BASES.contains(base);
    }

    /** Which generic backdrop applies: fish get a fillet shape, birds get a poultry shape, bugs get a shell shape, everything else gets the land-meat chunk. */
    private static String backdropFor(String resName) {
        String base = baseName(resName);
        String species = base.substring("meat-".length());
        if(FISH_SPECIES.contains(species)) {return FISH_BACKDROP_RES;}
        if(BIRD_SPECIES.contains(species)) {return POULTRY_BACKDROP_RES;}
        if(BUG_SPECIES.contains(species)) {return BUG_BACKDROP_RES;}
        return RAW_BACKDROP_RES;
    }

    /**
     * Loads (and, when applicable, composites) the full icon image for a
     * resource. Throws Loading if the resource ITSELF isn't cached yet --
     * same retry-next-frame contract as a plain
     * Resource.remote().load(...).get() call.
     *
     * The backdrop is best-effort and never blocks: callers cache whatever
     * this returns permanently (see e.g. CookbookPlanWnd.icon()), so a
     * Loading on the backdrop fetch is re-thrown just like a Loading on the
     * primary resource -- caught and diagnosed as "BACKDROP FAIL" once
     * (confirmed via the debug log: a rarely-touched backdrop like
     * meat-crust, used only by bugs, can still be mid-fetch the first time
     * anything needs it), it used to be swallowed here and the bare badge
     * got cached FOREVER with no backdrop, never retried. Real (non-Loading)
     * backdrop failures still fall back to the bare badge without retrying,
     * since the compositing pipeline itself is confirmed reliable now (see
     * the TYPE_INT_ARGB/coercergba fix below) -- a genuine failure here
     * means the backdrop resource itself is broken, not just slow.
     */
    public static BufferedImage loadIconImage(String resName) throws Loading {
        BufferedImage img;
        try {
            img = Resource.remote().load(resName).get().layer(Resource.imgc).img;
        } catch(Loading l) {
            diagOnce("primary-loading:" + resName, "PRIMARY LOADING (will retry): " + resName);
            throw l;
        } catch(RuntimeException e) {
            diagOnce("primary-fail:" + resName, "PRIMARY FAIL: " + resName + " -> " + e.getClass().getName() + ": " + e.getMessage());
            throw e;
        }
        if(img == null) {
            diagOnce("primary-null:" + resName, "PRIMARY has no image layer (null img): " + resName);
        }
        if(needsBackdrop(resName)) {
            String backdropRes = backdropFor(resName);
            BufferedImage backdrop;
            try {
                backdrop = Resource.remote().load(backdropRes).get().layer(Resource.imgc).img;
            } catch(Loading l) {
                diagOnce("backdrop-loading:" + backdropRes, "BACKDROP LOADING (will retry): " + backdropRes + " (for " + resName + ")");
                throw l;
            } catch(RuntimeException e) {
                diagOnce("backdrop-fail:" + resName, "BACKDROP FAIL for " + resName + " (wanted " + backdropRes + ") -> "
                    + e.getClass().getName() + ": " + e.getMessage());
                backdrop = null; // genuine failure, not just slow -- fall back to the bare badge, don't retry forever
            }
            if((backdrop == null) && (img != null)) {
                diagOnce("backdrop-unavailable:" + backdropRes, "BACKDROP unavailable (see FAIL log above), showing bare badge: " + backdropRes);
            } else if((backdrop != null) && (img != null)) {
                img = composite(backdrop, img);
                diagOnce("composite-ok:" + resName, "COMPOSITE OK: " + resName + " (backdrop " + backdropRes + ") badge/out="
                    + img.getWidth() + "x" + img.getHeight());
            }
        }
        return img;
    }

    // Diagnostics for the "meat icons went blank" report -- logs each distinct
    // event once (not every frame) to cookbook-icon-debug.log next to the
    // other cookbook-*.json save files, so this can be read back directly
    // instead of asking the user to relay error text by hand.
    private static final Set<String> loggedOnce = ConcurrentHashMap.newKeySet();

    private static void diagOnce(String key, String msg) {
        if(!loggedOnce.add(key)) {return;}
        try {
            File f = Config.getFile("cookbook-icon-debug.log");
            try(FileWriter fw = new FileWriter(f, true)) {
                fw.write(System.currentTimeMillis() + " " + msg + "\n");
            }
        } catch(Exception ignored) {}
    }

    private static BufferedImage composite(BufferedImage base, BufferedImage top) {
        // Canvas = the badge's own size. Most badges (e.g. meat-reindeer) match the
        // backdrop's native 32x32 exactly, but a few (e.g. meat-roedeer, meat-crane) are
        // full 128x128 portrait-style badges -- scaling the backdrop UP to fill the same
        // canvas keeps it proportioned correctly behind the badge either way. The earlier
        // approach (canvas = max(base,top), backdrop drawn at its own native size) left a
        // tiny 32x32 backdrop lost in the middle of a 128x128 canvas for those, which then
        // shrank to a "barely there" result once downscaled to icon size.
        int w = top.getWidth(), h = top.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(base, 0, 0, w, h, null);
        g.drawImage(top, 0, 0, w, h, null);
        g.dispose();
        // TYPE_INT_ARGB uses a DirectColorModel, which PUtils.convolvedown's
        // byte-interleaved raster (ComponentColorModel-shaped) is NOT
        // compatible with -- convolvedown's `new BufferedImage(img.getColorModel(), ...)`
        // throws IllegalArgumentException for it, silently swallowed by every
        // caller's generic catch(Exception), which permanently cached a null
        // Tex (blank icon) despite this method itself succeeding. Coerce to
        // the same cm_rgba/byte-raster shape every resource-loaded image
        // already uses, same as PUtils.coercergba does for any foreign image.
        return PUtils.coercergba(out, false);
    }

    /** Convenience: loadIconImage() downscaled to the given icon size, ready to draw. */
    public static Tex loadIcon(String resName, Coord iconSz) throws Loading {
        BufferedImage img = loadIconImage(resName);
        try {
            return new TexI(PUtils.convolvedown(img, iconSz, CharWnd.iconfilter));
        } catch(RuntimeException e) {
            diagOnce("scale-fail:" + resName, "SCALE/TEX FAIL for " + resName + " (img=" + img.getWidth() + "x" + img.getHeight()
                + " type=" + img.getType() + ") -> " + e.getClass().getName() + ": " + e.getMessage());
            throw e;
        }
    }
}
