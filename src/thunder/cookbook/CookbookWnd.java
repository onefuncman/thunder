package thunder.cookbook;

import haven.*;
import org.json.JSONObject;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-game reimplementation of the civ.hearthworld.com/cookbook food browser.
 * Data comes from that site's public /food-info.json (see CookbookService);
 * search syntax mirrors the site's own filter language (see CookbookQuery).
 * See docs/cookbook-integration.md for how this was reverse-engineered.
 */
public class CookbookWnd extends WindowX {
    private static final Coord ICON_SZ = UI.scale(24, 24);
    private static final Coord ATTR_ICON_SZ = UI.scale(20, 20);
    private static final int ROWH = UI.scale(34);
    private static final Color TIER2_COLOR = new Color(120, 255, 120);
    private static final Map<String, Color> ATTR_COLOR = new LinkedHashMap<>();
    /** Alpha for the per-attribute column background tint (site uses opaque pastel on white; we tint over our dark theme instead). */
    private static final int ATTR_BG_ALPHA = 70;
    private static final int ATTR_BG_ALPHA_ACTIVE = 130;

    /** Maps our display attr keys (matching the website's) to the client's own attribute resource codes. */
    private static String attrResCode(String base) {
        switch(base) {
            case "per": return "prc";
            case "cha": return "csm";
            default: return base;
        }
    }

    private static final Map<String, Tex> ATTR_ICONS = new HashMap<>();

    private static Tex attrIcon(String base) {
        Tex tex = ATTR_ICONS.get(base);
        if((tex == null) && !ATTR_ICONS.containsKey(base)) {
            try {
                Resource res = Resource.local().load("gfx/hud/chr/" + attrResCode(base)).get();
                BufferedImage img = res.layer(Resource.imgc).img;
                tex = new TexI(PUtils.convolvedown(img, ATTR_ICON_SZ, CharWnd.iconfilter));
                ATTR_ICONS.put(base, tex);
            } catch(Loading l) {
                /* retry next frame */
            } catch(Exception e) {
                ATTR_ICONS.put(base, null);
            }
        }
        return tex;
    }

    // Fixed column layout, matching the website's own table: Icon, Name,
    // one column per base attribute (background-tinted, not just colored
    // text), Ingredients, FEP/Hunger, Total FEP, Hunger, Energy, Fep bar.
    private static final int ICON_X = UI.scale(2);
    private static final int NAME_X = ICON_X + ICON_SZ.x + UI.scale(6);
    private static final int NAME_W = UI.scale(120);
    private static final int ATTR_X0 = NAME_X + NAME_W + UI.scale(4);
    private static final int ATTR_COL_W = UI.scale(34);
    private static final int ATTR_END_X = ATTR_X0 + ATTR_COL_W * CookbookItem.ATTR_ORDER.length;
    private static final int INGR_X = ATTR_END_X + UI.scale(6);
    private static final int INGR_W = UI.scale(170);
    private static final int FEPHUNGER_X = INGR_X + INGR_W + UI.scale(6);
    private static final int FEPHUNGER_W = UI.scale(65);
    private static final int TOTALFEP_X = FEPHUNGER_X + FEPHUNGER_W + UI.scale(6);
    private static final int TOTALFEP_W = UI.scale(60);
    private static final int HUNGER_X = TOTALFEP_X + TOTALFEP_W + UI.scale(6);
    private static final int HUNGER_W = UI.scale(55);
    private static final int ENERGY_X = HUNGER_X + HUNGER_W + UI.scale(6);
    private static final int ENERGY_W = UI.scale(55);
    private static final int FEPBAR_X = ENERGY_X + ENERGY_W + UI.scale(6);
    private static final int FEPBAR_W = UI.scale(90);
    private static final int PLAN_X = FEPBAR_X + FEPBAR_W + UI.scale(6);
    private static final int PLAN_W = UI.scale(40);
    private static final int CONTENT_W = PLAN_X + PLAN_W + UI.scale(6);
    private static final Coord LIST_SZ = new Coord(CONTENT_W, UI.scale(460));
    private static final Color PLAN_COL_BG = new Color(120, 180, 220, 40);

    static {
        ATTR_COLOR.put("str", new Color(224, 140, 150));
        ATTR_COLOR.put("agi", new Color(150, 150, 224));
        ATTR_COLOR.put("int", new Color(140, 200, 190));
        ATTR_COLOR.put("con", new Color(190, 150, 190));
        ATTR_COLOR.put("per", new Color(224, 180, 130));
        ATTR_COLOR.put("cha", new Color(150, 200, 150));
        ATTR_COLOR.put("dex", new Color(220, 220, 140));
        ATTR_COLOR.put("wil", new Color(190, 210, 120));
        ATTR_COLOR.put("psy", new Color(190, 150, 220));
    }

    private static Color attrColor(String key) {
        String base = key.endsWith("2") ? key.substring(0, key.length() - 1) : key;
        Color c = ATTR_COLOR.get(base);
        return (c != null) ? c : Color.LIGHT_GRAY;
    }

    private static Color attrBgColor(String base) {
        Color c = attrColor(base);
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), ATTR_BG_ALPHA);
    }

    /** FEP value for one base attribute at a given tier (1 or 2), or 0 if the food doesn't have it. */
    private static double attrTierValue(CookbookItem item, String base, int tier) {
        Double v = item.feps.get((tier == 2) ? (base + "2") : base);
        return (v != null) ? v : 0;
    }

    private final TextEntry search;
    private final Label status;
    private final Label authStatus;
    private final Coord authBtnPos;
    private Button authBtn;
    private final ItemList list;
    private List<CookbookItem> all = Collections.emptyList();
    private boolean loading = false;
    /** Attribute column currently sorted on (null = unsorted), and whether by +1 or +2 tier. */
    private String sortAttr = null;
    private int sortTier = 1;

    private enum SortMetric {FEPHUNGER, TOTALFEP, HUNGER, ENERGY}

    private static double metricValue(CookbookItem item, SortMetric m) {
        switch(m) {
            case FEPHUNGER: return item.fepPerHunger;
            case TOTALFEP: return item.totalFep;
            case HUNGER: return item.hunger;
            case ENERGY: return item.energy;
            default: return 0;
        }
    }

    private static String metricLabel(SortMetric m) {
        switch(m) {
            case FEPHUNGER: return "FEP/Hunger";
            case TOTALFEP: return "Total FEP";
            case HUNGER: return "Hunger";
            case ENERGY: return "Energy";
            default: return "";
        }
    }

    /** {x, width} of a metric column, matching the FEPHUNGER_X.../ENERGY_X... constants below. */
    private static int[] metricCol(SortMetric m) {
        switch(m) {
            case FEPHUNGER: return new int[]{FEPHUNGER_X, FEPHUNGER_W};
            case TOTALFEP: return new int[]{TOTALFEP_X, TOTALFEP_W};
            case HUNGER: return new int[]{HUNGER_X, HUNGER_W};
            case ENERGY: return new int[]{ENERGY_X, ENERGY_W};
            default: return new int[]{0, 0};
        }
    }

    /** FEP/Hunger, Total FEP, Hunger or Energy column currently sorted on, mutually exclusive with sortAttr. */
    private SortMetric sortMetric = null;
    private boolean sortMetricAsc = false;

    // Meal plan: dishes the user has picked, and how many of each. Insertion
    // order preserved so the plan window lists them in the order they were
    // added. planVersion lets CookbookPlanWnd notice changes without needing
    // an explicit callback wired up (same tick()-polling idiom as elsewhere).
    // This working plan is NOT auto-persisted -- see saveMeal/loadMeal below
    // for the explicit, user-named save/load feature.
    private final Map<CookbookItem, Integer> plan = new LinkedHashMap<>();
    private int planVersion = 0;
    private final Coord planBtnPos;
    private Button planBtn;

    public Map<CookbookItem, Integer> plan() {return plan;}

    public int planVersion() {return planVersion;}

    public void addToPlan(CookbookItem item) {
        plan.merge(item, 1, Integer::sum);
        planVersion++;
        refreshPlanBtn();
    }

    public void setPlanQty(CookbookItem item, int qty) {
        if(qty <= 0) {plan.remove(item);} else {plan.put(item, qty);}
        planVersion++;
        refreshPlanBtn();
    }

    // Named, explicitly-saved meal plans -- multiple slots, saved/loaded on
    // request via buttons in CookbookPlanWnd, distinct from the live working
    // plan above. Stored as {mealName: {itemKey: qty}} in one file.
    private static final String MEALS_FILE = "cookbook-meals.json";

    private JSONObject loadMealsFile() {
        try {
            String data = Config.loadFile(MEALS_FILE);
            if((data != null) && !data.isEmpty()) {return new JSONObject(data);}
        } catch(Exception ignored) {}
        return new JSONObject();
    }

    /** Names of all saved meals, in save order. */
    public List<String> savedMealNames() {
        return new ArrayList<>(loadMealsFile().keySet());
    }

    /** Saves the current working plan under the given name (overwrites if it already exists). */
    public void saveMeal(String name) {
        JSONObject meals = loadMealsFile();
        JSONObject entry = new JSONObject();
        for(Map.Entry<CookbookItem, Integer> e : plan.entrySet()) {
            entry.put(e.getKey().key(), e.getValue());
        }
        meals.put(name, entry);
        Config.saveFile(MEALS_FILE, meals.toString());
        refreshPlanBtn();
    }

    public void deleteMeal(String name) {
        JSONObject meals = loadMealsFile();
        meals.remove(name);
        Config.saveFile(MEALS_FILE, meals.toString());
        refreshPlanBtn();
    }

    /** Replaces the current working plan with the saved meal of the given name. */
    public void loadMeal(String name) {
        JSONObject meals = loadMealsFile();
        JSONObject entry = meals.optJSONObject(name);
        if(entry == null) {return;}
        Map<String, CookbookItem> byKey = new HashMap<>();
        for(CookbookItem it : all) {byKey.putIfAbsent(it.key(), it);}
        plan.clear();
        for(String k : entry.keySet()) {
            CookbookItem item = byKey.get(k);
            if(item != null) {plan.put(item, entry.getInt(k));}
        }
        planVersion++;
        refreshPlanBtn();
    }

    public CookbookWnd() {
        super(Coord.z, "Cookbook - civ.hearthworld.com");
        justclose = true;

        Widget prev = add(new Label("Filter foods by name, ingredients or FEP:"), 0, 0);
        search = add(new TextEntry(UI.scale(420), ""), prev.pos("bl").adds(0, 2));
        search.canactivate = true;
        Button refresh = add(new Button(UI.scale(70), "Refresh", this::refresh), search.pos("ur").adds(10, 0));
        Label help = add(new Label("[?]"), refresh.pos("ur").adds(10, 4));
        help.settip("; separates conditions (all must match).\n" +
            "name:text / from:text - name / ingredient contains text (\"exact\" for exact match)\n" +
            "-name:text / -from:text - exclude instead\n" +
            "str/agi/int/con/per/cha/dex/wil/psy[2] ><= N or N% - filter by raw or %% FEP\n" +
            "Example: name:steak;per2>50%;-from:\"deer\"", UI.scale(320));
        planBtnPos = help.pos("ur").adds(20, -4);
        Button missingBtn = add(new Button(UI.scale(110), "Missing Recipes", () -> thunder.cookbook.MissingRecipesWnd.toggle(ui)), planBtnPos.add(UI.scale(100), 0));

        authStatus = add(new Label(""), search.pos("bl").adds(0, 8));
        authBtnPos = search.pos("bl").adds(0, 8).add(UI.scale(210), -UI.scale(4));

        status = add(new Label(""), authStatus.pos("bl").adds(0, 6));

        Widget header = add(new ColumnHeader(), status.pos("bl").adds(0, 6));
        list = add(new ItemList(LIST_SZ.x, LIST_SZ.y), header.pos("bl").adds(0, 2));

        refreshAuthStatus();
        refreshPlanBtn();
        pack();
        setfocus(search);
        refresh();
    }

    public static void toggle(UI ui) {
        if((ui == null) || (ui.gui == null)) {return;}
        if(ui.gui.cookbookwnd == null) {
            ui.gui.cookbookwnd = ui.gui.add(new CookbookWnd(), UI.scale(new Coord(80, 80)));
        } else {
            ui.gui.cookbookwnd.destroy();
        }
    }

    /** Ensures the window is open (does not toggle it closed) and kicks off a refresh. */
    public static void toggle(UI ui, boolean ensureOpenAndRefresh) {
        if((ui == null) || (ui.gui == null)) {return;}
        if(ui.gui.cookbookwnd == null) {
            ui.gui.cookbookwnd = ui.gui.add(new CookbookWnd(), UI.scale(new Coord(80, 80)));
        } else if(ensureOpenAndRefresh) {
            ui.gui.cookbookwnd.refresh();
            ui.gui.cookbookwnd.refreshAuthStatus();
        }
        ui.gui.cookbookwnd.raise();
    }

    @Override
    public void destroy() {
        if((ui != null) && (ui.gui != null) && (ui.gui.cookbookwnd == this)) {ui.gui.cookbookwnd = null;}
        super.destroy();
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if((sender == this) && msg.equals("close")) {
            destroy();
        } else if((sender == search) && msg.equals("activate")) {
            applyFilter();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }

    // Set by the background refresh callback, consumed on the next tick() --
    // AWT/Java2D text and texture rendering (Label.settext, RichText.render,
    // new Button(...), etc.) is not safe to call off the main thread, so
    // nothing that touches widgets happens inside the async callback itself.
    private volatile List<CookbookItem> pendingItems = null;
    private volatile String pendingError = null;
    private volatile boolean pendingResult = false;

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if(pendingResult) {
            pendingResult = false;
            loading = false;
            if(pendingError != null) {
                status.settext("Could not load food data: " + pendingError);
            } else if(pendingItems != null) {
                all = pendingItems;
                lastFilterText = null;
            }
            pendingItems = null;
            pendingError = null;
        }
        applyFilter();
    }

    private String lastFilterText = "";

    private void applyFilter() {
        String text = search.text();
        if(text.equals(lastFilterText)) {return;}
        lastFilterText = text;
        rebuildList();
    }

    private void rebuildList() {
        CookbookQuery q = CookbookQuery.parse(lastFilterText);
        List<CookbookItem> filtered = new ArrayList<>();
        for(CookbookItem it : all) {
            if(q.matches(it)) {filtered.add(it);}
        }
        if(sortAttr != null) {
            String attr = sortAttr;
            int tier = sortTier;
            filtered.sort((a, b) -> Double.compare(attrTierValue(b, attr, tier), attrTierValue(a, attr, tier)));
        } else if(sortMetric != null) {
            SortMetric m = sortMetric;
            int dir = sortMetricAsc ? 1 : -1;
            filtered.sort((a, b) -> dir * Double.compare(metricValue(a, m), metricValue(b, m)));
        }
        list.setItems(filtered);
        status.settext(String.format("%,d / %,d foods", filtered.size(), all.size()));
    }

    /** Header click: sort by that attribute's +1 FEP descending; click the same attribute again for +2. */
    private void onAttrHeaderClick(String base) {
        if(base.equals(sortAttr)) {
            sortTier = (sortTier == 1) ? 2 : 1;
        } else {
            sortAttr = base;
            sortTier = 1;
        }
        sortMetric = null;
        rebuildList();
    }

    /** Header click: sort by that column, highest first; click the same column again to reverse. */
    private void onMetricHeaderClick(SortMetric m) {
        if(m == sortMetric) {
            sortMetricAsc = !sortMetricAsc;
        } else {
            sortMetric = m;
            sortMetricAsc = false;
        }
        sortAttr = null;
        rebuildList();
    }

    private void refresh() {
        if(loading) {return;}
        loading = true;
        status.settext("Loading food data from civ.hearthworld.com...");
        CookbookService.refreshAsync((items, error) -> {
            // Background thread: only touch plain fields here, never widgets --
            // tick() applies the result on the main thread. See the comment
            // on the pending* fields above.
            synchronized(ui) {
                if((ui == null) || (parent == null)) {return;} // window closed meanwhile
                pendingItems = items;
                pendingError = error;
                pendingResult = true;
            }
        });
    }

    private void refreshAuthStatus() {
        if(CookbookAuth.isLoggedIn()) {
            authStatus.settext("Logged in as " + CookbookAuth.username());
            setAuthBtn("Log out");
        } else {
            authStatus.settext("Not logged in");
            setAuthBtn("Log in");
        }
    }

    private void setAuthBtn(String label) {
        if(authBtn != null) {authBtn.destroy();}
        authBtn = add(new Button(UI.scale(80), label, this::onAuthClick), authBtnPos);
    }

    private void onAuthClick() {
        if(CookbookAuth.isLoggedIn()) {
            CookbookAuth.logout();
            refreshAuthStatus();
        } else if((ui != null) && (ui.gui != null)) {
            if(ui.gui.cookbookLoginWnd == null) {
                ui.gui.cookbookLoginWnd = ui.gui.add(new CookbookLoginWnd(), UI.scale(new Coord(150, 150)));
            }
            ui.gui.cookbookLoginWnd.raise();
        }
    }

    private void refreshPlanBtn() {
        if(planBtn != null) {planBtn.destroy();}
        planBtn = add(new Button(UI.scale(90), "Meal Plan (" + savedMealNames().size() + ")", this::onPlanClick), planBtnPos);
    }

    private void onPlanClick() {
        if((ui == null) || (ui.gui == null)) {return;}
        if(ui.gui.cookbookPlanWnd == null) {
            ui.gui.cookbookPlanWnd = ui.gui.add(new CookbookPlanWnd(this), UI.scale(new Coord(200, 120)));
        }
        ui.gui.cookbookPlanWnd.raise();
    }

    /**
     * Column header row above the list -- same column backgrounds/positions as each row.
     * Attribute columns show the client's own attribute icon (like the website's icon-header
     * variant) and are clickable: click sorts by that attribute's +1 FEP descending, click the
     * same one again to sort by +2 instead.
     */
    private class ColumnHeader extends Widget {
        ColumnHeader() {
            super(new Coord(CONTENT_W, UI.scale(20)));
            setcanfocus(false);
        }

        @Override
        public void draw(GOut g) {
            for(int i = 0; i < CookbookItem.ATTR_ORDER.length; i++) {
                String base = CookbookItem.ATTR_ORDER[i];
                boolean active = base.equals(sortAttr);
                Color c = attrColor(base);
                g.chcolor(new Color(c.getRed(), c.getGreen(), c.getBlue(), active ? ATTR_BG_ALPHA_ACTIVE : ATTR_BG_ALPHA));
                g.frect(new Coord(ATTR_X0 + i * ATTR_COL_W, 0), new Coord(ATTR_COL_W, sz.y));
            }
            if(sortMetric != null) {
                int[] col = metricCol(sortMetric);
                g.chcolor(255, 255, 255, ATTR_BG_ALPHA_ACTIVE);
                g.frect(new Coord(col[0], 0), new Coord(col[1], sz.y));
            }
            g.chcolor(PLAN_COL_BG);
            g.frect(new Coord(PLAN_X, 0), new Coord(PLAN_W, sz.y));
            g.chcolor();

            g.atext("Name", new Coord(NAME_X, sz.y / 2), 0, 0.5);
            for(int i = 0; i < CookbookItem.ATTR_ORDER.length; i++) {
                String base = CookbookItem.ATTR_ORDER[i];
                int cx = ATTR_X0 + i * ATTR_COL_W + ATTR_COL_W / 2;
                Tex icon = attrIcon(base);
                if(icon != null) {
                    g.image(icon, new Coord(cx - icon.sz().x / 2, (sz.y - icon.sz().y) / 2));
                } else {
                    g.atext(base, new Coord(cx, sz.y / 2), 0.5, 0.5);
                }
                if(base.equals(sortAttr)) {
                    g.chcolor(sortTier == 2 ? TIER2_COLOR : Color.WHITE);
                    g.atext(sortTier == 2 ? "2" : "1", new Coord(ATTR_X0 + i * ATTR_COL_W + ATTR_COL_W - UI.scale(3), UI.scale(3)), 1, 0);
                    g.chcolor();
                }
            }
            g.atext("Ingredients", new Coord(INGR_X, sz.y / 2), 0, 0.5);
            for(SortMetric m : SortMetric.values()) {
                int[] col = metricCol(m);
                String label = metricLabel(m);
                if(m == sortMetric) {label += sortMetricAsc ? " ^" : " v";}
                g.atext(label, new Coord(col[0] + col[1] / 2, sz.y / 2), 0.5, 0.5);
            }
            g.atext("Fep bar", new Coord(FEPBAR_X + FEPBAR_W / 2, sz.y / 2), 0.5, 0.5);
            g.atext("Plan", new Coord(PLAN_X + PLAN_W / 2, sz.y / 2), 0.5, 0.5);
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            if(ev.b == 1) {
                for(int i = 0; i < CookbookItem.ATTR_ORDER.length; i++) {
                    int cx0 = ATTR_X0 + i * ATTR_COL_W;
                    if((ev.c.x >= cx0) && (ev.c.x < cx0 + ATTR_COL_W)) {
                        onAttrHeaderClick(CookbookItem.ATTR_ORDER[i]);
                        return true;
                    }
                }
                for(SortMetric m : SortMetric.values()) {
                    int[] col = metricCol(m);
                    if((ev.c.x >= col[0]) && (ev.c.x < col[0] + col[1])) {
                        onMetricHeaderClick(m);
                        return true;
                    }
                }
            }
            return super.mousedown(ev);
        }

        @Override
        public Object tooltip(Coord c, Widget prev) {
            for(int i = 0; i < CookbookItem.ATTR_ORDER.length; i++) {
                int cx0 = ATTR_X0 + i * ATTR_COL_W;
                if((c.x >= cx0) && (c.x < cx0 + ATTR_COL_W)) {
                    return "Click to sort by highest +1, click again for +2";
                }
            }
            for(SortMetric m : SortMetric.values()) {
                int[] col = metricCol(m);
                if((c.x >= col[0]) && (c.x < col[0] + col[1])) {
                    return "Click to sort, click again to reverse";
                }
            }
            return super.tooltip(c, prev);
        }
    }

    /**
     * Row-per-food list with variable row height: a row grows tall enough to
     * word-wrap its ingredients column instead of clipping it (long lists get
     * a second/third line, like the site does). Listbox/ListWidget assume a
     * single fixed itemh for every row, so this is a small bespoke scrolling
     * container rather than a Listbox subclass.
     */
    private class ItemList extends Widget {
        private final Scrollbar sb;
        private List<Row> rows = Collections.emptyList();
        private int totalH = 0;
        private final Map<String, Tex> icons = new LinkedHashMap<>();
        private final Map<CookbookItem, Tex> ingrTex = new HashMap<>();
        private final Map<CookbookItem, Integer> rowH = new HashMap<>();

        private class Row {
            final CookbookItem item;
            final int y, h;
            Row(CookbookItem item, int y, int h) {this.item = item; this.y = y; this.h = h;}
        }

        ItemList(int w, int h) {
            super(new Coord(w, h));
            sb = adda(new Scrollbar(h, 0, 0), w, 0, 1, 0);
        }

        void setItems(List<CookbookItem> items) {
            List<Row> newRows = new ArrayList<>(items.size());
            int y = 0;
            for(CookbookItem it : items) {
                int h = rowHeight(it);
                newRows.add(new Row(it, y, h));
                y += h;
            }
            rows = newRows;
            totalH = y;
            sb.val = 0;
            sb.min(0);
            sb.max(Math.max(0, totalH - sz.y));
        }

        @Override
        public boolean mousewheel(MouseWheelEvent ev) {
            sb.ch(ev.a * ROWH);
            return true;
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            if((ev.b == 1) && (ev.c.x >= PLAN_X) && (ev.c.x < PLAN_X + PLAN_W)) {
                Row r = rowAt(ev.c.y);
                if(r != null) {
                    addToPlan(r.item);
                    return true;
                }
            }
            return super.mousedown(ev);
        }

        private Tex ingrTex(CookbookItem item) {
            return ingrTex.computeIfAbsent(item, it -> {
                String t = it.ingredientSummary();
                return RichText.render(t.isEmpty() ? "(no ingredients)" : t, INGR_W - UI.scale(8)).tex();
            });
        }

        private int rowHeight(CookbookItem item) {
            return rowH.computeIfAbsent(item, it -> Math.max(ROWH, ingrTex(it).sz().y + UI.scale(8)));
        }

        private Tex icon(CookbookItem item) {
            Tex tex = icons.get(item.resourceName);
            if((tex == null) && !icons.containsKey(item.resourceName)) {
                try {
                    tex = ItemIconUtil.loadIcon(item.resourceName, ICON_SZ);
                    icons.put(item.resourceName, tex);
                } catch(Loading l) {
                    /* retry next frame */
                } catch(Exception e) {
                    icons.put(item.resourceName, null); // give up, avoid retrying every frame
                }
            }
            return tex;
        }

        private Row rowAt(int localY) {
            int y = localY + sb.val;
            for(Row r : rows) {
                if((y >= r.y) && (y < r.y + r.h)) {return r;}
            }
            return null;
        }

        @Override
        public void draw(GOut g) {
            g.chcolor(0, 0, 0, 110);
            g.frect(Coord.z, sz);
            g.chcolor();

            int w = sz.x - (sb.vis() ? sb.sz.x : 0);
            int idx = 0;
            for(Row r : rows) {
                int ry = r.y - sb.val;
                if(ry + r.h <= 0) {idx++; continue;}
                if(ry >= sz.y) {break;}
                GOut rg = g.reclip(new Coord(0, ry), new Coord(w, r.h));
                drawitem(rg, r, idx);
                idx++;
            }
            super.draw(g);
        }

        private void drawitem(GOut g, Row row, int idx) {
            CookbookItem item = row.item;
            int h = row.h;

            g.chcolor((idx % 2 == 0) ? new Color(255, 255, 255, 8) : new Color(255, 255, 255, 18));
            g.frect(Coord.z, g.sz());
            g.chcolor();

            // One fixed-position, background-tinted column per base attribute -- like the website's.
            for(int i = 0; i < CookbookItem.ATTR_ORDER.length; i++) {
                String base = CookbookItem.ATTR_ORDER[i];
                boolean active = base.equals(sortAttr);
                g.chcolor(active ? new Color(attrColor(base).getRed(), attrColor(base).getGreen(), attrColor(base).getBlue(), ATTR_BG_ALPHA_ACTIVE) : attrBgColor(base));
                g.frect(new Coord(ATTR_X0 + i * ATTR_COL_W, 0), new Coord(ATTR_COL_W, h));
            }
            g.chcolor();

            Tex ic = icon(item);
            if(ic != null) {g.image(ic, new Coord(ICON_X, (h - ICON_SZ.y) / 2));}

            GOut ng = g.reclip(new Coord(NAME_X, 0), new Coord(NAME_W - UI.scale(4), h));
            ng.atext(item.name, new Coord(0, h / 2), 0, 0.5);

            // Tier +1 on top (default color), tier +2 below in green -- matching the site's convention.
            for(int i = 0; i < CookbookItem.ATTR_ORDER.length; i++) {
                String base = CookbookItem.ATTR_ORDER[i];
                double v1 = attrTierValue(item, base, 1);
                double v2 = attrTierValue(item, base, 2);
                if((v1 == 0) && (v2 == 0)) {continue;}
                int cx = ATTR_X0 + i * ATTR_COL_W + ATTR_COL_W / 2;
                if((v1 != 0) && (v2 != 0)) {
                    g.atext(String.format("%.1f", v1), new Coord(cx, h / 2 - UI.scale(7)), 0.5, 0.5);
                    g.chcolor(TIER2_COLOR);
                    g.atext(String.format("%.1f", v2), new Coord(cx, h / 2 + UI.scale(7)), 0.5, 0.5);
                    g.chcolor();
                } else if(v1 != 0) {
                    g.atext(String.format("%.1f", v1), new Coord(cx, h / 2), 0.5, 0.5);
                } else {
                    g.chcolor(TIER2_COLOR);
                    g.atext(String.format("%.1f", v2), new Coord(cx, h / 2), 0.5, 0.5);
                    g.chcolor();
                }
            }

            Tex ingr = ingrTex(item);
            g.image(ingr, new Coord(INGR_X, (h - ingr.sz().y) / 2));

            g.atext(String.format("%.2f", item.fepPerHunger), new Coord(FEPHUNGER_X + FEPHUNGER_W / 2, h / 2), 0.5, 0.5);
            g.atext(String.format("%.2f", item.totalFep), new Coord(TOTALFEP_X + TOTALFEP_W / 2, h / 2), 0.5, 0.5);
            g.atext(String.format("%.2f%%", item.hunger), new Coord(HUNGER_X + HUNGER_W / 2, h / 2), 0.5, 0.5);
            g.atext(String.format("%d%%", item.energy), new Coord(ENERGY_X + ENERGY_W / 2, h / 2), 0.5, 0.5);

            // Stacked FEP-share bar, its own column, like the website's "Fep bar".
            int barH = UI.scale(10);
            int barY = (h - barH) / 2;
            int x = FEPBAR_X;
            for(Map.Entry<String, Double> e : item.fepPercent.entrySet()) {
                int bw = (int) Math.round(e.getValue() / 100.0 * FEPBAR_W);
                if(bw <= 0) {continue;}
                g.chcolor(attrColor(e.getKey()));
                g.frect(new Coord(x, barY), new Coord(bw, barH));
                x += bw;
            }
            g.chcolor();

            // Plan column: "+" to add, or the current planned quantity once added.
            Integer qty = plan.get(item);
            g.chcolor(PLAN_COL_BG);
            g.frect(new Coord(PLAN_X, 0), new Coord(PLAN_W, h));
            g.chcolor();
            if(qty != null) {
                g.chcolor(TIER2_COLOR);
                g.atext("x" + qty, new Coord(PLAN_X + PLAN_W / 2, h / 2), 0.5, 0.5);
                g.chcolor();
            } else {
                g.atext("+", new Coord(PLAN_X + PLAN_W / 2, h / 2), 0.5, 0.5);
            }
        }

        @Override
        public Object tooltip(Coord c, Widget prev) {
            if((c.x >= PLAN_X) && (c.x < PLAN_X + PLAN_W)) {
                return "Click to add to your meal plan (click again to add more)";
            }
            Row r = rowAt(c.y);
            if(r == null) {return super.tooltip(c, prev);}
            CookbookItem item = r.item;
            StringBuilder sb = new StringBuilder();
            sb.append(item.name).append('\n');
            sb.append("Ingredients: ").append(item.ingredientSummary()).append('\n');
            for(String base : CookbookItem.ATTR_ORDER) {
                appendAttr(sb, item, base);
                appendAttr(sb, item, base + "2");
            }
            sb.append(String.format("Total FEP: %.2f   FEP/Hunger: %.2f\n", item.totalFep, item.fepPerHunger));
            sb.append(String.format("Hunger: %.2f%%   Energy: %d%%", item.hunger, item.energy));
            return RichText.render(sb.toString(), UI.scale(320)).tex();
        }

        private void appendAttr(StringBuilder sb, CookbookItem item, String key) {
            Double v = item.feps.get(key);
            if(v == null) {return;}
            if(key.endsWith("2")) {
                sb.append(String.format("%s: $col[%d,%d,%d]{%.2f}\n", key,
                    TIER2_COLOR.getRed(), TIER2_COLOR.getGreen(), TIER2_COLOR.getBlue(), v));
            } else {
                sb.append(key).append(": ").append(String.format("%.2f", v)).append('\n');
            }
        }
    }
}
