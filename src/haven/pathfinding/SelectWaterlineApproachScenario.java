package haven.pathfinding;

import auto.Bot;
import auto.Bot.BotAction;
import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.GameUI;
import haven.Gob;
import haven.HackThread;
import haven.MapFile;
import haven.Moving;
import haven.UI;
import haven.Utils;
import haven.NamedPlaceResolver.Place;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

final class SelectWaterlineApproachScenario implements PfTestRunner.Scenario {
   @Override
   public String name() {
      return "select_waterline_approach";
   }

   @Override
   public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
      if (run.cancelled) {
         throw new PfTestRunner.Cancelled();
      } else {
         List<JSONObject> checks = new ArrayList<>();
         GameUI gui = ui == null ? null : ui.gui;
         if (gui != null && gui.map != null) {
            checks.add(PfTestRunner.check("in_game", true, "in game"));
            if (run.cancelled) {
               throw new PfTestRunner.Cancelled();
            } else {
               PrototypePathfinder.Scene scene;
               synchronized (ui) {
                  scene = PrototypePathfinder.observe(gui);
               }

               TransitionApproachSelector.Selection sel = TransitionApproachSelector.waterlineApproach(scene);
               if (run.cancelled) {
                  throw new PfTestRunner.Cancelled();
               } else {
                  checks.add(selectionCheck(sel));
                  return body(checks, factsJson(sel), sel.refused() ? "no approach selected: " + sel.refusal : null);
               }
            }
         } else {
            checks.add(PfTestRunner.check("in_game", false, "client is not in game (select_waterline_approach requires in-game state)"));
            return body(checks, refusalFacts("NO_GAME", "client is not in game; selection not attempted"), "not in game: selection not attempted");
         }
      }
   }

   static JSONObject selectionCheck(TransitionApproachSelector.Selection sel) {
      return sel == null
         ? PfTestRunner.check("selection_completed", false, "selection not attempted")
         : PfTestRunner.check("selection_completed", sel.selected(), sel.evidence);
   }

   static JSONObject refusalFacts(String refusal, String reason) {
      JSONObject f = new JSONObject();
      f.put("profile", TransitionApproachSelector.TransitionProfile.WATERLINE.name());
      f.put("selected", false);
      f.put("refusal", refusal);
      f.put("reason", reason);
      f.put("water_cells", 0);
      f.put("waterline_cells", 0);
      f.put("candidates", 0);
      return f;
   }

   static JSONObject factsJson(TransitionApproachSelector.Selection sel) {
      if (sel == null) {
         return refusalFacts("NO_GAME", "selection not attempted (no in-game state)");
      } else {
         JSONObject f = new JSONObject();
         f.put("profile", sel.profile.name());
         f.put("selected", sel.selected());
         if (sel.refused()) {
            f.put("refusal", sel.refusal.name());
            f.put("reason", sel.evidence);
            f.put("water_cells", sel.waterCells);
            f.put("waterline_cells", sel.waterlineCells);
            f.put("candidates", sel.candidates.size());
            return f;
         } else {
            f.put("refusal", JSONObject.NULL);
            f.put("reason", sel.evidence);
            f.put("water_cells", sel.waterCells);
            f.put("waterline_cells", sel.waterlineCells);
            f.put("candidates", sel.candidates.size());
            if (!sel.candidates.isEmpty()) {
               f.put("best_score", PfTestRunner.round2(sel.candidates.get(0).score));
            }

            f.put("approach_tile", new JSONArray().put(sel.approachTile.x).put(sel.approachTile.y));
            f.put("approach_world", new JSONArray().put(PfTestRunner.round2(sel.approachWorld.x)).put(PfTestRunner.round2(sel.approachWorld.y)));
            f.put("approach_side", sel.side);
            f.put("standoff", PfTestRunner.round2(sel.standoff));
            f.put("footprint_tiles", PfTestRunner.round2(sel.footprintTiles));
            f.put("player_dist", PfTestRunner.round2(sel.playerDist));
            if (!sel.candidates.isEmpty()) {
               f.put("route_cells", sel.candidates.get(0).route.size());
               f.put("route_expanded", sel.candidates.get(0).routeExpanded);
            }

            return f;
         }
      }
   }

   private static JSONObject body(List<JSONObject> checks, JSONObject facts, String note) {
      JSONObject o = new JSONObject();
      o.put("verdict", PfTestRunner.verdictOf(checks));
      JSONArray arr = new JSONArray();

      for (JSONObject c : checks) {
         arr.put(c);
      }

      o.put("checks", arr);
      if (facts != null) {
         o.put("facts", facts);
      }

      if (note != null && !note.isEmpty()) {
         o.put("note", note);
      }

      return o;
   }
}
