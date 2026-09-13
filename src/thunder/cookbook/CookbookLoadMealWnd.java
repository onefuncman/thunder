package thunder.cookbook;

import haven.*;

import java.awt.Color;
import java.util.Collections;
import java.util.List;

/** Picker popup for CookbookPlanWnd's "Load Meal" button: left-click a saved meal to load it, right-click to delete it. */
public class CookbookLoadMealWnd extends WindowX {
    private static final int ROWH = UI.scale(20);
    private static final int LIST_W = UI.scale(240);

    private final CookbookWnd owner;
    private final CookbookPlanWnd planWnd;
    private final MealList list;
    private final Label empty;
    private final Label status;

    public CookbookLoadMealWnd(CookbookWnd owner, CookbookPlanWnd planWnd) {
        super(Coord.z, "Load Meal");
        this.owner = owner;
        this.planWnd = planWnd;
        justclose = true;

        Widget prev = add(new Label("Click a meal to load it (stays open -- click another anytime). Right-click to delete."), 0, 0);
        list = add(new MealList(LIST_W, 8), prev.pos("bl").adds(0, 4));
        empty = add(new Label("No saved meals yet."), prev.pos("bl").adds(0, 4));
        status = add(new Label(""), list.pos("bl").adds(0, 4));

        refresh();
        pack();
    }

    private void refresh() {
        List<String> names = owner.savedMealNames();
        list.setItems(names);
        status.c = new Coord(0, (names.isEmpty() ? empty : list).pos("bl").y + UI.scale(4));
        if(names.isEmpty()) {
            list.hide();
            empty.show();
        } else {
            list.show();
            empty.hide();
        }
        pack();
    }

    @Override
    public void destroy() {
        if(planWnd != null) {planWnd.onMealWndClosed();}
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

    private class MealList extends Listbox<String> {
        private List<String> items = Collections.emptyList();

        MealList(int w, int h) {
            super(w, h, ROWH);
            bgcolor = new Color(0, 0, 0, 90);
        }

        void setItems(List<String> items) {
            this.items = items;
            sb.val = 0;
        }

        @Override
        protected String listitem(int i) {return items.get(i);}

        @Override
        protected int listitems() {return items.size();}

        @Override
        protected void drawitem(GOut g, String name, int idx) {
            g.chcolor((idx % 2 == 0) ? new Color(255, 255, 255, 8) : new Color(255, 255, 255, 18));
            g.frect(Coord.z, g.sz());
            g.chcolor();
            g.atext(name, new Coord(UI.scale(4), ROWH / 2), 0, 0.5);
        }

        @Override
        protected void itemclick(String name, int button) {
            if(button == 1) {
                owner.loadMeal(name);
                change(name);
                status.settext("Loaded \"" + name + "\".");
            } else if(button == 3) {
                owner.deleteMeal(name);
                refresh();
            }
        }
    }
}
