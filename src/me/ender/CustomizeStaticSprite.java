package me.ender;

import haven.CFG;
import haven.Coord3f;
import haven.Gob;
import haven.StaticSprite;
import haven.render.Location;
import haven.render.RenderTree;

public class CustomizeStaticSprite {
    public static void added(StaticSprite sprite, RenderTree.Slot slot) {
	/* The parchment decal resource is both the overlay a cupboard wears and
	 * the gob of a decal lying on the ground; only the former has an Overlay
	 * owner (the latter's owner is the gob's ResDrawable). */
	try {
	    if (CFG.DISPLAY_DECALS_ON_TOP.get()
		&& sprite.res.name.equals(ResName.PARCHMENT_DECAL)
		&& sprite.owner instanceof Gob.Overlay
		&& ((Gob.Overlay)sprite.owner).gob.getres().name.equals(ResName.CUPBOARD))
	    {
		slot.cstate(Location.xlate(new Coord3f(-5,-5,17.5f)));
	    }
	} catch (Exception ignored) {
	}
    }
}
