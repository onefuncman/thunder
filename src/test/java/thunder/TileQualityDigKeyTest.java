package thunder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TileQualityDigKeyTest {
    @Test
    void diggableClaysClassify() {
	assertEquals("dig/ball-clay", TileQuality.digKeyForName("Ball Clay"));
	assertEquals("dig/acre-clay", TileQuality.digKeyForName("Acre Clay"));
	assertEquals("dig/river-clay", TileQuality.digKeyForName("River Clay"));
	assertEquals("dig/sea-clay", TileQuality.digKeyForName("Sea Clay"));
	assertEquals("dig/clay", TileQuality.digKeyForName("Clay"));
    }

    @Test
    void plainDigProduceClassifies() {
	assertEquals("dig/soil", TileQuality.digKeyForName("Soil"));
	assertEquals("dig/sand", TileQuality.digKeyForName("Sand"));
	assertEquals("dig/sand", TileQuality.digKeyForName("Sand, stack of"));
    }

    @Test
    void dirtIsACategoryNotAnItem() {
	assertNull(TileQuality.digKeyForName("Dirt"));
    }

    @Test
    void stackTitlesClassify() {
	assertEquals("dig/ball-clay", TileQuality.digKeyForName("Ball Clay, stack of"));
	assertEquals("dig/soil", TileQuality.digKeyForName("Soil, stack of"));
    }

    @Test
    void normalizationIsForgiving() {
	assertEquals("dig/ball-clay", TileQuality.digKeyForName("  ball clay "));
	assertEquals("dig/river-clay", TileQuality.digKeyForName("RIVER CLAY"));
    }

    @Test
    void nonDigItemsAreRejected() {
	assertNull(TileQuality.digKeyForName("Bream"));
	assertNull(TileQuality.digKeyForName("Earthworm"));
	assertNull(TileQuality.digKeyForName("Entrails"));
	assertNull(TileQuality.digKeyForName("Clay Pot"));
	assertNull(TileQuality.digKeyForName(""));
	assertNull(TileQuality.digKeyForName(null));
    }

    @Test
    void digDisplayNamesUseInGameNames() {
	assertEquals("Ball Clay", TileQuality.displayName("dig/ball-clay"));
	assertEquals("Acre Clay", TileQuality.displayName("dig/acre-clay"));
	assertEquals("River Clay", TileQuality.displayName("dig/river-clay"));
	assertEquals("Sea Clay", TileQuality.displayName("dig/sea-clay"));
	assertEquals("Soil", TileQuality.displayName("dig/soil"));
	assertEquals("Sand", TileQuality.displayName("dig/sand"));
	assertEquals("Dig", TileQuality.displayName("dig"));
	// interim res-slug keys recorded before name-based classification
	assertEquals("Ball Clay", TileQuality.displayName("dig/clay-gray"));
	assertEquals("Acre Clay", TileQuality.displayName("dig/clay-acre"));
    }
}
