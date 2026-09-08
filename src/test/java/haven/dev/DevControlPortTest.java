package haven.dev;

import haven.Config.Variable;
import java.lang.reflect.Field;
import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DevControlPortTest {
   private static final String KEY = "haven.dev.control.port";

   @Test
   void defaultIsDisabledWhenPropertyUnset() throws Exception {
      String prev = System.getProperty("haven.dev.control.port");

      try {
         System.clearProperty("haven.dev.control.port");
         resetCache();
         Assertions.assertEquals(0, (Integer)DevControl.PORT.get(), "DevControl must be off by default (0 disables the bind)");
      } finally {
         restore(prev);
         resetCache();
      }
   }

   @Test
   void explicitPortStillEnablesServer() throws Exception {
      String prev = System.getProperty("haven.dev.control.port");

      try {
         System.setProperty("haven.dev.control.port", "18761");
         resetCache();
         Assertions.assertEquals(18761, (Integer)DevControl.PORT.get(), "explicit -Dhaven.dev.control.port=18761 must still enable the server");
      } finally {
         restore(prev);
         resetCache();
      }
   }

   @Test
   void pfScenarioListSerializesWithBundledJsonLibrary() {
      JSONObject result = DevControl.pfScenarios();
      Assertions.assertTrue(result.getBoolean("ok"));
      java.util.List<String> known = haven.pathfinding.PfTestRunner.knownScenarios();
      Assertions.assertEquals(known.size(), result.getJSONArray("scenarios").length());
      Assertions.assertEquals("observe", result.getJSONArray("scenarios").getString(0));
      Assertions.assertEquals("basement_cabinet_identify", result.getJSONArray("scenarios").getString(1));
      Assertions.assertEquals("move_to_marker", result.getJSONArray("scenarios").getString(2));
      Assertions.assertEquals("move_to_auto_open_ground", result.getJSONArray("scenarios").getString(3));
      Assertions.assertEquals("move_to_auto_obstacle_corridor", result.getJSONArray("scenarios").getString(4));
      Assertions.assertEquals("move_to_auto_known_long_leg", result.getJSONArray("scenarios").getString(5));
      Assertions.assertEquals("move_to_auto_cave_transition_approach", result.getJSONArray("scenarios").getString(6));
      Assertions.assertEquals("cross_cellar_door", result.getJSONArray("scenarios").getString(7));
      Assertions.assertEquals("cross_cellar_stairs", result.getJSONArray("scenarios").getString(8));
      Assertions.assertEquals("cross_minehole", result.getJSONArray("scenarios").getString(9));
      Assertions.assertEquals("select_open_ground", result.getJSONArray("scenarios").getString(10));
      Assertions.assertEquals("select_obstacle_corridor", result.getJSONArray("scenarios").getString(11));
      Assertions.assertEquals("select_known_long_leg", result.getJSONArray("scenarios").getString(12));
      Assertions.assertEquals("select_boulder_approach", result.getJSONArray("scenarios").getString(13));
      Assertions.assertEquals("select_cave_transition_approach", result.getJSONArray("scenarios").getString(14));
      Assertions.assertEquals("select_door_gate_approach", result.getJSONArray("scenarios").getString(15));
      Assertions.assertEquals("select_waterline_approach", result.getJSONArray("scenarios").getString(16));
      Assertions.assertEquals("surface_long_open_ground", result.getJSONArray("scenarios").getString(17));
      Assertions.assertEquals("campaign_recorded", result.getJSONArray("scenarios").getString(known.size() - 1));
   }

   private static void resetCache() throws Exception {
      Field inited = Variable.class.getDeclaredField("inited");
      inited.setAccessible(true);
      inited.setBoolean(DevControl.PORT, false);
   }

   private static void restore(String prev) {
      if (prev == null) {
         System.clearProperty("haven.dev.control.port");
      } else {
         System.setProperty("haven.dev.control.port", prev);
      }
   }
}
