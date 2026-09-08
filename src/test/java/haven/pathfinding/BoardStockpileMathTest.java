package haven.pathfinding;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class BoardStockpileMathTest {
    @Test
    void areaSelectionIsTileAlignedAndInclusive() {
        BoardStockpileMath.Area a = BoardStockpileMath.fromClicks(12, 23, -1, 1);
        Assertions.assertEquals(-11, a.x, 1e-9);
        Assertions.assertEquals(0, a.y, 1e-9);
        Assertions.assertEquals(33, a.width, 1e-9);
        Assertions.assertEquals(33, a.height, 1e-9);
    }

    @Test
    void workerStateMappingIsTerseAndTerminal() {
        Assertions.assertEquals("walking", BoardStockpileMath.uiState("QUEUED", null));
        Assertions.assertEquals("completed", BoardStockpileMath.uiState("SUCCEEDED", null));
        Assertions.assertEquals("failed", BoardStockpileMath.uiState("CANCELLED", "CANCELLED"));
    }

    @Test
    void planRequestContainsOnlyCredentialFreeWorkerFields() {
        BoardStockpileMath.Area a = BoardStockpileMath.fromClicks(0, 0, 20, 20);
        String request = BoardStockpileClient.planRequest(a, 2, 4, "gfx/invobjs/board");
        Assertions.assertTrue(request.contains("\"op\":\"plan_stockpile\""));
        Assertions.assertTrue(request.contains("\"requested_piles\":2"));
        Assertions.assertFalse(request.contains("password"));
        Assertions.assertFalse(request.contains("cookie"));
    }
}
