package haven.pathfinding;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CriticalRouteScenarioTest {
   @Test
   void missingGameFailsClosedWithoutJsonArrayListCrash() throws Exception {
      for (CriticalRouteScenario.Kind k : CriticalRouteScenario.Kind.values()) {
         JSONObject r = new CriticalRouteScenario(k).execute(new PfTestRunner.Run(k.name), null);
         Assertions.assertEquals("FAIL", r.getString("verdict"), k.name);
         Assertions.assertEquals("NO_GAME", r.getJSONObject("facts").getString("refusal"), k.name);
         Assertions.assertFalse(r.getJSONObject("facts").optBoolean("forward", true), k.name);
         Assertions.assertFalse(r.getJSONObject("facts").optBoolean("reverse", true), k.name);
         Assertions.assertFalse(r.getJSONObject("facts").optBoolean("original_goal_preserved", true), k.name);
         JSONArray checks = r.getJSONArray("checks");
         Assertions.assertTrue(checks.length() > 0, k.name);
         boolean fixtureFail = false;
         for (int i = 0; i < checks.length(); i++) {
            JSONObject c = checks.getJSONObject(i);
            if ("fixture".equals(c.optString("name")) && "fail".equals(c.optString("status"))) {
               fixtureFail = true;
            }
         }
         Assertions.assertTrue(fixtureFail, k.name + " must not PASS without a game");
      }
   }

   @Test
   void campaignRoutesAreAllowlistedAndBidirectional() {
      Assertions.assertEquals(8, CriticalRouteScenario.Kind.values().length);
      for (CriticalRouteScenario.Kind k : CriticalRouteScenario.Kind.values()) {
         Assertions.assertTrue(k.name.startsWith("campaign_"), k.name);
         Assertions.assertTrue(k.bidirectional, k.name);
         Assertions.assertNull(PfTestRunner.validateScenario(k.name), k.name);
         Assertions.assertEquals(k.name, new CriticalRouteScenario(k).name());
      }
   }
}
