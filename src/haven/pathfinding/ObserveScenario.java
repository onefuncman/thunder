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

final class ObserveScenario implements PfTestRunner.Scenario {
   @Override
   public String name() {
      return "observe";
   }

   @Override
   public JSONObject execute(PfTestRunner.Run run, UI ui) throws Exception {
      List<JSONObject> checks = new ArrayList<>();
      GameUI gui = ui == null ? null : ui.gui;
      if (gui != null && gui.map != null) {
         long deadline = System.currentTimeMillis() + 3000L;
         boolean idle = false;

         while (true) {
            label107: {
               if (System.currentTimeMillis() < deadline) {
                  if (run.cancelled) {
                     throw new PfTestRunner.Cancelled();
                  }

                  synchronized (ui) {
                     Gob me = gui.map.player();
                     if (me != null && me.getattr(Moving.class) != null) {
                        break label107;
                     }

                     idle = true;
                  }
               }

               if (run.cancelled) {
                  throw new PfTestRunner.Cancelled();
               }

               PrototypePathfinder.Scene scene;
               synchronized (ui) {
                  scene = PrototypePathfinder.observe(gui);
               }

               checks.add(
                  PfTestRunner.check(
                     "player_present", scene.player != null, scene.player == null ? "no player gob in game" : "player at " + PfTestHarness.pt(scene.player)
                  )
               );
               checks.add(
                  PfTestRunner.check(
                     "occupancy_present",
                     scene.occupancy != null,
                     scene.occupancy == null
                        ? "occupancy build returned null"
                        : scene.occupancy.w + "x" + scene.occupancy.h + " grid, " + scene.obstacles + " obstacles"
                  )
               );
               if (scene.player == null) {
                  checks.add(PfTestRunner.skip("idle_player_not_solid", "no player to evaluate"));
               } else if (!idle) {
                  checks.add(PfTestRunner.skip("idle_player_not_solid", "player still moving after 3000ms; idle invariant not evaluated"));
               } else {
                  checks.add(
                     PfTestRunner.check(
                        "idle_player_not_solid",
                        !scene.playerInSolid,
                        scene.playerInSolid ? "idle player cell is occupancy-SOLID (LEGAL POS MARKED SOLID)" : "idle player cell is free"
                     )
                  );
               }

               return body(checks, scene, idle ? null : "idle invariant skipped: player was moving at capture");
            }

            Thread.sleep(100L);
         }
      } else {
         checks.add(PfTestRunner.check("in_game", false, "client is not in game (observe requires in-game state)"));
         return body(checks, null, null);
      }
   }

   private static JSONObject body(List<JSONObject> checks, PrototypePathfinder.Scene scene, String note) {
      JSONObject o = new JSONObject();
      o.put("verdict", PfTestRunner.verdictOf(checks));
      JSONArray arr = new JSONArray();

      for (JSONObject c : checks) {
         arr.put(c);
      }

      o.put("checks", arr);
      if (scene != null) {
         o.put("scene", PfTestRunner.sceneJson(scene));
      }

      if (note != null) {
         o.put("note", note);
      }

      return o;
   }
}
