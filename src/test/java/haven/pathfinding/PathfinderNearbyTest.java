package haven.pathfinding;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PathfinderNearbyTest {
   @Test
   void emptyQueryMatchesEverything() {
      Assertions.assertTrue(PrototypePathfinder.matches("", 12L, "Maple Tree", "gfx/terobjs/trees/maple"));
      Assertions.assertTrue(PrototypePathfinder.matches("  ", 12L, "Maple Tree", "gfx/terobjs/trees/maple"));
      Assertions.assertTrue(PrototypePathfinder.matches(null, 12L, "Maple Tree", "gfx/terobjs/trees/maple"));
   }

   @Test
   void matchesPrettyNameResidAndId() {
      Assertions.assertTrue(PrototypePathfinder.matches("maple", 99L, "Maple Tree", "gfx/terobjs/trees/maple"));
      Assertions.assertTrue(PrototypePathfinder.matches("terobjs/trees", 99L, "Maple Tree", "gfx/terobjs/trees/maple"));
      Assertions.assertTrue(PrototypePathfinder.matches("99", 99L, "Maple Tree", "gfx/terobjs/trees/maple"));
      Assertions.assertFalse(PrototypePathfinder.matches("birch", 99L, "Maple Tree", "gfx/terobjs/trees/maple"));
   }
}
