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

final class BasementCabinetIdentifyScenario implements PfTestRunner.Scenario {
   @Override
   public String name() {
      return "basement_cabinet_identify";
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
            PrototypePathfinder.Scene scene;
            synchronized (ui) {
               scene = PrototypePathfinder.observe(gui);
            }

            JSONObject eval = PfTestRunner.evaluateCabinets(scene);
            JSONArray arr = eval.getJSONArray("checks");

            for (int i = 0; i < arr.length(); i++) {
               checks.add(arr.getJSONObject(i));
            }

            return body(checks, eval.has("fixture") ? eval.getJSONObject("fixture") : null, eval.optString("note", null));
         } else {
            checks.add(PfTestRunner.check("in_game", false, "client is not in game (basement_cabinet_identify requires in-game state)"));
            return body(checks, null, null);
         }
      }
   }

   private static JSONObject body(List<JSONObject> checks, JSONObject fixture, String note) {
      JSONObject o = new JSONObject();
      o.put("verdict", PfTestRunner.verdictOf(checks));
      JSONArray arr = new JSONArray();

      for (JSONObject c : checks) {
         arr.put(c);
      }

      o.put("checks", arr);
      if (fixture != null) {
         o.put("fixture", fixture);
      }

      if (note != null && !note.isEmpty()) {
         o.put("note", note);
      }

      return o;
   }
}
