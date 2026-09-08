package haven.pathfinding;

import auto.Bot;
import haven.Button;
import haven.Coord;
import haven.Coord2d;
import haven.GOut;
import haven.GameUI;
import haven.Gob;
import haven.HackThread;
import haven.Label;
import haven.Listbox;
import haven.TextEntry;
import haven.UI;
import haven.GameUI.Hidewnd;
import haven.GameUI.MsgType;
import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import me.ender.CustomCursors;
import org.json.JSONArray;
import org.json.JSONObject;

public class CriticalRouteWnd extends Hidewnd {
   private static final int LIST_W = UI.scale(324);
   private static final int ROW_H = UI.scale(18);
   private static CriticalRouteBook draft = new CriticalRouteBook("untitled");
   private final TextEntry name;
   private final Label status;
   private final LegList legs;
   private final SavedList saved;
   private final OrganizerAreaSelector area = new OrganizerAreaSelector();
   private List<String> savedIds = new ArrayList<String>();

   public CriticalRouteWnd() {
      super(Coord.z, "Critical routes");
      this.justclose = true;
      this.name = (TextEntry)this.add(new TextEntry(LIST_W - UI.scale(70), draft.id), Coord.z);
      this.add(new Button(UI.scale(64), "Save") {
         public void click() {
            CriticalRouteWnd.this.save();
         }
      }, this.name.pos("ur").adds(6, 0));
      int y = this.name.sz.y + UI.scale(6);
      int bw = UI.scale(50);
      int gap = UI.scale(4);
      int x = 0;
      this.add(new Button(bw, "Here") {
         public void click() {
            CriticalRouteWnd.this.addHere();
         }
      }, x, y);
      x += bw + gap;
      this.add(new Button(bw, "Ground") {
         public void click() {
            CriticalRouteWnd.this.markGround();
         }
      }, x, y);
      x += bw + gap;
      this.add(new Button(bw, "Approach") {
         public void click() {
            CriticalRouteWnd.this.markApproach();
         }
      }, x, y);
      x += bw + gap;
      this.add(new Button(bw, "Object") {
         public void click() {
            CriticalRouteWnd.this.markGob();
         }
      }, x, y);
      x += bw + gap;
      this.add(new Button(bw, "Undo") {
         public void click() {
            draft.undo();
            CriticalRouteWnd.this.refreshLegs();
         }
      }, x, y);
      x += bw + gap;
      this.add(new Button(bw, "Clear") {
         public void click() {
            draft.legs.clear();
            CriticalRouteWnd.this.refreshLegs();
         }
      }, x, y);
      y += UI.scale(28);
      this.add(new Button(UI.scale(90), "Create area") {
         public void click() {
            CriticalRouteWnd.this.createArea();
         }
      }, 0, y);
      this.add(new Button(UI.scale(110), "Stock pile test") {
         public void click() {
            CriticalRouteWnd.this.stockpileTest();
         }
      }, UI.scale(96), y);
      this.add(new Button(UI.scale(92), "Export area") {
         public void click() {
            CriticalRouteWnd.this.exportArea();
         }
      }, UI.scale(212), y);
      y += UI.scale(28);
      this.status = (Label)this.add(new Label("Here / Ground / Approach / Object."), 0, y);
      y += UI.scale(18);
      this.legs = (LegList)this.add(new LegList(LIST_W, 8), 0, y);
      y += this.legs.sz.y + UI.scale(6);
      this.add(new Button(UI.scale(90), "Walk fwd") {
         public void click() {
            CriticalRouteWnd.this.walk(false);
         }
      }, 0, y);
      this.add(new Button(UI.scale(90), "Walk back") {
         public void click() {
            CriticalRouteWnd.this.walk(true);
         }
      }, UI.scale(96), y);
      this.add(new Button(UI.scale(100), "To campaign") {
         public void click() {
            CriticalRouteWnd.this.addToCampaign();
         }
      }, UI.scale(198), y);
      y += UI.scale(28);
      this.add(new Label("Saved routes"), 0, y);
      y += UI.scale(16);
      this.saved = (SavedList)this.add(new SavedList(LIST_W, 6), 0, y);
      y += this.saved.sz.y + UI.scale(6);
      this.add(new Button(UI.scale(70), "Load") {
         public void click() {
            CriticalRouteWnd.this.loadSelected();
         }
      }, 0, y);
      this.add(new Button(UI.scale(70), "Delete") {
         public void click() {
            CriticalRouteWnd.this.deleteSelected();
         }
      }, UI.scale(76), y);
      this.add(new Button(UI.scale(70), "Refresh") {
         public void click() {
            CriticalRouteWnd.this.refreshSaved();
         }
      }, UI.scale(152), y);
      this.pack();
      this.hide();
   }

   static CriticalRouteBook draft() {
      return draft;
   }

   public void show() {
      super.show();
      this.refreshLegs();
      this.refreshSaved();
      this.raise();
   }

   private GameUI gui() {
      return this.ui == null ? null : this.ui.gui;
   }

   private void msg(String t, MsgType type) {
      GameUI gui = this.gui();
      if (gui != null) {
         gui.msg(t, type);
      }
      this.status.settext(t);
   }

   private void addHere() {
      GameUI gui = this.gui();
      if (gui == null || gui.map == null || gui.map.player() == null || gui.map.player().rc == null) {
         this.msg("No player position", MsgType.ERROR);
         return;
      }
      draft.addStand(gui, gui.map.player().rc);
      this.refreshLegs();
      Coord tile = CriticalRouteBook.mapTile(gui.map.player().rc, CriticalRouteBook.sessTc(gui));
      this.msg(tile == null ? "Added stand on this floor" : "Added stand tile " + tile.x + "," + tile.y + " on this floor", MsgType.INFO);
   }

   private void markGround() {
      GameUI gui = this.gui();
      if (gui == null || gui.map == null) {
         return;
      }
      CustomCursors.startMarkingGround(gui.map, mc -> {
         draft.addStand(this.gui(), mc);
         this.refreshLegs();
         Coord tile = CriticalRouteBook.mapTile(mc, CriticalRouteBook.sessTc(this.gui()));
         this.msg(tile == null ? "Added ground stand" : "Added ground tile " + tile.x + "," + tile.y, MsgType.INFO);
      });
      this.msg("Click the map to add a stand (right-click cancels)", MsgType.INFO);
   }

   private void markGob() {
      GameUI gui = this.gui();
      if (gui == null || gui.map == null) {
         return;
      }
      CustomCursors.startPicking(gui.map, gob -> {
         draft.addGob(this.gui(), gob);
         this.refreshLegs();
         this.msg("Added " + draft.legs.get(draft.legs.size() - 1).label(), MsgType.INFO);
      }, true, true);
      this.msg("Click an object (door, boat, cart, stairs…) — right-click cancels", MsgType.INFO);
   }

   private void markApproach() {
      GameUI gui = this.gui();
      if (gui == null || gui.map == null) {
         return;
      }
      CustomCursors.startPicking(gui.map, gob -> {
         draft.addApproach(this.gui(), gob);
         this.refreshLegs();
         this.msg("Added " + draft.legs.get(draft.legs.size() - 1).label(), MsgType.INFO);
      }, true, true);
      this.msg("Click an object to approach (no action) — right-click cancels", MsgType.INFO);
   }

   private void createArea() {
      GameUI gui = this.gui();
      if (gui == null || gui.map == null) {
         return;
      }
      this.area.cancel();
      CustomCursors.startMarkingArea(gui.map, mc -> {
         int before = this.area.vertexCount();
         OrganizerAreaSelector.Selection sel = this.area.click(mc, gui.map.glob.map);
         if (sel != null) {
            this.msg("Area " + sel, MsgType.INFO);
         } else if (this.area.vertexCount() == before) {
            this.msg("Grid not loaded — move closer and click again", MsgType.ERROR);
         } else {
            this.msg("Corner " + this.area.vertexCount() + " of " + OrganizerAreaSelector.VERTEX_COUNT + " set — walk to the next corner", MsgType.INFO);
         }
      }, () -> {
         this.area.cancel();
         this.msg("Area selection cancelled", MsgType.INFO);
      }, OrganizerAreaSelector.VERTEX_COUNT);
      this.msg("Click four ground corners (walk between them) — right-click cancels", MsgType.INFO);
   }

   private void exportArea() {
      GameUI gui = this.gui();
      if (gui == null) {
         return;
      }
      OrganizerAreaSelector.Selection sel = this.area.selection();
      if (sel == null) {
         this.msg("Create an area first", MsgType.ERROR);
         return;
      }
      String name = this.name.text();
      if (name == null || name.trim().isEmpty()) {
         this.msg("Type a name first", MsgType.ERROR);
         return;
      }
      try {
         java.nio.file.Path outDir = java.nio.file.Paths.get("/home/greg/.haven");
         java.nio.file.Files.createDirectories(outDir);
         java.nio.file.Path outFile = outDir.resolve("navlab-area-export.json");
         org.json.JSONObject json = AreaExport.toJson(sel.gridVertices, name, "transition");
         java.nio.file.Files.write(outFile, (json.toString(2) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
         this.msg("Exported area \"" + name + "\" (transition) to " + outFile.toAbsolutePath(), MsgType.INFO);
      } catch (java.io.IOException e) {
         this.msg("Export failed: " + e.getMessage(), MsgType.ERROR);
      }
   }

   private void stockpileTest() {
      GameUI gui = this.gui();
      if (gui == null) {
         return;
      }
      OrganizerAreaSelector.Selection sel = this.area.selection();
      if (sel == null) {
         this.msg("Create an area first", MsgType.ERROR);
         return;
      }
      this.msg("Stockpile test: planning…", MsgType.INFO);
      StockpileOrganizer.start(gui, sel);
   }

   private void save() {
      try {
         draft = copyNamed();
         draft.save();
         this.name.settext(draft.id);
         this.refreshSaved();
         this.refreshLegs();
         this.msg("Saved " + draft.id + " (" + draft.legs.size() + " legs)", MsgType.INFO);
      } catch (IOException e) {
         this.msg("Save failed: " + e.getMessage(), MsgType.ERROR);
      }
   }

   private void loadSelected() {
      String id = this.saved.sel;
      if (id == null) {
         this.msg("Pick a saved route", MsgType.ERROR);
         return;
      }
      try {
         draft = CriticalRouteBook.load(id);
         this.name.settext(draft.id);
         this.refreshLegs();
         this.msg("Loaded " + draft.id, MsgType.INFO);
      } catch (IOException e) {
         this.msg("Load failed: " + e.getMessage(), MsgType.ERROR);
      }
   }

   private void deleteSelected() {
      String id = this.saved.sel;
      if (id == null) {
         return;
      }
      try {
         Files.deleteIfExists(CriticalRouteBook.dir().resolve(id + ".json"));
         this.refreshSaved();
         this.msg("Deleted " + id, MsgType.INFO);
      } catch (IOException e) {
         this.msg("Delete failed: " + e.getMessage(), MsgType.ERROR);
      }
   }

   private void addToCampaign() {
      if (draft.legs.isEmpty()) {
         this.msg("Add at least one stand or object first", MsgType.ERROR);
         return;
      }
      try {
         draft = copyNamed();
         draft.save();
         Path file = campaignFile();
         JSONObject root;
         if (Files.isRegularFile(file)) {
            root = new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
         } else {
            root = new JSONObject().put("consecutive", 5).put("routes", new JSONArray());
         }
         JSONArray routes = root.optJSONArray("routes");
         if (routes == null) {
            routes = new JSONArray();
            root.put("routes", routes);
         }
         boolean found = false;
         for (int i = 0; i < routes.length(); i++) {
            JSONObject r = routes.getJSONObject(i);
            if (draft.id.equals(r.optString("id")) || draft.id.equals(r.optString("route"))) {
               r.put("scenario", "campaign_recorded");
               r.put("route", draft.id);
               r.put("bidirectional", true);
               r.put("enabled", true);
               found = true;
               break;
            }
         }
         if (!found) {
            routes.put(
               new JSONObject()
                  .put("id", draft.id)
                  .put("scenario", "campaign_recorded")
                  .put("route", draft.id)
                  .put("aspects", new JSONArray().put("surface"))
                  .put("bidirectional", true)
                  .put("enabled", true)
            );
         }
         Files.createDirectories(file.getParent());
         Files.write(file, (root.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
         this.refreshSaved();
         this.msg("Campaign will run " + draft.id + " (5 consecutive both ways)", MsgType.INFO);
      } catch (IOException e) {
         this.msg("Campaign update failed: " + e.getMessage(), MsgType.ERROR);
      }
   }

   private CriticalRouteBook copyNamed() {
      CriticalRouteBook named = new CriticalRouteBook(this.name.text());
      named.legs.addAll(draft.legs);
      return named;
   }

   private static Path campaignFile() {
      Path cwd = haven.Utils.path(System.getProperty("user.dir", "."));
      Path tools = cwd.resolve("tools").resolve("critical-routes.json");
      if (Files.isRegularFile(tools) || Files.isDirectory(cwd.resolve("tools"))) {
         return tools;
      }
      Path parent = cwd.getParent();
      return parent == null ? tools : parent.resolve("tools").resolve("critical-routes.json");
   }

   private void walk(boolean reverse) {
      GameUI gui = this.gui();
      if (gui == null) {
         return;
      }
      if (draft.legs.isEmpty()) {
         this.msg("No legs to walk", MsgType.ERROR);
         return;
      }
      if (Bot.hasCurrent()) {
         this.msg("Another bot is running", MsgType.ERROR);
         return;
      }
      final CriticalRouteBook book = copyNamed();
      this.msg(reverse ? "Walking reverse…" : "Walking forward…", MsgType.INFO);
      Thread th = new HackThread(() -> {
         try {
            String err = RecordedRouteScenario.walkLive(gui, book, reverse);
            gui.msg(err == null ? (reverse ? "Reverse arrived" : "Forward arrived") : err, err == null ? MsgType.INFO : MsgType.ERROR);
         } catch (Exception e) {
            gui.msg("Route walk failed: " + e.getMessage(), MsgType.ERROR);
         }
      }, "pf-route-walk");
      th.start();
   }

   private void refreshLegs() {
      this.legs.display();
      this.status.settext(draft.legs.isEmpty() ? "Empty route — add Here / Ground / Approach / Object" : draft.legs.size() + " legs");
   }

   private void refreshSaved() {
      this.savedIds = new ArrayList<String>(CriticalRouteBook.listIds());
      this.saved.display();
   }

   private class LegList extends Listbox<CriticalRouteBook.Leg> {
      LegList(int w, int rows) {
         super(w, rows, ROW_H);
         this.bgcolor = new Color(0, 0, 0, 84);
      }

      protected CriticalRouteBook.Leg listitem(int i) {
         return draft.legs.get(i);
      }

      protected int listitems() {
         return draft.legs.size();
      }

      protected void drawitem(GOut g, CriticalRouteBook.Leg item, int i) {
         g.chcolor(i % 2 == 0 ? new Color(0, 0, 0, 84) : new Color(0, 0, 0, 42));
         g.frect(Coord.z, g.sz());
         g.chcolor();
         g.atext((i + 1) + ". " + item.label(), new Coord(UI.scale(4), this.itemh / 2), 0.0, 0.5);
      }
   }

   private class SavedList extends Listbox<String> {
      SavedList(int w, int rows) {
         super(w, rows, ROW_H);
         this.bgcolor = new Color(0, 0, 0, 84);
      }

      protected String listitem(int i) {
         return CriticalRouteWnd.this.savedIds.get(i);
      }

      protected int listitems() {
         return CriticalRouteWnd.this.savedIds.size();
      }

      protected void drawitem(GOut g, String item, int i) {
         g.chcolor(i % 2 == 0 ? new Color(0, 0, 0, 84) : new Color(0, 0, 0, 42));
         g.frect(Coord.z, g.sz());
         g.chcolor();
         g.atext(item, new Coord(UI.scale(4), this.itemh / 2), 0.0, 0.5);
      }
   }
}
