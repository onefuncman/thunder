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

    private final EntryList list;
    private final KindList kindList;
    private final CheckBox overlayBox;
    private long lastSeq = -1;
    private String lastKind = "<unset>";

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
	int h = overlayBox.sz.y + UI.scale(5);

	add(new Label("Kind"), 0, h);
	add(new Label("Captures (q desc)"), UI.scale(135), h);
	h += UI.scale(14);

	kindList = add(new KindList(UI.scale(125), 14), 0, h);
	list = add(new EntryList(UI.scale(280), 14), UI.scale(135), h);
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
	}
    }

    private void refresh(boolean rebuildKinds) {
	TileQuality tq = TileQuality.current();
	List<TileQuality.TileSnapshot> snaps = (tq == null) ? Collections.emptyList() : tq.snapshotAll();

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
	    for(Map.Entry<String, Short> ke : s.kinds.entrySet()) {
		if(filter != null && !filter.equals(ke.getKey())) {continue;}
		entries.add(new Entry(s.gridId, s.tileIdx, ke.getKey(), ke.getValue()));
	    }
	}
	entries.sort((a, b) -> Short.compare(b.q, a.q));
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
	Entry(long gridId, int tileIdx, String kind, short q) {
	    this.gridId = gridId;
	    this.tileIdx = tileIdx;
	    this.kind = kind;
	    this.q = q;
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
	}

	@Override
	public void change(Entry e) {
	    super.change(e);
	    if(e != null) {jumpTo(e);}
	}
    }
}
