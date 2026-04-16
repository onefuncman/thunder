package haven.res.ui.croster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RecolorReportTest {

    @Test
    void summaryShowsDoneCount() {
	CattleRoster.RecolorReport r = new CattleRoster.RecolorReport(5, 0, 0, 5);
	r.recolored = 5;
	assertEquals("Recolor: 5/5 done.", r.summary());
    }

    @Test
    void summaryIncludesAlreadyColorSkips() {
	CattleRoster.RecolorReport r = new CattleRoster.RecolorReport(5, 2, 0, 3);
	r.recolored = 3;
	assertEquals("Recolor: 3/3 done, 2 already that color.", r.summary());
    }

    @Test
    void summaryIncludesOffscreen() {
	CattleRoster.RecolorReport r = new CattleRoster.RecolorReport(10, 0, 4, 6);
	r.recolored = 6;
	assertEquals("Recolor: 6/6 done, 4 not in render.", r.summary());
    }

    @Test
    void summaryIncludesWindowFailures() {
	CattleRoster.RecolorReport r = new CattleRoster.RecolorReport(3, 0, 0, 3);
	r.recolored = 1;
	r.windowMissed = 2;
	assertEquals("Recolor: 1/3 done, 2 window timeout.", r.summary());
    }

    @Test
    void summaryIncludesGrpFailures() {
	CattleRoster.RecolorReport r = new CattleRoster.RecolorReport(2, 0, 0, 2);
	r.recolored = 1;
	r.grpMissed = 1;
	assertEquals("Recolor: 1/2 done, 1 no color picker.", r.summary());
    }

    @Test
    void summaryCombinesAllFailureCategories() {
	CattleRoster.RecolorReport r = new CattleRoster.RecolorReport(10, 1, 2, 7);
	r.recolored = 4;
	r.windowMissed = 2;
	r.grpMissed = 1;
	assertEquals(
	    "Recolor: 4/7 done, 1 already that color, 2 not in render, 2 window timeout, 1 no color picker.",
	    r.summary());
    }

    @Test
    void summaryOmitsZeroedCategories() {
	CattleRoster.RecolorReport r = new CattleRoster.RecolorReport(0, 0, 0, 0);
	assertEquals("Recolor: 0/0 done.", r.summary());
    }
}
