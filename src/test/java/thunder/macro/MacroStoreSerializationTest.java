package thunder.macro;

import com.google.gson.Gson;
import haven.Coord;
import haven.Coord2d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MacroStoreSerializationTest {
    private static final Gson GSON = MacroStore.GSON;

    @Test
    void itemActRoundTrip() {
	MacroStep.ItemAct s = new MacroStep.ItemAct("gfx/invobj/waterskin", 3, 0);
	String json = GSON.toJson(s, MacroStep.class);
	MacroStep back = GSON.fromJson(json, MacroStep.class);
	assertTrue(back instanceof MacroStep.ItemAct);
	assertEquals("gfx/invobj/waterskin", ((MacroStep.ItemAct) back).resid);
	assertEquals(3, ((MacroStep.ItemAct) back).button);
    }

    @Test
    void gobActRoundTrip() {
	MacroStep.GobAct s = new MacroStep.GobAct("gfx/terobjs/barrel", Coord2d.of(123.5, 456.5), 3, 0);
	String json = GSON.toJson(s, MacroStep.class);
	MacroStep back = GSON.fromJson(json, MacroStep.class);
	assertTrue(back instanceof MacroStep.GobAct);
	MacroStep.GobAct g = (MacroStep.GobAct) back;
	assertEquals("gfx/terobjs/barrel", g.resid);
	assertEquals(123.5, g.lastPos.x, 1e-9);
	assertEquals(456.5, g.lastPos.y, 1e-9);
    }

    @Test
    void invDropRoundTrip() {
	MacroStep.InvDrop s = new MacroStep.InvDrop(new Coord(2, 3));
	String json = GSON.toJson(s, MacroStep.class);
	MacroStep back = GSON.fromJson(json, MacroStep.class);
	assertTrue(back instanceof MacroStep.InvDrop);
	assertEquals(2, ((MacroStep.InvDrop) back).slot.x);
	assertEquals(3, ((MacroStep.InvDrop) back).slot.y);
    }

    @Test
    void flowerChoiceRoundTrip() {
	MacroStep.FlowerChoice s = new MacroStep.FlowerChoice("Drink");
	String json = GSON.toJson(s, MacroStep.class);
	MacroStep back = GSON.fromJson(json, MacroStep.class);
	assertTrue(back instanceof MacroStep.FlowerChoice);
	assertEquals("Drink", ((MacroStep.FlowerChoice) back).optionName);
    }

    @Test
    void waitRoundTrip() {
	MacroStep.Wait s = new MacroStep.Wait(MacroStep.Wait.Kind.PROGRESS, 30000);
	String json = GSON.toJson(s, MacroStep.class);
	MacroStep back = GSON.fromJson(json, MacroStep.class);
	assertTrue(back instanceof MacroStep.Wait);
	assertEquals(MacroStep.Wait.Kind.PROGRESS, ((MacroStep.Wait) back).kind);
	assertEquals(30000, ((MacroStep.Wait) back).timeoutMs);
    }

    @Test
    void sleepRoundTrip() {
	MacroStep.Sleep s = new MacroStep.Sleep(500);
	String json = GSON.toJson(s, MacroStep.class);
	MacroStep back = GSON.fromJson(json, MacroStep.class);
	assertTrue(back instanceof MacroStep.Sleep);
	assertEquals(500, ((MacroStep.Sleep) back).ms);
    }

    @Test
    void cmdRoundTrip() {
	MacroStep.Cmd s = new MacroStep.Cmd("macro run other");
	String json = GSON.toJson(s, MacroStep.class);
	MacroStep back = GSON.fromJson(json, MacroStep.class);
	assertTrue(back instanceof MacroStep.Cmd);
	assertEquals("macro run other", ((MacroStep.Cmd) back).text);
    }

    @Test
    void waitGobNearRoundTrip() {
	MacroStep.Wait s = new MacroStep.Wait(MacroStep.Wait.Kind.GOB_NEAR, 5000);
	s.resid = "gfx/terobjs/cattle";
	s.gobNear = Coord2d.of(123, 456);
	s.gobRadius = 50.0;
	String json = GSON.toJson(s, MacroStep.class);
	MacroStep back = GSON.fromJson(json, MacroStep.class);
	assertTrue(back instanceof MacroStep.Wait);
	MacroStep.Wait w = (MacroStep.Wait) back;
	assertEquals(MacroStep.Wait.Kind.GOB_NEAR, w.kind);
	assertEquals("gfx/terobjs/cattle", w.resid);
	assertEquals(123, w.gobNear.x, 1e-9);
	assertEquals(50.0, w.gobRadius, 1e-9);
    }

    @Test
    void waitMessageRoundTrip() {
	MacroStep.Wait s = new MacroStep.Wait(MacroStep.Wait.Kind.MESSAGE, 5000);
	s.pattern = "You have stopped";
	String json = GSON.toJson(s, MacroStep.class);
	MacroStep back = GSON.fromJson(json, MacroStep.class);
	assertTrue(back instanceof MacroStep.Wait);
	assertEquals("You have stopped", ((MacroStep.Wait) back).pattern);
    }

    @Test
    void unknownTypeThrows() {
	String json = "{\"type\":\"FROBNICATE\"}";
	assertThrows(IllegalArgumentException.class, () -> GSON.fromJson(json, MacroStep.class));
    }
}
