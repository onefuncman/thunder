package thunder;

import haven.*;
import me.ender.minimap.MapFileUtils;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.*;
import java.util.*;

import static haven.MapFile.*;
import static haven.MCache.cmaps;

public class TileQuality {
    private static final String INDEX = "thunder-tq-index";
    private static final String GRID_NAME = "thunder-tq-grid-%x";
    private static final int GRID_VERSION = 2;

    public static final String KEY_STONE_PREFIX = "stone/";
    public static final String KEY_CRYSTAL = "crystal";
    public static final String KEY_SHELL = "shell";
    public static final String KEY_QUARTZ = "quartz";
    public static final String KEY_CATGOLD = "catgold";
    public static final String KEY_DIG = "dig";
    public static final String KEY_DIG_PREFIX = "dig/";
    public static final String KEY_WATER = "water";
    public static final String KEY_SPRING_WATER = "spring_water";
    public static final String KEY_SALT_WATER = "salt_water";

    static final byte GROUP_MINE = 1;
    static final byte GROUP_DIG = 2;
    static final byte GROUP_FILL_WATER = 3;
    static final byte GROUP_FILL_SPRING_WATER = 4;
    static final byte GROUP_FILL_SALT_WATER = 5;

    public static final String CURSOR_MINE = "gfx/hud/curs/mine";
    public static final String CURSOR_DIG = "gfx/hud/curs/dig";
    // fill cursor to be filled in once observed from a proto recording.

    public static final String OVERLAY_TAG = "tilequality";

    private final Object lock = new Object();
    private final Set<Long> gridIds = new HashSet<>();
    private final Map<Long, Map<Integer, Map<String, Short>>> grids = new HashMap<>();
    public final MapFile file;

    // Fill detection doesn't get a cursor-clear like mine/dig, so a TTL gates
    // attribution. 5s matches MilkingAssist and covers observed round-trip.
    private static final long FILL_TTL_MS = 5000;

    // At most one action is in flight at a time: a new click replaces it,
    // and it's cleared when the paginae cursor goes away. Items that were
    // captured into `retries` keep their own copy of the action, so clearing
    // here doesn't lose in-flight resolutions.
    private PendingAction currentPending;
    // Items whose quality wasn't ready on first tt; retried from GItem.tick.
    // WeakHashMap so destroyed items are GC-evicted without explicit cleanup.
    private final Map<GItem, PendingAction> retries = new WeakHashMap<>();

    // Static globals so the MiniMap renderer (running on a worker thread)
    // can find the active TileQuality and the current overlay state.
    private static volatile TileQuality current;
    public static volatile long seq = 0;
    public static volatile String selectedKind = null;

    public static TileQuality current() {return current;}

    public static void setSelectedKind(String kind) {
	if(!Objects.equals(selectedKind, kind)) {
	    selectedKind = kind;
	    seq++;
	}
    }

    public TileQuality(MapFile file) {
	this.file = file;
	MapFileUtils.load(file, this::loadIndex, INDEX);
	current = this;
    }

    /** Walks all known grids and renames matching keys. Returns the number of entries changed. */
    public int renameKey(String oldKey, String newKey) {
	if(oldKey.equals(newKey)) {return 0;}
	int total = 0;
	synchronized (lock) {
	    for(Long id : new ArrayList<>(gridIds)) {
		if(!grids.containsKey(id) && !loadGrid(id)) {continue;}
		Map<Integer, Map<String, Short>> grid = grids.get(id);
		if(grid == null) {continue;}
		boolean dirty = false;
		for(Map<String, Short> tile : grid.values()) {
		    Short val = tile.remove(oldKey);
		    if(val == null) {continue;}
		    Short existing = tile.get(newKey);
		    if(existing == null || val > existing) {tile.put(newKey, val);}
		    dirty = true;
		    total++;
		}
		if(dirty) {storeGrid(id, grid);}
	    }
	}
	return total;
    }

    // --- Pending action management ---

    static class PendingAction {
	final byte group;
	final Coord2d rc;
	final long deadline; // 0 = no TTL

	PendingAction(byte group, Coord2d rc) {
	    this(group, rc, 0);
	}

	PendingAction(byte group, Coord2d rc, long deadline) {
	    this.group = group;
	    this.rc = rc;
	    this.deadline = deadline;
	}

	boolean expired() {
	    return deadline > 0 && System.currentTimeMillis() > deadline;
	}
    }

    private static boolean isFillGroup(byte group) {
	return group == GROUP_FILL_WATER || group == GROUP_FILL_SPRING_WATER || group == GROUP_FILL_SALT_WATER;
    }

    /** Debug accessor for {@link TileQualityDebug}. */
    PendingAction debugPeekPending() {
	synchronized (lock) {return currentPending;}
    }

    static String debugGroupName(byte group) {
	switch (group) {
	    case GROUP_MINE: return "mine";
	    case GROUP_DIG: return "dig";
	    case GROUP_FILL_WATER: return "fill-water";
	    case GROUP_FILL_SPRING_WATER: return "fill-spring";
	    case GROUP_FILL_SALT_WATER: return "fill-salt";
	    default: return "unknown(" + group + ")";
	}
    }

    /**
     * Captures a mine/dig click based on the current root cursor. Called from
     * MapView.Selector.mmousedown (area mine: the server-created selector is up
     * with the mine cursor active) and from MapView.click (plain map clicks: dig
     * has no area-select mode -- the server only ever sends "sel" for mine -- so
     * dig arms from an ordinary click made while the dig cursor is up).
     */
    public static void markPendingForClick(Coord2d rc, GameUI gui) {
	if(gui == null || gui.tileQuality == null || gui.ui == null) {return;}
	byte group = groupForCursor(gui.ui.root.cursor);
	if(group < 0) {return;}
	TileQualityDebug.event("pending set: group=%s rc=%s", debugGroupName(group), rc);
	gui.tileQuality.setPending(group, rc);
    }

    /**
     * Called when a fresh mineout overlay (gfx/terobjs/mineout) appears on a gob.
     * The gob's position is the tile that was just mined — items will follow shortly.
     * For area-mine drags this fires once per tile in sequence, advancing the pending
     * to each tile in turn so per-tile quality attribution stays correct.
     */
    public static void onMineoutOverlay(Gob gob) {
	if(gob == null || gob.glob == null || gob.glob.sess == null) {return;}
	UI ui = gob.glob.sess.ui;
	if(ui == null || ui.gui == null || ui.gui.tileQuality == null) {return;}
	ui.gui.tileQuality.setPending(GROUP_MINE, gob.rc);
    }

    /**
     * Called from MapView.iteminteract's Hittest.hit() -- player clicked map while holding an item.
     * If the target is a recognized water source (well gob, or fresh/salt water tile), mark a
     * pending fill action keyed to the source's tile. The hand item's subsequent `tt` update
     * will carry Contents with the water quality, which we attribute back here.
     */
    public static void markPendingFillFromMap(GameUI gui, Coord2d mc, Gob clickedGob) {
	if(gui == null || gui.tileQuality == null) {return;}
	byte group;
	Coord2d rc;
	byte gobGroup = (clickedGob == null) ? -1 : groupForWaterGob(clickedGob);
	if(gobGroup >= 0) {
	    group = gobGroup;
	    rc = clickedGob.rc;
	} else {
	    Coord tc = mc.floor(MCache.tilesz);
	    if(auto.MapHelper.isSaltWaterTile(gui, tc)) {
		group = GROUP_FILL_SALT_WATER;
		rc = mc;
	    } else if(auto.MapHelper.isFreshWaterTile(gui, tc)) {
		group = GROUP_FILL_WATER;
		rc = mc;
	    } else {
		return; // not a water source; don't mark
	    }
	}
	long deadline = System.currentTimeMillis() + FILL_TTL_MS;
	gui.tileQuality.setPending(new PendingAction(group, rc, deadline));
    }

    /** Maps a clicked water-source gob to a fill group, or -1 if unknown. */
    private static byte groupForWaterGob(Gob gob) {
	try {
	    Resource res = gob.getres();
	    if(res == null) {return -1;}
	    String n = res.name;
	    if("gfx/terobjs/wellspring".equals(n)) {return GROUP_FILL_SPRING_WATER;}
	    if("gfx/terobjs/well".equals(n)) {return GROUP_FILL_WATER;}
	    return -1;
	} catch (Loading ignore) {
	    return -1;
	}
    }

    private static byte groupForCursor(Indir<Resource> cursor) {
	if(cursor == null) {return -1;}
	String name;
	try {
	    Resource r = cursor.get();
	    if(r == null) {return -1;}
	    name = r.name;
	} catch (Loading ignore) {return -1;}
	if(CURSOR_MINE.equals(name)) {return GROUP_MINE;}
	if(CURSOR_DIG.equals(name)) {return GROUP_DIG;}
	return -1;
    }

    private void setPending(byte group, Coord2d rc) {
	setPending(new PendingAction(group, rc));
    }

    private void setPending(PendingAction action) {
	synchronized (lock) {
	    currentPending = action;
	}
    }

    /** Called when the root cursor resource changes (from RootWidget.uimsg "curs"). */
    public static void onRootCursorChange(Indir<Resource> cursor, GameUI gui) {
	if(gui == null || gui.tileQuality == null) {return;}
	String name;
	try {
	    name = (cursor == null) ? null : (cursor.get() == null ? null : cursor.get().name);
	} catch (Loading ignore) {
	    return; // undecided; next tick we'll see it again
	}
	gui.tileQuality.onCursorName(name);
    }

    private void onCursorName(String cursorName) {
	synchronized (lock) {
	    PendingAction p = currentPending;
	    if(p == null) {return;}
	    if(!cursorMatchesGroup(cursorName, p.group)) {
		currentPending = null;
		TileQualityDebug.event("pending cleared: cursor now %s (was group=%s)", cursorName, debugGroupName(p.group));
	    }
	}
    }

    private static boolean cursorMatchesGroup(String cursorName, byte group) {
	switch (group) {
	    case GROUP_MINE: return CURSOR_MINE.equals(cursorName);
	    // Dig is a plain click action: the server keeps curs/dig up for the
	    // whole dig session (produce arrives with the cursor still set) and
	    // clears it when the action is cancelled, so dig is cursor-gated
	    // exactly like mine. Verified live 2026-08-13; there is no
	    // area-select for dig.
	    case GROUP_DIG: return CURSOR_DIG.equals(cursorName);
	    // Fill groups are TTL-gated: keep their pending until replaced.
	    default: return true;
	}
    }

    // --- Item quality resolution ---

    public static void onItemInfoUpdate(GItem item) {
	UI ui = item.ui;
	if(ui == null) {return;}
	GameUI gui = ui.gui;
	if(gui == null || gui.tileQuality == null) {return;}
	PendingAction action;
	synchronized (gui.tileQuality.lock) {
	    action = gui.tileQuality.currentPending;
	    if(action != null && action.expired()) {
		gui.tileQuality.currentPending = null;
		TileQualityDebug.event("pending expired: group=%s", debugGroupName(action.group));
		action = null;
	    }
	}
	if(action == null) {return;}
	gui.tileQuality.resolveItem(item, gui, action);
    }

    public static void onItemTick(GItem item) {
	UI ui = item.ui;
	if(ui == null) {return;}
	GameUI gui = ui.gui;
	if(gui == null || gui.tileQuality == null) {return;}
	PendingAction action;
	synchronized (gui.tileQuality.lock) {
	    action = gui.tileQuality.retries.get(item);
	}
	if(action == null) {return;}
	gui.tileQuality.resolveItem(item, gui, action);
    }

    private void resolveItem(GItem item, GameUI gui, PendingAction action) {
	if(!isEligibleItem(item, gui, action)) {return;}

	String key;
	try {
	    key = classifyItem(item, action);
	} catch (Loading l) {
	    synchronized (lock) {retries.put(item, action);}
	    return;
	}
	if(key == null) {
	    // Definitively not produce for this action (e.g. an Earthworm during a
	    // dig): drop any retry entry, or an item that entered `retries` while
	    // its info was loading would re-classify every tick forever.
	    synchronized (lock) {retries.remove(item);}
	    return;
	}

	double q;
	try {
	    q = item.quality();
	} catch (Loading l) {
	    q = 0;
	}
	if(q <= 0) {
	    synchronized (lock) {retries.put(item, action);}
	    return;
	}
	synchronized (lock) {retries.remove(item);}

	Coord2d loc = action.rc;
	if(action.group == GROUP_DIG) {
	    // The character walks to the clicked tile and digs the tile under
	    // itself, so the digger's position at item arrival is the accurate
	    // source tile.
	    Gob player = (gui.map == null) ? null : gui.map.player();
	    if(player != null) {loc = player.rc;}
	}

	Coord gc = loc.floor(MCache.tilesz);
	MCache.Grid grid;
	try {
	    grid = gui.ui.sess.glob.map.getgridt(gc);
	} catch (Exception e) {return;}
	if(grid == null) {return;}

	Coord tc = gc.sub(grid.gc.mul(MCache.cmaps));
	short val = (short) Math.min(Math.round(q * 10), Short.MAX_VALUE);
	TileQualityDebug.event("record %s q=%.1f tile=%s (group=%s)", key, q, gc, debugGroupName(action.group));
	recordQuality(grid.id, tc, val, key);
    }

    private static boolean isEligibleItem(GItem item, GameUI gui, PendingAction action) {
	if(isFillGroup(action.group)) {
	    return isInHand(item, gui);
	}
	return isInMainInventory(item, gui);
    }

    private static boolean isInHand(GItem item, GameUI gui) {
	if(gui == null || gui.hand == null) {return false;}
	for(GameUI.DraggedItem di : gui.hand) {
	    if(di != null && di.item == item) {return true;}
	}
	return false;
    }

    private static boolean isInMainInventory(GItem item, GameUI gui) {
	if(gui.maininv == null) {return false;}
	Widget w = item.parent;
	int depth = 0;
	while (w != null && depth < 12) {
	    if(w == gui.maininv) {return true;}
	    if(w instanceof GItem.ContentsWindow) {
		GItem cont = ((GItem.ContentsWindow) w).cont;
		w = (cont == null) ? null : cont.parent;
		depth++;
		continue;
	    }
	    w = w.parent;
	    depth++;
	}
	return false;
    }

    private static String classifyItem(GItem item, PendingAction action) {
	if(action.group == GROUP_MINE) {
	    return classifyMinedItem(item);
	} else if(action.group == GROUP_DIG) {
	    return classifyDugItem(item);
	} else if(isFillGroup(action.group)) {
	    return classifyFilledItem(item, action.group);
	}
	return null;
    }

    /**
     * Only classify when the container actually holds liquid content -- filters out
     * unrelated tt updates on the hand item. Content quality is read via `item.quality()`
     * which falls back to `contains.q` when the container has content.
     */
    private static String classifyFilledItem(GItem item, byte group) {
	item.info(); // force parse; throws Loading if not ready so we retry
	ItemData.Content content = item.contains.get();
	if(content == null || content.empty() || content.q == null || content.q.isEmpty()) {return null;}
	switch (group) {
	    case GROUP_FILL_WATER: return KEY_WATER;
	    case GROUP_FILL_SPRING_WATER: return KEY_SPRING_WATER;
	    case GROUP_FILL_SALT_WATER: return KEY_SALT_WATER;
	    default: return null;
	}
    }

    /**
     * A dig pending stays armed for the whole dig session (the dig cursor is up
     * until the action is cancelled), and digging yields side products beyond
     * terrain material (e.g. earthworms), so we gate on the item actually being
     * dig produce -- otherwise any quality item entering the inventory during a
     * dig session would be misattributed. Classification is by item NAME (not
     * res slug) so single items and stack contents produce the same kind key,
     * and new diggable materials (River Clay, Sea Clay, ...) work without a
     * res-name table. Rejected names are visible via the dev.tq event log.
     */
    private static String classifyDugItem(GItem item) {
	List<ItemInfo> info = item.info(); // throws Loading until parsed, so the item retries
	ItemInfo.Name name = ItemInfo.find(ItemInfo.Name.class, info);
	String key = (name == null || name.original == null) ? null : digKeyForName(name.original);
	if(key != null) {
	    TileQualityDebug.event("dug item name='%s' -> %s", name.original, key);
	    return key;
	}
	ItemData.Content content = item.contains.get();
	if(content != null && !content.empty()) {
	    String ckey = digKeyForName(content.name);
	    TileQualityDebug.event("dug stack content='%s' -> %s", content.name, ckey);
	    return ckey;
	}
	TileQualityDebug.event("dug item name='%s' rejected (not dig produce)",
			       (name == null) ? "(no name)" : name.original);
	return null;
    }

    /**
     * Pure helper: maps a dig-produce item name (single item, "X, stack of"
     * stack title, or stack contents) to a dig kind key, or null for non-dig
     * items. Any "<something> Clay" counts as dig produce; other materials are
     * listed explicitly.
     */
    static String digKeyForName(String name) {
	if(name == null) {return null;}
	String n = name.trim().toLowerCase();
	if(n.endsWith(", stack of")) {n = n.substring(0, n.length() - ", stack of".length()).trim();}
	switch (n) {
	    case "clay": case "soil": case "sand":
		return KEY_DIG_PREFIX + n;
	}
	if(n.endsWith(" clay")) {
	    return KEY_DIG_PREFIX + n.replace(' ', '-');
	}
	return null;
    }

    /** Throws Loading if the item's info isn't ready yet (gems need the name text). */
    private static String classifyMinedItem(GItem item) {
	String resname = item.resname();
	if(resname.isEmpty()) {return null;}

	switch (resname) {
	    case "gfx/invobjs/strangecrystal": return KEY_CRYSTAL;
	    case "gfx/invobjs/petrifiedshell": return KEY_SHELL;
	    case "gfx/invobjs/quarryquartz": return KEY_QUARTZ;
	    case "gfx/invobjs/catgold": return KEY_CATGOLD;
	    case "gfx/invobjs/gems/gemstone": return classifyGem(item);
	}

	if(resname.startsWith("gfx/invobjs/")) {
	    return KEY_STONE_PREFIX + resname.substring("gfx/invobjs/".length());
	}
	return null;
    }

    /**
     * Gem items share one res (gems/gemstone); the specific gem (Onyx, Ruby, ...) is in the
     * ItemInfo.Name text like "Fair Smooth Onyx" where the final word is the gem type.
     * Throws Loading if info isn't parsed yet.
     */
    private static String classifyGem(GItem item) {
	List<ItemInfo> info = item.info(); // may throw Loading
	ItemInfo.Name name = ItemInfo.find(ItemInfo.Name.class, info);
	if(name == null || name.original == null) {return null;}
	String[] parts = name.original.trim().split("\\s+");
	if(parts.length == 0) {return null;}
	String gem = parts[parts.length - 1].toLowerCase();
	return gem.isEmpty() ? null : gem;
    }

    // --- Display ---

    public static String displayName(String key) {
	if(key.startsWith(KEY_STONE_PREFIX)) {
	    String rock = key.substring(KEY_STONE_PREFIX.length());
	    return Character.toUpperCase(rock.charAt(0)) + rock.substring(1);
	}
	if(key.startsWith(KEY_DIG_PREFIX)) {
	    String mat = key.substring(KEY_DIG_PREFIX.length());
	    switch (mat) {
		// res-slug keys from the interim build; new keys are name-derived
		case "clay-gray": return "Ball Clay";
		case "clay-acre": return "Acre Clay";
	    }
	    StringBuilder b = new StringBuilder();
	    for(String w : mat.split("-")) {
		if(w.isEmpty()) {continue;}
		if(b.length() > 0) {b.append(' ');}
		b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
	    }
	    return b.toString();
	}
	switch (key) {
	    case KEY_CRYSTAL: return "Strange Crystal";
	    case KEY_SHELL: return "Petrified Shell";
	    case KEY_QUARTZ: return "Quarryartz";
	    case KEY_CATGOLD: return "Cat's Gold";
	    case KEY_DIG: return "Dig";
	    case KEY_WATER: return "Water";
	    case KEY_SPRING_WATER: return "Spring Water";
	    case KEY_SALT_WATER: return "Salt Water";
	    default:
		if(key.isEmpty()) {return key;}
		return Character.toUpperCase(key.charAt(0)) + key.substring(1);
	}
    }

    /** Quality palette: highest threshold ≤ q wins; q<1 → no color (null). */
    private static final int[] Q_THRESHOLDS = {1, 10, 50, 100, 200, 300, 400};
    private static final Color[] Q_COLORS = {
	new Color(180, 180, 180),
	new Color(255, 255, 255),
	new Color(0, 214, 10),
	new Color(0, 131, 255),
	new Color(165, 0, 255),
	new Color(255, 114, 0),
	new Color(255, 0, 0),
    };

    /** Returns the palette color for a stored qualityX10, or null if below threshold. */
    public static Color colorFor(short qualityX10) {
	double q = qualityX10 / 10.0;
	Color picked = null;
	for(int i = 0; i < Q_THRESHOLDS.length; i++) {
	    if(q >= Q_THRESHOLDS[i]) {picked = Q_COLORS[i];}
	}
	return picked;
    }

    /** Snapshot of one tile's stored kinds, for use by overlay & search. */
    public static class TileSnapshot {
	public final long gridId;
	public final int tileIdx;
	public final Map<String, Short> kinds;
	public TileSnapshot(long gridId, int tileIdx, Map<String, Short> kinds) {
	    this.gridId = gridId;
	    this.tileIdx = tileIdx;
	    this.kinds = kinds;
	}
    }

    /** Snapshot a single grid's tile data for rendering. Empty map if unknown. */
    public Map<Integer, Map<String, Short>> snapshotGrid(long gridId) {
	synchronized (lock) {
	    if(!gridIds.contains(gridId)) {return Collections.emptyMap();}
	    if(!grids.containsKey(gridId)) {loadGrid(gridId);}
	    Map<Integer, Map<String, Short>> g = grids.get(gridId);
	    if(g == null) {return Collections.emptyMap();}
	    Map<Integer, Map<String, Short>> copy = new HashMap<>(g.size());
	    for(Map.Entry<Integer, Map<String, Short>> e : g.entrySet()) {
		copy.put(e.getKey(), new HashMap<>(e.getValue()));
	    }
	    return copy;
	}
    }

    /** Snapshot every stored entry. Each tile yields one TileSnapshot. */
    public List<TileSnapshot> snapshotAll() {
	synchronized (lock) {
	    // Ensure all known grids are loaded.
	    for(Long id : new ArrayList<>(gridIds)) {
		if(!grids.containsKey(id)) {loadGrid(id);}
	    }
	    List<TileSnapshot> out = new ArrayList<>();
	    for(Map.Entry<Long, Map<Integer, Map<String, Short>>> ge : grids.entrySet()) {
		long gid = ge.getKey();
		for(Map.Entry<Integer, Map<String, Short>> te : ge.getValue().entrySet()) {
		    out.add(new TileSnapshot(gid, te.getKey(), new HashMap<>(te.getValue())));
		}
	    }
	    return out;
	}
    }

    /**
     * Render one grid's quality overlay. Color per tile = color for
     * `selectedKind`'s quality if set, else color for max quality across all kinds.
     * Tiles without data are transparent.
     */
    public BufferedImage olrender(long gridId) {
	Map<Integer, Map<String, Short>> g = snapshotGrid(gridId);
	WritableRaster buf = PUtils.imgraster(cmaps);
	if(g.isEmpty()) {return PUtils.rasterimg(buf);}
	String filter = selectedKind;
	for(Map.Entry<Integer, Map<String, Short>> te : g.entrySet()) {
	    int idx = te.getKey();
	    int x = idx % cmaps.x;
	    int y = idx / cmaps.x;
	    if(x < 0 || x >= cmaps.x || y < 0 || y >= cmaps.y) {continue;}
	    short q;
	    if(filter != null) {
		Short v = te.getValue().get(filter);
		if(v == null) {continue;}
		q = v;
	    } else {
		short max = 0;
		for(Short v : te.getValue().values()) {if(v > max) {max = v;}}
		q = max;
	    }
	    Color col = colorFor(q);
	    if(col == null) {continue;}
	    buf.setSample(x, y, 0, col.getRed());
	    buf.setSample(x, y, 1, col.getGreen());
	    buf.setSample(x, y, 2, col.getBlue());
	    buf.setSample(x, y, 3, 200);
	}
	return PUtils.rasterimg(buf);
    }

    // --- Storage ---

    private boolean recordQuality(long gridId, Coord tc, short quality, String key) {
	synchronized (lock) {
	    Map<Integer, Map<String, Short>> grid = grids.get(gridId);
	    if(grid == null) {
		if(!loadGrid(gridId)) {
		    grid = new HashMap<>();
		    grids.put(gridId, grid);
		    gridIds.add(gridId);
		    storeIndex();
		} else {
		    grid = grids.get(gridId);
		}
	    }
	    int idx = tc.x + tc.y * MCache.cmaps.x;
	    Map<String, Short> tileData = grid.computeIfAbsent(idx, k -> new HashMap<>());
	    Short existing = tileData.get(key);
	    if(existing == null || quality > existing) {
		tileData.put(key, quality);
		storeGrid(gridId, grid);
		seq++;
		return true;
	    }
	    return false;
	}
    }

    // --- Persistence ---

    private void storeIndex() {
	synchronized (lock) {
	    OutputStream fp;
	    try {
		fp = file.sstore(INDEX);
	    } catch (IOException e) {
		throw (new StreamMessage.IOError(e));
	    }
	    try (StreamMessage out = new StreamMessage(fp)) {
		out.adduint8(1);
		for (Long id : gridIds) {
		    out.addint64(id);
		}
	    }
	}
    }

    private void storeGrid(long id, Map<Integer, Map<String, Short>> grid) {
	OutputStream fp;
	try {
	    fp = file.sstore(GRID_NAME, id);
	} catch (IOException e) {
	    throw (new StreamMessage.IOError(e));
	}
	try (StreamMessage out = new StreamMessage(fp)) {
	    out.adduint8(GRID_VERSION);
	    ZMessage zout = new ZMessage(out);
	    int count = 0;
	    for (Map<String, Short> tileData : grid.values()) {
		count += tileData.size();
	    }
	    zout.addint32(count);
	    for (Map.Entry<Integer, Map<String, Short>> tileEntry : grid.entrySet()) {
		int idx = tileEntry.getKey();
		for (Map.Entry<String, Short> kindEntry : tileEntry.getValue().entrySet()) {
		    zout.addint16((short) idx);
		    zout.addstring(kindEntry.getKey());
		    zout.addint16(kindEntry.getValue());
		}
	    }
	    zout.finish();
	}
    }

    private boolean loadIndex(StreamMessage data) {
	synchronized (lock) {
	    int ver = data.uint8();
	    if(ver == 1) {
		while (!data.eom()) {
		    gridIds.add(data.int64());
		}
		return true;
	    } else {
		warn("unknown mapfile thunder-tq-index version: %d", ver);
	    }
	}
	return false;
    }

    private boolean loadGrid(long id) {
	synchronized (lock) {
	    if(!gridIds.contains(id)) {return false;}
	    if(grids.containsKey(id)) {return true;}

	    if(!MapFileUtils.load(file, data -> loadGridData(data, id), GRID_NAME, id)) {
		grids.remove(id);
		gridIds.remove(id);
		storeIndex();
		return false;
	    }
	}
	return true;
    }

    private boolean loadGridData(StreamMessage data, long id) {
	int ver = data.uint8();
	if(ver == GRID_VERSION) {
	    ZMessage zdata = new ZMessage(data);
	    int count = zdata.int32();
	    Map<Integer, Map<String, Short>> grid = new HashMap<>();
	    for (int i = 0; i < count; i++) {
		int idx = zdata.int16() & 0xFFFF;
		String key = zdata.string();
		short quality = (short) zdata.int16();
		grid.computeIfAbsent(idx, k -> new HashMap<>()).put(key, quality);
	    }
	    grids.put(id, grid);
	    return true;
	} else {
	    warn("unknown mapfile thunder-tq-grid %d version: %d (expected %d)", id, ver, GRID_VERSION);
	}
	return false;
    }

    // --- Trim ---

    public static void trim(Session sess, List<Long> removed) {
	UI ui = sess.ui;
	if(ui == null) {return;}
	GameUI gui = ui.gui;
	if(gui == null || gui.tileQuality == null) {return;}
	gui.tileQuality.doTrim(removed);
    }

    private void doTrim(List<Long> removed) {
	synchronized (lock) {
	    if(removed == null) {
		grids.clear();
	    } else {
		for (Long id : removed) {
		    grids.remove(id);
		}
	    }
	}
    }
}
