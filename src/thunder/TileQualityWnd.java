package thunder;

import haven.*;

import java.awt.Color;
import java.util.*;

import static haven.MCache.cmaps;

public class TileQualityWnd extends WindowX {
    private static final String ALL = "All kinds";
    private static final Color ROW_EVEN = new Color(255, 255, 255, 16);
    private static final Color ROW_ODD = new Color(255, 255, 255, 32);
    private static final Text.Foundry ELF = CharWnd.attrf;
    private static final int ELH = ELF.height() + UI.scale(2);

    private enum Sort {QUALITY, DISTANCE}

    private final EntryList list;
    private final KindList kindList;
    private final CheckBox overlayBox;
    private final CheckBox segmentOnlyBox;
    private final Label sortLabel;

    private Sort sortMode = Sort.QUALITY;
    private boolean segmentOnly = true;
    private long lastSeq = -1;
    private String lastKind = "<unset>";
    private List<Entry> entries = Collections.emptyList();

    public TileQualityWnd() {
	super(Coord.z, "Tile Quality");
	justclose = true;

	overlayBox = add(new CheckBox("Show overlay") {
	    @Override
	    public void changed(boolean val) {
		if(ui != null && ui.gui != null && ui.gui.mapfile != null) {
		    ui.gui.mapfile.toggleol(TileQuality.OVERLAY_TAG, val);
		}
	    }
	}, Coord.z);
	int h = overlayBox.sz.y + UI.scale(3);

	segmentOnlyBox = add(new CheckBox("Current segment only") {
	    @Override
	    public void changed(boolean val) {
		segmentOnly = val;
		refresh(false);
	    }
	}, 0, h);
	segmentOnlyBox.a = segmentOnly;
	h += segmentOnlyBox.sz.y + UI.scale(5);

	add(new Label("Kind"), 0, h);
	sortLabel = add(new Label("Sort: quality"), UI.scale(135), h);
	add(new Button(UI.scale(50), "Quality") {
	    @Override
	    public void click() {sortMode = Sort.QUALITY; sortLabel.settext("Sort: quality"); refresh(false);}
	}, UI.scale(220), h - UI.scale(2));
	add(new Button(UI.scale(60), "Distance") {
	    @Override
	    public void click() {sortMode = Sort.DISTANCE; sortLabel.settext("Sort: distance"); refresh(false);}
	}, UI.scale(275), h - UI.scale(2))
	    .settip("Tile-distance from your current position. Only valid within your current map segment - a segment is a contiguous explored area (e.g. the surface, or one cave system). Tiles in other segments show '-' since their relative position is unknown.", UI.scale(280));
	h += UI.scale(20);

	kindList = add(new KindList(UI.scale(125), 14), 0, h);
	list = add(new EntryList(UI.scale(320), 14), UI.scale(135), h);
	pack();

	refresh(true);
    }

    public static void toggle(UI ui) {
	if(ui == null || ui.gui == null) {return;}
	if(ui.gui.tileQualityWnd == null) {
	    ui.gui.tileQualityWnd = ui.gui.add(new TileQualityWnd(), 100, 100);
	} else {
	    ui.gui.tileQualityWnd.destroy();
	}
    }

    @Override
    public void destroy() {
	super.destroy();
	if(ui != null && ui.gui != null) {ui.gui.tileQualityWnd = null;}
    }

    @Override
    public void tick(double dt) {
	super.tick(dt);
	if(lastSeq != TileQuality.seq || !Objects.equals(lastKind, TileQuality.selectedKind)) {
	    refresh(false);
	} else if(!entries.isEmpty()) {
	    updateDistances();
	}
    }

    private void updateDistances() {
	PlayerLoc me = playerLoc();
	for(Entry e : entries) {
	    if(me == null || e.entrySegTc == null || e.entrySeg != me.segId) {
		e.distance = -1;
	    } else {
		int dx = e.entrySegTc.x - me.segTc.x;
		int dy = e.entrySegTc.y - me.segTc.y;
		e.distance = (int) Math.round(Math.sqrt((double) dx * dx + (double) dy * dy));
	    }
	}
    }

    /** Player position resolved into segment + segment-tc, or null if unavailable. */
    private static class PlayerLoc {
	final long segId;
	final Coord segTc;
	PlayerLoc(long segId, Coord segTc) {this.segId = segId; this.segTc = segTc;}
    }

    private PlayerLoc playerLoc() {
	if(ui == null || ui.gui == null || ui.gui.map == null || ui.gui.mapfile == null) {return null;}
	Gob pl = ui.gui.map.player();
	if(pl == null) {return null;}
	MapFile file = ui.gui.mapfile.file;
	MCache mcache = ui.sess.glob.map;
	Coord tileWorld = pl.rc.floor(MCache.tilesz);
	Coord gridWorld = tileWorld.div(cmaps);
	MCache.Grid mcg;
	try {mcg = mcache.getgrid(gridWorld);} catch(Loading l) {return null;}
	if(mcg == null) {return null;}
	if(!file.lock.readLock().tryLock()) {return null;}
	try {
	    MapFile.GridInfo info = file.gridinfo.get(mcg.id);
	    if(info == null) {return null;}
	    Coord local = tileWorld.sub(gridWorld.mul(cmaps));
	    return new PlayerLoc(info.seg, info.sc.mul(cmaps).add(local));
	} finally {
	    file.lock.readLock().unlock();
	}
    }

    private void refresh(boolean rebuildKinds) {
	TileQuality tq = TileQuality.current();
	List<TileQuality.TileSnapshot> snaps = (tq == null) ? Collections.emptyList() : tq.snapshotAll();
	PlayerLoc me = playerLoc();
	MapFile file = (ui != null && ui.gui != null && ui.gui.mapfile != null) ? ui.gui.mapfile.file : null;

	if(rebuildKinds) {
	    Set<String> kinds = new TreeSet<>();
	    for(TileQuality.TileSnapshot s : snaps) {kinds.addAll(s.kinds.keySet());}
	    List<String> kindOpts = new ArrayList<>(kinds.size() + 1);
	    kindOpts.add(ALL);
	    kindOpts.addAll(kinds);
	    kindList.setItems(kindOpts);
	    kindList.sel = ALL;
	}

	String filter = TileQuality.selectedKind;
	List<Entry> entries = new ArrayList<>();
	for(TileQuality.TileSnapshot s : snaps) {
	    long entrySeg = -1;
	    Coord entrySegTc = null;
	    if(file != null && file.lock.readLock().tryLock()) {
		try {
		    MapFile.GridInfo info = file.gridinfo.get(s.gridId);
		    if(info != null) {
			entrySeg = info.seg;
			int tx = s.tileIdx % cmaps.x;
			int ty = s.tileIdx / cmaps.x;
			entrySegTc = info.sc.mul(cmaps).add(tx, ty);
		    }
		} finally {file.lock.readLock().unlock();}
	    }
	    if(segmentOnly && me != null && entrySeg != me.segId) {continue;}

	    int dist = -1;
	    if(me != null && entrySegTc != null && entrySeg == me.segId) {
		int dx = entrySegTc.x - me.segTc.x;
		int dy = entrySegTc.y - me.segTc.y;
		dist = (int) Math.round(Math.sqrt((double) dx * dx + (double) dy * dy));
	    }

	    for(Map.Entry<String, Short> ke : s.kinds.entrySet()) {
		if(filter != null && !filter.equals(ke.getKey())) {continue;}
		entries.add(new Entry(s.gridId, s.tileIdx, ke.getKey(), ke.getValue(), entrySeg, entrySegTc, dist));
	    }
	}
	entries.sort((a, b) -> {
	    if(sortMode == Sort.DISTANCE) {
		// Unknown distance (-1) sorts last, ties broken by quality desc.
		int ad = a.distance < 0 ? Integer.MAX_VALUE : a.distance;
		int bd = b.distance < 0 ? Integer.MAX_VALUE : b.distance;
		int c = Integer.compare(ad, bd);
		if(c != 0) {return c;}
	    }
	    return Short.compare(b.q, a.q);
	});
	this.entries = entries;
	list.setItems(entries);

	lastSeq = TileQuality.seq;
	lastKind = TileQuality.selectedKind;
    }

    private void selectKind(String kind) {
	String newSel = ALL.equals(kind) ? null : kind;
	TileQuality.setSelectedKind(newSel);
	if(newSel != null && ui != null && ui.gui != null && ui.gui.mapfile != null) {
	    ui.gui.mapfile.toggleol(TileQuality.OVERLAY_TAG, true);
	    overlayBox.set(true);
	}
    }

    private void jumpTo(Entry e) {
	if(ui == null || ui.gui == null || ui.gui.mapfile == null) {return;}
	MapFile file = ui.gui.mapfile.file;
	MapFile.GridInfo info = file.gridinfo.get(e.gridId);
	if(info == null) {return;}
	int tx = e.tileIdx % cmaps.x;
	int ty = e.tileIdx / cmaps.x;
	Coord tc = info.sc.mul(cmaps).add(tx, ty);
	ui.gui.mapfile.view.center(new MiniMap.SpecLocator(info.seg, tc));
	if(!ui.gui.mapfile.visible()) {ui.gui.mapfile.show();}
    }

    static class Entry {
	final long gridId;
	final int tileIdx;
	final String kind;
	final short q;
	final long entrySeg;    // -1 if unresolved
	final Coord entrySegTc; // null if unresolved
	int distance;           // tiles from player; -1 if unknown / cross-segment
	Entry(long gridId, int tileIdx, String kind, short q, long entrySeg, Coord entrySegTc, int distance) {
	    this.gridId = gridId;
	    this.tileIdx = tileIdx;
	    this.kind = kind;
	    this.q = q;
	    this.entrySeg = entrySeg;
	    this.entrySegTc = entrySegTc;
	    this.distance = distance;
	}
    }

    private class KindList extends Listbox<String> {
	private List<String> items = Collections.emptyList();

	KindList(int w, int h) {
	    super(w, h, ELH);
	    bgcolor = new Color(0, 0, 0, 84);
	}

	void setItems(List<String> items) {this.items = items;}

	@Override
	protected String listitem(int idx) {return items.get(idx);}

	@Override
	protected int listitems() {return items.size();}

	@Override
	protected void drawitem(GOut g, String item, int idx) {
	    g.chcolor((idx % 2 == 0) ? ROW_EVEN : ROW_ODD);
	    g.frect(Coord.z, g.sz());
	    g.chcolor();
	    String label = ALL.equals(item) ? item : TileQuality.displayName(item);
	    g.atext(label, new Coord(UI.scale(4), ELH / 2), 0, 0.5);
	}

	@Override
	public void change(String item) {
	    super.change(item);
	    if(item != null) {selectKind(item);}
	}
    }

    private class EntryList extends FilteredListBox<Entry> {
	EntryList(int w, int h) {
	    super(w, h, ELH);
	    bgcolor = new Color(0, 0, 0, 84);
	    showFilterText = true;
	}

	@Override
	protected boolean match(Entry e, String filter) {
	    if(filter.isEmpty()) {return true;}
	    return TileQuality.displayName(e.kind).toLowerCase().contains(filter.toLowerCase());
	}

	@Override
	protected void drawitem(GOut g, Entry e, int idx) {
	    g.chcolor((idx % 2 == 0) ? ROW_EVEN : ROW_ODD);
	    g.frect(Coord.z, g.sz());
	    Color qcol = TileQuality.colorFor(e.q);
	    if(qcol != null) {
		g.chcolor(qcol.getRed(), qcol.getGreen(), qcol.getBlue(), 255);
	    } else {
		g.chcolor();
	    }
	    g.atext(String.format("%.1f", e.q / 10.0), new Coord(UI.scale(4), ELH / 2), 0, 0.5);
	    g.chcolor();
	    g.atext(TileQuality.displayName(e.kind), new Coord(UI.scale(50), ELH / 2), 0, 0.5);
	    String distStr = (e.distance < 0) ? "-" : (e.distance + "t");
	    g.atext(distStr, new Coord(UI.scale(255), ELH / 2), 0, 0.5);
	}

	@Override
	public void change(Entry e) {
	    super.change(e);
	    if(e != null) {jumpTo(e);}
	}
    }
}
