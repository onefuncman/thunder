package me.ender;

import haven.*;
import org.junit.jupiter.api.Test;
import java.awt.Color;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class ClientUtilsTest {

    // --- clamp ---

    @Test
    void clampWithinRange() {
        assertEquals(5, ClientUtils.clamp(5, 0, 10));
    }

    @Test
    void clampBelowMin() {
        assertEquals(0, ClientUtils.clamp(-5, 0, 10));
    }

    @Test
    void clampAboveMax() {
        assertEquals(10, ClientUtils.clamp(15, 0, 10));
    }

    @Test
    void clampAtBoundaries() {
        assertEquals(0, ClientUtils.clamp(0, 0, 10));
        assertEquals(10, ClientUtils.clamp(10, 0, 10));
    }

    // --- str2int ---

    @Test
    void str2intValid() {
        assertEquals(42, ClientUtils.str2int("42"));
        assertEquals(-5, ClientUtils.str2int("-5"));
    }

    @Test
    void str2intInvalid() {
        assertEquals(0, ClientUtils.str2int("abc"));
        assertEquals(0, ClientUtils.str2int(null));
        assertEquals(0, ClientUtils.str2int(""));
    }

    // --- str2cc ---

    @Test
    void str2ccClampsTo0to255() {
        assertEquals(128, ClientUtils.str2cc("128"));
        assertEquals(0, ClientUtils.str2cc("-10"));
        assertEquals(255, ClientUtils.str2cc("300"));
    }

    // --- hex2color ---

    @Test
    void hex2colorValid() {
        Color c = ClientUtils.hex2color("FF0000", Color.BLACK);
        assertEquals(255, c.getRed());
        assertEquals(0, c.getGreen());
        assertEquals(0, c.getBlue());
    }

    @Test
    void hex2colorWithAlpha() {
        Color c = ClientUtils.hex2color("80FF0000", Color.BLACK);
        assertEquals(128, c.getAlpha());
        assertEquals(255, c.getRed());
    }

    @Test
    void hex2colorNull() {
        assertEquals(Color.BLACK, ClientUtils.hex2color(null, Color.BLACK));
    }

    @Test
    void hex2colorInvalid() {
        assertEquals(Color.WHITE, ClientUtils.hex2color("notahex", Color.WHITE));
    }

    // --- color2hex ---

    @Test
    void color2hexWithAlpha() {
        String hex = ClientUtils.color2hex(new Color(255, 0, 0, 128));
        assertNotNull(hex);
        assertTrue(hex.contains("ff0000"));
    }

    @Test
    void color2hexNoAlpha() {
        String hex = ClientUtils.color2hex(new Color(255, 0, 0), false);
        assertEquals("ff0000", hex);
    }

    @Test
    void color2hexNull() {
        assertNull(ClientUtils.color2hex(null));
    }

    // --- checkbit / setbit ---

    @Test
    void checkbitTrue() {
        assertTrue(ClientUtils.checkbit(0b1010, 1));
        assertTrue(ClientUtils.checkbit(0b1010, 3));
    }

    @Test
    void checkbitFalse() {
        assertFalse(ClientUtils.checkbit(0b1010, 0));
        assertFalse(ClientUtils.checkbit(0b1010, 2));
    }

    @Test
    void setbitOn() {
        assertEquals(0b1011, ClientUtils.setbit(0b1010, 0, true));
    }

    @Test
    void setbitOff() {
        assertEquals(0b1000, ClientUtils.setbit(0b1010, 1, false));
    }

    // --- round ---

    @Test
    void roundToDecimalPlaces() {
        assertEquals(3.14, ClientUtils.round(3.14159, 2), 1e-10);
        assertEquals(3.1, ClientUtils.round(3.14159, 1), 1e-10);
        assertEquals(3.0, ClientUtils.round(3.14159, 0), 1e-10);
    }

    // --- f2s ---

    @Test
    void f2sDefaultPrecision() {
        String s = ClientUtils.f2s(3.14159);
        assertTrue(s.contains("3.14"));
    }

    @Test
    void f2sCustomPrecision() {
        assertEquals("3.1", ClientUtils.f2s(3.14, 1));
    }

    // --- formatTimeLong ---

    @Test
    void formatTimeLongSeconds() {
        assertEquals("30s", ClientUtils.formatTimeLong(30));
    }

    @Test
    void formatTimeLongMinutes() {
        assertEquals("1m 30s", ClientUtils.formatTimeLong(90));
    }

    @Test
    void formatTimeLongHours() {
        // Zero-value units are skipped: 3630s = 1h 0m 30s -> "1h 30s"
        assertEquals("1h 30s", ClientUtils.formatTimeLong(3630));
    }

    @Test
    void formatTimeLongDays() {
        String result = ClientUtils.formatTimeLong(86400 + 3661);
        assertTrue(result.startsWith("1d"));
    }

    // --- formatTimeShort ---

    @Test
    void formatTimeShortUnderMinute() {
        assertEquals("30", ClientUtils.formatTimeShort(30));
    }

    @Test
    void formatTimeShortMinutes() {
        assertEquals("1:30", ClientUtils.formatTimeShort(90));
    }

    @Test
    void formatTimeShortHours() {
        // time > 3600, divides by 60 first
        String result = ClientUtils.formatTimeShort(3661);
        assertNotNull(result);
        assertTrue(result.contains(":"));
    }

    // --- clipLine (Liang-Barsky) ---

    @Test
    void clipLineFullyInside() {
        Pair<Coord, Coord> result = ClientUtils.clipLine(
            Coord.of(5, 5), Coord.of(8, 8),
            Coord.of(0, 0), Coord.of(10, 10));
        assertNotNull(result);
        assertEquals(5, result.a.x);
        assertEquals(5, result.a.y);
        assertEquals(8, result.b.x);
        assertEquals(8, result.b.y);
    }

    @Test
    void clipLineFullyOutside() {
        Pair<Coord, Coord> result = ClientUtils.clipLine(
            Coord.of(20, 20), Coord.of(30, 30),
            Coord.of(0, 0), Coord.of(10, 10));
        assertNull(result);
    }

    @Test
    void clipLinePartiallyClipped() {
        Pair<Coord, Coord> result = ClientUtils.clipLine(
            Coord.of(5, 5), Coord.of(15, 5),
            Coord.of(0, 0), Coord.of(10, 10));
        assertNotNull(result);
        assertEquals(5, result.a.x);
        assertEquals(10, result.b.x);
    }

    // --- intersect ---

    @Test
    void intersectCrossing() {
        Optional<Coord2d> p = ClientUtils.intersect(
            new Pair<>(Coord2d.of(0, 0), Coord2d.of(10, 10)),
            new Pair<>(Coord2d.of(0, 10), Coord2d.of(10, 0)));
        assertTrue(p.isPresent());
        assertEquals(5.0, p.get().x, 0.01);
        assertEquals(5.0, p.get().y, 0.01);
    }

    @Test
    void intersectParallel() {
        Optional<Coord2d> p = ClientUtils.intersect(
            new Pair<>(Coord2d.of(0, 0), Coord2d.of(10, 0)),
            new Pair<>(Coord2d.of(0, 5), Coord2d.of(10, 5)));
        assertFalse(p.isPresent());
    }

    // --- prettyResName ---

    @Test
    void prettyResNameNull() {
        assertEquals("???", ClientUtils.prettyResName((String) null));
    }

    @Test
    void prettyResNameEmpty() {
        assertEquals("???", ClientUtils.prettyResName(""));
    }

    // prettyResName(String) triggers Config static init which requires
    // the full game environment, so we skip testing it here

    // --- num2value ---

    @Test
    void num2valueInteger() {
        Integer i = ClientUtils.num2value(3.14, Integer.class);
        assertEquals(3, i);
    }

    @Test
    void num2valueLong() {
        Long l = ClientUtils.num2value(42, Long.class);
        assertEquals(42L, l);
    }

    @Test
    void num2valueFloat() {
        Float f = ClientUtils.num2value(3.14, Float.class);
        assertEquals(3.14f, f, 0.01f);
    }

    // --- chainOptionals ---

    @Test
    @SuppressWarnings("unchecked")
    void chainOptionalsReturnsFirst() {
        Optional<String> result = ClientUtils.chainOptionals(
            () -> Optional.empty(),
            () -> Optional.of("second"),
            () -> Optional.of("third")
        );
        assertTrue(result.isPresent());
        assertEquals("second", result.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void chainOptionalsAllEmpty() {
        Optional<String> result = ClientUtils.chainOptionals(
            () -> Optional.empty(),
            () -> Optional.empty()
        );
        assertFalse(result.isPresent());
    }
}
