package haven.pathfinding;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SurfaceTravelScenarioTest {
   @Test
   void missingGameFailsClosedWithoutJsonArrayListCrash() throws Exception {
      for (SurfaceTravelScenario.Kind k : SurfaceTravelScenario.Kind.values()) {
         JSONObject r = new SurfaceTravelScenario(k).execute(new PfTestRunner.Run(k.name), null);
         Assertions.assertEquals("FAIL", r.getString("verdict"), k.name);
         Assertions.assertEquals("NO_GAME", r.getJSONObject("facts").getString("refusal"), k.name);
         Assertions.assertTrue(r.has("checks"), k.name);
         JSONArray checks = r.getJSONArray("checks");
         Assertions.assertTrue(checks.length() > 0, k.name);
      }
   }

   @Test
   void orgJsonJsonArrayDoesNotAcceptJavaList() {
      java.util.List<JSONObject> checks = new java.util.ArrayList<JSONObject>();
      checks.add(new JSONObject().put("name", "fixture"));
      Assertions.assertThrows(org.json.JSONException.class, () -> new JSONArray(checks));
   }
}
