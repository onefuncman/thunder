package haven;

import org.junit.jupiter.api.Test;
import java.awt.Color;
import static org.junit.jupiter.api.Assertions.*;

public class AstronomyTest {

    private Astronomy make(double dt, double mp, double yt, boolean night, int is, double sp, double sd, double years, double ym, double md) {
        return new Astronomy(dt, mp, yt, night, Color.WHITE, is, sp, sd, years, ym, md);
    }

    // --- Season enum ---

    @Test
    void seasonLengths() {
        assertEquals(30, Astronomy.Season.Spring.length);
        assertEquals(105, Astronomy.Season.Summer.length);
        assertEquals(30, Astronomy.Season.Autumn.length);
        assertEquals(15, Astronomy.Season.Winter.length);
    }

    @Test
    void yearLength() {
        assertEquals(180, Astronomy.Season.yearLength());
    }

    @Test
    void seasonValues() {
        Astronomy.Season[] seasons = Astronomy.Season.values();
        assertEquals(4, seasons.length);
        assertEquals(Astronomy.Season.Spring, seasons[0]);
        assertEquals(Astronomy.Season.Summer, seasons[1]);
        assertEquals(Astronomy.Season.Autumn, seasons[2]);
        assertEquals(Astronomy.Season.Winter, seasons[3]);
    }

    // --- Moon phases ---

    @Test
    void moonPhaseNames() {
        assertEquals(8, Astronomy.phase.length);
        assertEquals("New Moon", Astronomy.phase[0]);
        assertEquals("Waxing Crescent", Astronomy.phase[1]);
        assertEquals("First Quarter", Astronomy.phase[2]);
        assertEquals("Waxing Gibbous", Astronomy.phase[3]);
        assertEquals("Full Moon", Astronomy.phase[4]);
        assertEquals("Waning Gibbous", Astronomy.phase[5]);
        assertEquals("Last Quarter", Astronomy.phase[6]);
        assertEquals("Waning Crescent", Astronomy.phase[7]);
    }

    // --- Constructor calculations ---

    @Test
    void hourAndMinute() {
        // dt=0.5 -> 12:00 (noon)
        Astronomy a = make(0.5, 0, 0, false, 0, 0, 0, 0, 0, 0);
        assertEquals(12, a.hh);
        assertEquals(0, a.mm);
    }

    @Test
    void hourAndMinuteQuarter() {
        // dt=0.25 -> 6:00
        Astronomy a = make(0.25, 0, 0, false, 0, 0, 0, 0, 0, 0);
        assertEquals(6, a.hh);
        assertEquals(0, a.mm);
    }

    @Test
    void hourAndMinuteOffset() {
        // dt=0.51 -> 12:14 (24*0.51=12.24, mm = int(60*0.24) = 14)
        Astronomy a = make(0.51, 0, 0, false, 0, 0, 0, 0, 0, 0);
        assertEquals(12, a.hh);
        assertEquals(14, a.mm);
    }

    @Test
    void dayOfYear() {
        // yt=0.5 -> day 90 (half of 180)
        Astronomy a = make(0, 0, 0.5, false, 0, 0, 0, 0, 0, 0);
        assertEquals(90, a.day);
    }

    @Test
    void dayOfYearZero() {
        Astronomy a = make(0, 0, 0, false, 0, 0, 0, 0, 0, 0);
        assertEquals(0, a.day);
    }

    @Test
    void seasonMethod() {
        Astronomy spring = make(0, 0, 0, false, 0, 0, 0, 0, 0, 0);
        assertEquals(Astronomy.Season.Spring, spring.season());

        Astronomy summer = make(0, 0, 0, false, 1, 0, 0, 0, 0, 0);
        assertEquals(Astronomy.Season.Summer, summer.season());

        Astronomy autumn = make(0, 0, 0, false, 2, 0, 0, 0, 0, 0);
        assertEquals(Astronomy.Season.Autumn, autumn.season());

        Astronomy winter = make(0, 0, 0, false, 3, 0, 0, 0, 0, 0);
        assertEquals(Astronomy.Season.Winter, winter.season());
    }

    @Test
    void nightFlag() {
        Astronomy day = make(0, 0, 0, false, 0, 0, 0, 0, 0, 0);
        assertFalse(day.night);

        Astronomy night = make(0, 0, 0, true, 0, 0, 0, 0, 0, 0);
        assertTrue(night.night);
    }

    @Test
    void fieldsStored() {
        Astronomy a = make(0.5, 0.25, 0.75, true, 2, 0.3, 0.4, 5.0, 0.1, 0.2);
        assertEquals(0.5, a.dt, 1e-10);
        assertEquals(0.25, a.mp, 1e-10);
        assertEquals(0.75, a.yt, 1e-10);
        assertTrue(a.night);
        assertEquals(Color.WHITE, a.mc);
        assertEquals(2, a.is);
        assertEquals(0.3, a.sp, 1e-10);
        assertEquals(0.4, a.sd, 1e-10);
        assertEquals(5.0, a.years, 1e-10);
        assertEquals(0.1, a.ym, 1e-10);
        assertEquals(0.2, a.md, 1e-10);
    }

    @Test
    void seasonDayComputed() {
        // is=0 (Spring, length=30), sp=0.5 -> scday = 15
        Astronomy a = make(0, 0, 0, false, 0, 0.5, 0, 0, 0, 0);
        assertEquals(15, a.scday);
    }

    @Test
    void seasonRemainingComputed() {
        // is=0 (Spring, length=30), sp=0.0 -> full season remaining
        Astronomy a = make(0, 0, 0, false, 0, 0.0, 0, 0, 0, 0);
        assertEquals(0, a.scday);
        assertEquals(30, a.srday);
    }

    @Test
    void seasonRemainingAtEnd() {
        // sp=1.0 -> 0 remaining
        Astronomy a = make(0, 0, 0, false, 0, 1.0, 0, 0, 0, 0);
        assertEquals(30, a.scday);
        assertEquals(0, a.srday);
        assertEquals(0, a.srhh);
        assertEquals(0, a.srmm);
    }

    @Test
    void midnight() {
        Astronomy a = make(0.0, 0, 0, true, 0, 0, 0, 0, 0, 0);
        assertEquals(0, a.hh);
        assertEquals(0, a.mm);
    }

    @Test
    void endOfDay() {
        // dt just under 1.0 -> 23:59
        Astronomy a = make(0.999, 0, 0, false, 0, 0, 0, 0, 0, 0);
        assertEquals(23, a.hh);
        assertEquals(58, a.mm);
    }
}
