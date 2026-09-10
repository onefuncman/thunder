package haven.pathfinding;

import haven.Button;
import haven.Coord;
import haven.GOut;
import haven.GameUI;
import haven.Gob;
import haven.GobHighlight;
import haven.Label;
import haven.Listbox;
import haven.TextEntry;
import haven.UI;
import haven.GameUI.Hidewnd;
import haven.GameUI.MsgType;
import haven.Widget.KeyDownEvent;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public class PathfinderWnd extends Hidewnd {
   private static final int LIST_ROWS = 16;
   private static final int ROW_H = UI.scale(18);
   private final TextEntry input;
   private final Label status;
   private final PathfinderWnd.ResultList results;
   private List<PrototypePathfinder.NearbyGob> hits = new ArrayList<>();
   private long highlighted = -1L;
   private double sinceRefresh;

   public PathfinderWnd() {
      super(Coord.z, "Pathfinder");
      this.justclose = true;
      int listW = UI.scale(280);
      int btnW = UI.scale(60);
      this.input = (TextEntry)this.add(new TextEntry(listW - btnW - UI.scale(6), "") {
         protected void changed() {
            PathfinderWnd.this.refresh();
         }

         public void activate(String text) {
            PathfinderWnd.this.pathToSelected();
         }
      });
      this.add(new Button(btnW, "Path") {
         public void click() {
            PathfinderWnd.this.pathToSelected();
         }
      }, this.input.pos("ur").adds(6, 0));
      this.status = (Label)this.add(new Label("Nearby objects, closest first"), this.input.pos("bl").adds(0, 4));
      this.results = (PathfinderWnd.ResultList)this.add(new PathfinderWnd.ResultList(listW, 16), this.status.pos("bl").adds(0, 4));
      this.add(new Button(listW, "Catalog cupboards") {
         public void click() {
            GameUI gui = PathfinderWnd.this.gui();
            if (gui != null) {
               CupboardBot.start(gui);
            }
         }
      }, this.results.pos("bl").adds(0, 6));
      this.pack();
      Coord min = UI.scale(300, 400);
      if (this.csz().x < min.x || this.csz().y < min.y) {
         this.resize(new Coord(Math.max(this.csz().x, min.x), Math.max(this.csz().y, min.y)));
      }

      this.hide();
   }

   public boolean keydown(KeyDownEvent ev) {
      if (ev.code == 27 && this.input.text().length() > 0) {
         this.input.settext("");
         this.refresh();
         return true;
      } else {
         return super.keydown(ev);
      }
   }

   public void tick(double dt) {
      super.tick(dt);
      if (this.visible) {
         this.sinceRefresh += dt;
         if (this.sinceRefresh >= 0.4) {
            this.refresh();
         }
      }
   }

   public void show() {
      super.show();
      this.setfocus(this.input);
      this.refresh();
      this.raise();
   }

   public void hide() {
      super.hide();
      this.clearHighlight();
      this.hits = new ArrayList<>();
   }

   public void dispose() {
      this.clearHighlight();
      super.dispose();
   }

   private GameUI gui() {
      return this.ui == null ? null : this.ui.gui;
   }

   private void refresh() {
      this.sinceRefresh = 0.0;
      GameUI gui = this.gui();
      if (gui != null) {
         long keep = this.results.sel != null ? ((PrototypePathfinder.NearbyGob)this.results.sel).id : this.highlighted;
         this.hits = PrototypePathfinder.nearby(gui, this.input.text());
         this.status
            .settext(this.hits.isEmpty() ? "No nearby objects" : String.format("%d nearby object%s", this.hits.size(), this.hits.size() == 1 ? "" : "s"));
         PrototypePathfinder.NearbyGob keepHit = null;

         for (PrototypePathfinder.NearbyGob hit : this.hits) {
            if (hit.id == keep) {
               keepHit = hit;
               break;
            }
         }

         this.results.change(keepHit);
         if (keepHit == null && this.highlighted >= 0L) {
            this.clearHighlight();
         }
      }
   }

   private void pathToSelected() {
      PrototypePathfinder.NearbyGob sel = (PrototypePathfinder.NearbyGob)this.results.sel;
      if (sel == null && !this.hits.isEmpty()) {
         sel = this.hits.get(0);
      }

      if (sel == null) {
         GameUI gui = this.gui();
         if (gui != null) {
            gui.msg("Pathfinder: pick an object first", MsgType.ERROR);
         }
      } else {
         PrototypePathfinder.goToGob(this.gui(), sel.id);
      }
   }

   private void highlight(long id) {
      if (this.highlighted != id) {
         this.clearHighlight();
         if (id >= 0L && this.ui != null && this.ui.sess != null) {
            Gob gob = this.ui.sess.glob.oc.getgob(id);
            if (gob != null) {
               GobHighlight h = (GobHighlight)gob.getattr(GobHighlight.class);
               if (h == null) {
                  h = new GobHighlight(gob);
                  gob.setattr(h);
               }

               h.setFlashing(true);
               this.ui.root.effects.stickGob(gob);
               this.highlighted = id;
            }
         }
      }
   }

   private void clearHighlight() {
      if (this.highlighted >= 0L) {
         if (this.ui != null && this.ui.sess != null) {
            this.ui.root.effects.unstickGob(this.highlighted);
            Gob gob = this.ui.sess.glob.oc.getgob(this.highlighted);
            if (gob != null) {
               GobHighlight h = (GobHighlight)gob.getattr(GobHighlight.class);
               if (h != null && h.isFlashing()) {
                  h.setFlashing(false);
                  if (!h.isActive()) {
                     gob.delattr(GobHighlight.class);
                  }
               }
            }
         }

         this.highlighted = -1L;
      }
   }

   private class ResultList extends Listbox<PrototypePathfinder.NearbyGob> {
      private final Color rowEven = new Color(0, 0, 0, 84);
      private final Color rowOdd = new Color(0, 0, 0, 42);

      ResultList(int w, int rows) {
         super(w, rows, PathfinderWnd.ROW_H);
         this.bgcolor = new Color(0, 0, 0, 84);
      }

      protected PrototypePathfinder.NearbyGob listitem(int i) {
         return PathfinderWnd.this.hits.get(i);
      }

      protected int listitems() {
         return PathfinderWnd.this.hits.size();
      }

      protected void drawitem(GOut g, PrototypePathfinder.NearbyGob item, int i) {
         g.chcolor(i % 2 == 0 ? this.rowEven : this.rowOdd);
         g.frect(Coord.z, g.sz());
         g.chcolor();
         g.atext(item.label(), new Coord(UI.scale(4), this.itemh / 2), 0.0, 0.5);
      }

      public void change(PrototypePathfinder.NearbyGob item) {
         super.change(item);
         if (item != null) {
            PathfinderWnd.this.highlight(item.id);
         }
      }

      protected void itemactivate(PrototypePathfinder.NearbyGob item) {
         this.change(item);
         PathfinderWnd.this.pathToSelected();
      }

      protected Object itemtip(PrototypePathfinder.NearbyGob item) {
         return String.format("%s\n%s\nid %d", item.name, item.resid, item.id);
      }
   }
}
