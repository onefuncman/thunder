package thunder.cookbook;

import haven.*;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * "Meal plan" companion to CookbookWnd: lists the dishes picked from the
 * browse list (via its Plan column) with an editable quantity per dish, and
 * below that a rolled-up bill of materials -- the sum of ingredient%*qty
 * across every planned dish, per raine's spec (see the Discord thread this
 * was scoped from, and docs/cookbook-integration.md).
 */
public class CookbookPlanWnd extends WindowX {
    private static final Coord ICON_SZ = UI.scale(20, 20);
    private static final int ROWH = UI.scale(26);
    private static final int NAME_X = ICON_SZ.x + UI.scale(6);
    private static final int NAME_W = UI.scale(210);
    private static final int QTY_X = NAME_X + NAME_W + UI.scale(10);
    private static final int QTY_W = UI.scale(40);
    private static final int RM_X = QTY_X + QTY_W + UI.scale(8);
    private static final int RM_W = UI.scale(60);
    private static final int ROW_W = RM_X + RM_W;
    private static final Coord MAT_SZ = new Coord(ROW_W, UI.scale(260));

    private final CookbookWnd owner;
    private final TextEntry mealNameField;
    private final Label saveMsg;
    private final Label empty;
    private final Label materialsHeader;
    private final Label materialsNote;
    private final MaterialsList materials;
    private int shownVersion = -1;
    private final List<Widget> rowWidgets = new ArrayList<>();
    private final Map<CookbookItem, TextEntry> qtyFields = new HashMap<>();
    private final Map<String, Tex> icons = new HashMap<>();
    private CookbookLoadMealWnd loadMealWnd;

    private Tex icon(String resourceName) {
        Tex tex = icons.get(resourceName);
        if((tex == null) && !icons.containsKey(resourceName)) {
            try {
                tex = ItemIconUtil.loadIcon(resourceName, ICON_SZ);
                icons.put(resourceName, tex);
            } catch(Loading l) {
                /* retry next frame */
            } catch(Exception e) {
                icons.put(resourceName, null);
            }
        }
        return tex;
    }

    /** Draws a fixed resource's icon, re-fetching each frame so a not-yet-loaded icon appears once it resolves. */
    private class RowIcon extends Widget {
        final String resourceName;
        RowIcon(String resourceName) {
            super(ICON_SZ);
            this.resourceName = resourceName;
        }

        @Override
        public void draw(GOut g) {
            Tex t = icon(resourceName);
            if(t != null) {g.image(t, Coord.z);}
        }
    }

    /** One line of the bill of materials: an ingredient name/amount, with a resource (for its icon) when known. */
    private static class MaterialRow {
        final String name;
        final double amount;
        final String resName; // null if this came from the FEP-percentage estimate, not a captured real recipe

        MaterialRow(String name, double amount, String resName) {
            this.name = name;
            this.amount = amount;
            this.resName = resName;
        }
    }

    public CookbookPlanWnd(CookbookWnd owner) {
        super(Coord.z, "Meal Plan");
        this.owner = owner;
        justclose = true;

        Widget nameLbl = add(new Label("Meal name:"), 0, UI.scale(4));
        mealNameField = add(new TextEntry(UI.scale(140), ""), nameLbl.pos("ur").adds(6, -4));
        mealNameField.canactivate = true;
        Button saveBtn = add(new Button(UI.scale(80), "Save Meal", this::onSaveMealClick), mealNameField.pos("ur").adds(10, 0));
        Button loadBtn = add(new Button(UI.scale(80), "Load Meal", this::onLoadMealClick), saveBtn.pos("ur").adds(6, 0));
        saveMsg = add(new Label(""), nameLbl.pos("bl").adds(0, 4));

        empty = add(new Label("Click the + column in the Cookbook list to add dishes here."), 0, 0);
        materialsHeader = add(new Label("Bill of Materials"), 0, 0);
        materialsNote = add(new Label(""), 0, 0);
        materials = add(new MaterialsList(MAT_SZ.x, MAT_SZ.y / ROWH), 0, 0);

        rebuild();
        pack();
    }

    private void onSaveMealClick() {
        String name = mealNameField.text().trim();
        if(name.isEmpty()) {
            saveMsg.settext("Enter a meal name first.");
            return;
        }
        owner.saveMeal(name);
        saveMsg.settext("Saved \"" + name + "\".");
    }

    private void onLoadMealClick() {
        if((ui == null) || (ui.gui == null)) {return;}
        if(loadMealWnd == null) {
            loadMealWnd = ui.gui.add(new CookbookLoadMealWnd(owner, this), UI.scale(new Coord(260, 200)));
        }
        loadMealWnd.raise();
    }

    void onMealWndClosed() {
        loadMealWnd = null;
    }

    public static void toggle(UI ui) {
        if((ui == null) || (ui.gui == null) || (ui.gui.cookbookwnd == null)) {return;}
        if(ui.gui.cookbookPlanWnd == null) {
            ui.gui.cookbookPlanWnd = ui.gui.add(new CookbookPlanWnd(ui.gui.cookbookwnd), UI.scale(new Coord(200, 120)));
        } else {
            ui.gui.cookbookPlanWnd.destroy();
        }
    }

    @Override
    public void destroy() {
        if((ui != null) && (ui.gui != null) && (ui.gui.cookbookPlanWnd == this)) {ui.gui.cookbookPlanWnd = null;}
        super.destroy();
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if((sender == this) && msg.equals("close")) {
            destroy();
        } else if((sender == mealNameField) && msg.equals("activate")) {
            onSaveMealClick();
        } else if((sender instanceof TextEntry) && msg.equals("activate")) {
            for(Map.Entry<CookbookItem, TextEntry> e : qtyFields.entrySet()) {
                if(e.getValue() == sender) {
                    commitQty(e.getKey(), (String) args[0]);
                    break;
                }
            }
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }

    private void commitQty(CookbookItem item, String text) {
        try {
            owner.setPlanQty(item, Integer.parseInt(text.trim()));
        } catch(NumberFormatException ex) {
            // not a whole number -- leave the plan alone; the field keeps the typed text
        }
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if(owner.planVersion() != shownVersion) {
            rebuild();
        }
    }

    private void rebuild() {
        shownVersion = owner.planVersion();
        saveMsg.settext("");
        for(Widget w : rowWidgets) {w.destroy();}
        rowWidgets.clear();
        qtyFields.clear();

        Map<CookbookItem, Integer> plan = owner.plan();
        int y = saveMsg.pos("bl").y + UI.scale(6);
        if(plan.isEmpty()) {
            empty.show();
            empty.c = new Coord(0, y);
            y += empty.sz.y + UI.scale(6);
        } else {
            empty.hide();
            for(Map.Entry<CookbookItem, Integer> e : plan.entrySet()) {
                CookbookItem item = e.getKey();
                int qty = e.getValue();

                Widget rowIcon = add(new RowIcon(item.resourceName), 0, y + (ROWH - ICON_SZ.y) / 2);
                rowWidgets.add(rowIcon);

                Widget nameLbl = add(new Label(item.name), NAME_X, y + UI.scale(4));
                rowWidgets.add(nameLbl);

                TextEntry qtyField = add(new TextEntry(QTY_W, String.valueOf(qty)), QTY_X, y);
                qtyField.canactivate = true;
                rowWidgets.add(qtyField);
                qtyFields.put(item, qtyField);

                Button rm = add(new Button(RM_W, "Remove", () -> owner.setPlanQty(item, 0)), RM_X, y);
                rowWidgets.add(rm);

                y += ROWH;
            }
            y += UI.scale(6);
        }

        materialsHeader.c = new Coord(0, y);
        y += materialsHeader.sz.y + UI.scale(2);
        materialsNote.c = new Coord(0, y);
        y += materialsNote.sz.y + UI.scale(4);
        materials.c = new Coord(0, y);

        recomputeMaterials();
        pack();
    }

    /**
     * Prefers real captured crafting-recipe data (RecipeCapture) for a dish
     * when we have it -- exact ingredients and quantities, with real icons.
     * Falls back to the FEP-percentage estimate (no icon; that data has no
     * resource reference) for any dish nobody's crafted with this client yet.
     */
    private void recomputeMaterials() {
        Map<String, Double> totals = new TreeMap<>();
        Map<String, String> resByName = new HashMap<>();
        boolean anyCaptured = false, anyEstimated = false;
        for(Map.Entry<CookbookItem, Integer> e : owner.plan().entrySet()) {
            CookbookItem item = e.getKey();
            int qty = e.getValue();
            List<RecipeCapture.Ingredient> captured = RecipeCapture.recipeFor(item.resourceName);
            if(captured != null) {
                anyCaptured = true;
                for(RecipeCapture.Ingredient ig : captured) {
                    totals.merge(ig.displayName, (double) ig.qty * qty, Double::sum);
                    resByName.putIfAbsent(ig.displayName, ig.resName);
                }
            } else {
                anyEstimated = true;
                for(CookbookItem.Ingredient ig : item.ingredients) {
                    totals.merge(ig.name, (ig.percentage / 100.0) * qty, Double::sum);
                }
            }
        }
        List<MaterialRow> rows = new ArrayList<>();
        for(Map.Entry<String, Double> e : totals.entrySet()) {
            rows.add(new MaterialRow(e.getKey(), e.getValue(), resByName.get(e.getKey())));
        }
        rows.sort((a, b) -> Double.compare(b.amount, a.amount));
        materials.setItems(rows);

        if(totals.isEmpty()) {
            materialsNote.settext("(nothing planned yet)");
        } else if(anyCaptured && !anyEstimated) {
            materialsNote.settext("Real recipe amounts, from crafting windows opened in this client.");
        } else if(anyCaptured) {
            materialsNote.settext("Mix of real recipe amounts and %-based estimates, for dishes not yet crafted here.");
        } else {
            materialsNote.settext("Estimates (ingredient % x quantity) -- craft these dishes once in this client for real amounts.");
        }
    }

    private class MaterialsList extends Listbox<MaterialRow> {
        private List<MaterialRow> items = Collections.emptyList();

        MaterialsList(int w, int h) {
            super(w, h, ROWH);
            bgcolor = new Color(0, 0, 0, 90);
        }

        void setItems(List<MaterialRow> items) {
            this.items = items;
            sb.val = 0;
        }

        @Override
        protected MaterialRow listitem(int i) {return items.get(i);}

        @Override
        protected int listitems() {return items.size();}

        @Override
        protected void drawitem(GOut g, MaterialRow row, int idx) {
            g.chcolor((idx % 2 == 0) ? new Color(255, 255, 255, 8) : new Color(255, 255, 255, 18));
            g.frect(Coord.z, g.sz());
            g.chcolor();
            int textX = UI.scale(4);
            String resName = row.resName;
            if(resName == null) {
                // No real recipe captured for this one -- see if Ring of Brodgar's
                // resource index has a matching item we can borrow an icon from.
                resName = IngredientIconResolver.getCached(row.name);
                if(resName == null) {IngredientIconResolver.resolveAsync(row.name);}
            }
            if(resName != null) {
                Tex t = icon(resName);
                if(t != null) {
                    g.image(t, new Coord(UI.scale(2), (ROWH - t.sz().y) / 2));
                }
                textX = UI.scale(2) + ICON_SZ.x + UI.scale(4);
            }
            g.atext(row.name, new Coord(textX, ROWH / 2), 0, 0.5);
            g.atext(String.format("%.2f", row.amount), new Coord(sz.x - (sb.vis() ? sb.sz.x : 0) - UI.scale(4), ROWH / 2), 1, 0.5);
        }
    }
}
