package thunder.cookbook;

import haven.*;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Debug tool, console command ":restest" (wired via GameUI.cmdmap). Two modes,
 * toggled by the button in the header:
 *  - "My Ingredients": every distinct ingredient name in the Cookbook dataset
 *    with whatever icon IngredientIconResolver resolved for it (a red box if
 *    unresolved), so a wrong/missing icon jumps out visually instead of
 *    checking the Meal Plan one ingredient at a time.
 *  - "All Invobjs": every item resource under gfx/invobjs on Ring of
 *    Brodgar (BrodgarInvobjsIndex), for browsing/identifying real resource
 *    names by eye. Hover any cell in either mode to see its full resource
 *    path in a tooltip.
 * See docs/cookbook-integration.md.
 */
public class IngredientIconTestWnd extends WindowX {
    private static final int COLS = 6;
    private static final Coord ICON_SZ = UI.scale(28, 28);
    private static final int CELL_W = UI.scale(160);
    private static final int CELL_H = UI.scale(38);

    private final Label status;
    private Button modeBtn;
    private final Coord modeBtnPos;
    private final GridList grid;
    private final Map<String, Tex> icons = new HashMap<>();
    private List<String> cells = Collections.emptyList(); // ingredient names, or invobj resource paths, depending on mode
    private boolean invobjMode = false;
    private boolean loading = false;

    private volatile List<CookbookItem> pendingItems = null;
    private volatile boolean pendingIngredients = false;
    private volatile List<String> pendingInvobjs = null;
    private volatile boolean pendingInvobjsReady = false;

    public IngredientIconTestWnd() {
        super(Coord.z, "Ingredient Icon Test");
        justclose = true;

        status = add(new Label("Loading..."), 0, 0);
        modeBtnPos = status.pos("ur").adds(20, -4);
        setModeBtn();
        grid = add(new GridList(COLS * CELL_W, 16), status.pos("bl").adds(0, 4));

        pack();
        loadIngredients();
    }

    public static void toggle(UI ui) {
        if((ui == null) || (ui.gui == null)) {return;}
        if(ui.gui.ingredientIconTestWnd == null) {
            ui.gui.ingredientIconTestWnd = ui.gui.add(new IngredientIconTestWnd(), UI.scale(new Coord(60, 60)));
        } else {
            ui.gui.ingredientIconTestWnd.destroy();
        }
    }

    @Override
    public void destroy() {
        if((ui != null) && (ui.gui != null) && (ui.gui.ingredientIconTestWnd == this)) {ui.gui.ingredientIconTestWnd = null;}
        super.destroy();
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if((sender == this) && msg.equals("close")) {
            destroy();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }

    private void toggleMode() {
        invobjMode = !invobjMode;
        setModeBtn();
        if(invobjMode) {loadInvobjs();} else {loadIngredients();}
    }

    private void setModeBtn() {
        if(modeBtn != null) {modeBtn.destroy();}
        modeBtn = add(new Button(UI.scale(130), invobjMode ? "Show My Ingredients" : "Show All Invobjs", this::toggleMode), modeBtnPos);
    }

    private void loadIngredients() {
        List<CookbookItem> have = CookbookService.lastGood();
        if(!have.isEmpty()) {
            setCells(ingredientNames(have));
            return;
        }
        if(loading) {return;}
        loading = true;
        status.settext("Fetching food data...");
        CookbookService.refreshAsync((items, error) -> {
            synchronized(ui) {
                if((ui == null) || (parent == null)) {return;} // window closed meanwhile
                pendingItems = items;
                pendingIngredients = true;
            }
        });
    }

    private void loadInvobjs() {
        List<String> have = BrodgarInvobjsIndex.getCached();
        if(have != null) {
            setCells(new ArrayList<>(have));
            return;
        }
        if(loading) {return;}
        loading = true;
        status.settext("Crawling brodgar.io/res/gfx/invobjs (one-time, cached after)...");
        BrodgarInvobjsIndex.loadAsync(() -> {
            synchronized(ui) {
                if((ui == null) || (parent == null)) {return;} // window closed meanwhile
                pendingInvobjs = BrodgarInvobjsIndex.getCached();
                pendingInvobjsReady = true;
            }
        });
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if(pendingIngredients) {
            pendingIngredients = false;
            loading = false;
            if((pendingItems != null) && !invobjMode) {setCells(ingredientNames(pendingItems));}
            pendingItems = null;
        }
        if(pendingInvobjsReady) {
            pendingInvobjsReady = false;
            loading = false;
            if((pendingInvobjs != null) && invobjMode) {setCells(new ArrayList<>(pendingInvobjs));}
            pendingInvobjs = null;
        }
        if(!cells.isEmpty()) {updateStatus();} // icon lookups keep resolving in the background; keep the count live
    }

    private List<String> ingredientNames(List<CookbookItem> items) {
        TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for(CookbookItem it : items) {
            for(CookbookItem.Ingredient ig : it.ingredients) {set.add(ig.name);}
        }
        return new ArrayList<>(set);
    }

    private void setCells(List<String> newCells) {
        Collections.sort(newCells, String.CASE_INSENSITIVE_ORDER);
        cells = newCells;
        List<List<String>> rows = new ArrayList<>();
        for(int i = 0; i < cells.size(); i += COLS) {
            rows.add(cells.subList(i, Math.min(i + COLS, cells.size())));
        }
        grid.setRows(rows);
        updateStatus();
    }

    private void updateStatus() {
        if(invobjMode) {
            status.settext(String.format("%,d invobjs items (Ring of Brodgar). Hover for resource path.", cells.size()));
            return;
        }
        int resolved = 0;
        for(String n : cells) {
            if(IngredientIconResolver.getCached(n) != null) {resolved++;}
        }
        status.settext(String.format("%,d / %,d resolved (unresolved shown with a red box)", resolved, cells.size()));
    }

    /** In invobj mode a cell already IS the resource path; in ingredient mode look it up (and kick off resolution if unknown). */
    private String resPathFor(String cell) {
        if(invobjMode) {return cell;}
        String p = IngredientIconResolver.getCached(cell);
        if(p == null) {IngredientIconResolver.resolveAsync(cell);}
        return p;
    }

    private String displayText(String cell) {
        if(!invobjMode) {return cell;}
        int i = cell.lastIndexOf('/');
        return (i >= 0) ? cell.substring(i + 1) : cell;
    }

    private String tooltipText(String cell) {
        if(invobjMode) {return cell;}
        String p = IngredientIconResolver.getCached(cell);
        return (p != null) ? (cell + " -> " + p) : (cell + " (unresolved)");
    }

    private Tex icon(String resName) {
        Tex tex = icons.get(resName);
        if((tex == null) && !icons.containsKey(resName)) {
            try {
                tex = ItemIconUtil.loadIcon(resName, ICON_SZ);
                icons.put(resName, tex);
            } catch(Loading l) {
                /* retry next frame */
            } catch(Exception e) {
                icons.put(resName, null);
            }
        }
        return tex;
    }

    private class GridList extends Listbox<List<String>> {
        private List<List<String>> rows = Collections.emptyList();

        GridList(int w, int h) {
            super(w, h, CELL_H);
            bgcolor = new Color(0, 0, 0, 90);
        }

        void setRows(List<List<String>> rows) {
            this.rows = rows;
            sb.val = 0;
        }

        @Override
        protected List<String> listitem(int i) {return rows.get(i);}

        @Override
        protected int listitems() {return rows.size();}

        @Override
        protected void drawitem(GOut g, List<String> row, int idx) {
            g.chcolor((idx % 2 == 0) ? new Color(255, 255, 255, 8) : new Color(255, 255, 255, 18));
            g.frect(Coord.z, g.sz());
            g.chcolor();
            for(int col = 0; col < row.size(); col++) {
                String cell = row.get(col);
                int x = col * CELL_W;
                String resName = resPathFor(cell);
                if(resName != null) {
                    Tex t = icon(resName);
                    if(t != null) {
                        g.image(t, new Coord(x + UI.scale(2), (CELL_H - t.sz().y) / 2));
                    }
                } else {
                    g.chcolor(160, 40, 40, 180);
                    g.frect(new Coord(x + UI.scale(2), (CELL_H - ICON_SZ.y) / 2), ICON_SZ);
                    g.chcolor();
                }
                GOut ng = g.reclip(new Coord(x + ICON_SZ.x + UI.scale(6), 0), new Coord(CELL_W - ICON_SZ.x - UI.scale(8), CELL_H));
                ng.atext(displayText(cell), new Coord(0, CELL_H / 2), 0, 0.5);
            }
        }

        @Override
        public Object tooltip(Coord c, Widget prev) {
            int idx = idxat(c);
            if((idx < 0) || (idx >= rows.size())) {return super.tooltip(c, prev);}
            List<String> row = rows.get(idx);
            int col = c.x / CELL_W;
            if((col < 0) || (col >= row.size())) {return super.tooltip(c, prev);}
            return tooltipText(row.get(col));
        }
    }
}
