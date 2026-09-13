package thunder.cookbook;

import haven.*;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checklist of every Cookbook dish (from /food-info.json, ~930 entries) we do
 * NOT yet have real captured recipe data for (RecipeCapture.capturedNames()),
 * so the "constantly update as we unlock more recipes" problem has a concrete
 * list to work off of instead of discovering gaps by accident. There is no
 * offline source for real recipe ingredients/quantities (confirmed again by
 * decompiling paginae/craft/*.res directly: a recipe button's action layer
 * has only its name + parent menu + click target, never ingredients) -- this
 * window can't fill gaps by itself, it just tells you which dish to go open
 * a crafting window for next. See docs/cookbook-integration.md.
 */
public class MissingRecipesWnd extends WindowX {
    private static final Coord ICON_SZ = UI.scale(20, 20);
    private static final int ROWH = UI.scale(26);
    private static final Coord LIST_SZ = new Coord(UI.scale(260), UI.scale(360));

    private final Label status;
    private final MissingList list;
    private final Map<String, Tex> icons = new HashMap<>();
    private boolean loading = false;

    private volatile List<CookbookItem> pendingItems = null;
    private volatile boolean pendingReady = false;

    public MissingRecipesWnd() {
        super(Coord.z, "Missing Recipes");
        justclose = true;

        status = add(new Label("Loading..."), 0, 0);
        Button refresh = add(new Button(UI.scale(70), "Refresh", this::load), status.pos("ur").adds(20, -4));
        list = add(new MissingList(LIST_SZ.x, LIST_SZ.y / ROWH), status.pos("bl").adds(0, 4));

        pack();
        load();
    }

    public static void toggle(UI ui) {
        if((ui == null) || (ui.gui == null) || (ui.gui.cookbookwnd == null)) {return;}
        if(ui.gui.missingRecipesWnd == null) {
            ui.gui.missingRecipesWnd = ui.gui.add(new MissingRecipesWnd(), UI.scale(new Coord(260, 200)));
        } else {
            ui.gui.missingRecipesWnd.destroy();
        }
    }

    @Override
    public void destroy() {
        if((ui != null) && (ui.gui != null) && (ui.gui.missingRecipesWnd == this)) {ui.gui.missingRecipesWnd = null;}
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

    private void load() {
        List<CookbookItem> have = CookbookService.lastGood();
        if(!have.isEmpty()) {
            setItems(have);
            return;
        }
        if(loading) {return;}
        loading = true;
        status.settext("Fetching food data...");
        CookbookService.refreshAsync((items, error) -> {
            synchronized(ui) {
                if((ui == null) || (parent == null)) {return;} // window closed meanwhile
                pendingItems = items;
                pendingReady = true;
            }
        });
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if(pendingReady) {
            pendingReady = false;
            loading = false;
            if(pendingItems != null) {setItems(pendingItems);}
            pendingItems = null;
        }
    }

    private void setItems(List<CookbookItem> all) {
        Set<String> captured = RecipeCapture.capturedNames();
        List<CookbookItem> missing = new ArrayList<>();
        for(CookbookItem item : all) {
            if(!captured.contains(item.resourceName)) {missing.add(item);}
        }
        missing.sort(Comparator.comparing(a -> a.name, String.CASE_INSENSITIVE_ORDER));
        list.setItems(missing);
        status.settext(String.format("%,d missing / %,d total (%,d captured)", missing.size(), all.size(), captured.size()));
    }

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

    private class MissingList extends Listbox<CookbookItem> {
        private List<CookbookItem> items = Collections.emptyList();

        MissingList(int w, int h) {
            super(w, h, ROWH);
            bgcolor = new Color(0, 0, 0, 90);
        }

        void setItems(List<CookbookItem> items) {
            this.items = items;
            sb.val = 0;
        }

        @Override
        protected CookbookItem listitem(int i) {return items.get(i);}

        @Override
        protected int listitems() {return items.size();}

        @Override
        protected void drawitem(GOut g, CookbookItem item, int idx) {
            g.chcolor((idx % 2 == 0) ? new Color(255, 255, 255, 8) : new Color(255, 255, 255, 18));
            g.frect(Coord.z, g.sz());
            g.chcolor();
            Tex t = icon(item.resourceName);
            int textX = UI.scale(4);
            if(t != null) {
                g.image(t, new Coord(UI.scale(2), (ROWH - t.sz().y) / 2));
                textX = UI.scale(2) + ICON_SZ.x + UI.scale(4);
            }
            g.atext(item.name, new Coord(textX, ROWH / 2), 0, 0.5);
        }
    }
}
