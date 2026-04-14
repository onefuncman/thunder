package me.ender;

import haven.*;
import me.ender.ui.CFGBox;
import me.ender.ui.CFGSlider;

import java.util.Set;

public class GobInfoOpts extends WindowX {
    
    public static final int PAD = UI.scale(5);
    
    public enum InfoPart {
	PLANT_GROWTH("Plant growth"),
	TREE_GROWTH("Tree growth"),
	TREE_CONTENTS("Tree contents"),
	ANIMAL_FLEECE("Shearable animals"),
	COOPS("Coop Needs"),
	TROUGH("Food troughs"),
	GARDEN_POT("Garden pots"),
	HEALTH("Object health"),
	BARREL("Barrel contents"),
	DISPLAY_SIGN("Display sign contents"),
	CHEESE_RACK("Cheese rack contents"),
	QUALITY("Quality"),
	TIMER("Timer");
	
	public final String text;
	
	InfoPart(String text) {this.text = text;}
    }
    
    public enum TreeSubPart {
	SEEDS("Seeds"),
	LEAVES("Leaves"),
	BARK("Bark"),
	BOUGH("Bough");
	
	public final String text;
	
	TreeSubPart(String text) {this.text = text;}
    }
    
    public GobInfoOpts() {
	super(Coord.z, "Gob info settings");
	justclose = true;
	
	Set<InfoPart> selected = CFG.DISPLAY_GOB_INFO_DISABLED_PARTS.get();
	Composer composer = new Composer(this).vmrgn(PAD);
	composer.add(new Label("Types of info to display:"));
	composer.hpad(2 * PAD);
	for (InfoPart cat : InfoPart.values()) {
	    CheckBox box = composer.add(new CheckBox(cat.text, false));
	    box.a = !selected.contains(cat);
	    box.changed(val -> {
		boolean changed;
		Set<InfoPart> categories = CFG.DISPLAY_GOB_INFO_DISABLED_PARTS.get();
		if(val) {
		    changed = categories.remove(cat);
		} else {
		    changed = categories.add(cat);
		}
		if(changed) {
		    CFG.DISPLAY_GOB_INFO_DISABLED_PARTS.set(categories);
		}
	    });
	}
	composer.y(0).hpad(UI.scale(150));
	composer.add(new Label("Options:"));
	composer.hpad(composer.hpad() + 2 * PAD);
	composer.add(new CFGBox("Shorten text of the object contents", CFG.DISPLAY_GOB_INFO_SHORT, "Will remove some not very relevant parts of the contents name", true));
	composer.add(new CFGBox("Hide tree contents for growing trees", CFG.DISPLAY_GOB_INFO_TREE_HIDE_GROWING_PARTS, "Will not show seeds/leaves/etc. for not fully grown trees", true));
	composer.add(new CFGBox("Show scale for big trees", CFG.DISPLAY_GOB_INFO_TREE_SHOW_BIG, "Will not show scale for big enough trees", true));
	composer.add(new CFGSlider(UI.scale(140), 101, 200, CFG.DISPLAY_GOB_INFO_TREE_SHOW_BIG_THRESHOLD, composer.add(new Label("")), "Big tree threshold: %d%%"));
	
	composer.hpad(composer.hpad() - 2 * PAD);
	composer.add(new Label("Tree parts:"));
	composer.hpad(composer.hpad() + 2 * PAD);
	Set<TreeSubPart> selectedTreeParts = CFG.DISPLAY_GOB_INFO_TREE_ENABLED_PARTS.get();
	for (TreeSubPart cat : TreeSubPart.values()) {
	    CheckBox box = composer.add(new CheckBox(cat.text, false));
	    box.a = selectedTreeParts.contains(cat);
	    box.changed(val -> {
		boolean changed;
		Set<TreeSubPart> categories = CFG.DISPLAY_GOB_INFO_TREE_ENABLED_PARTS.get();
		if(val) {
		    changed = categories.add(cat);
		} else {
		    changed = categories.remove(cat);
		}
		if(changed) {
		    CFG.DISPLAY_GOB_INFO_TREE_ENABLED_PARTS.set(categories);
		}
	    });
	}
	
	pack();
    }
    
    private static Window instance;
    
    public static void toggle(Widget parent) {
	if(instance == null) {
	    instance = parent.add(new GobInfoOpts(), 200, 100);
	} else {
	    instance.reqdestroy();
	    instance = null;
	}
    }
    
    @Override
    public void destroy() {
	super.destroy();
	instance = null;
    }
    
    public static boolean enabled(InfoPart part) {return !CFG.DISPLAY_GOB_INFO_DISABLED_PARTS.get().contains(part);}
    
    public static boolean disabled(InfoPart part) {return CFG.DISPLAY_GOB_INFO_DISABLED_PARTS.get().contains(part);}
    
    public static boolean enabled(TreeSubPart part) {return CFG.DISPLAY_GOB_INFO_TREE_ENABLED_PARTS.get().contains(part);}
    
    public static void toggle(InfoPart part) {
	Set<InfoPart> parts = CFG.DISPLAY_GOB_INFO_DISABLED_PARTS.get();
	if(parts.contains(part)) {
	    parts.remove(part);
	} else {
	    parts.add(part);
	}
	CFG.DISPLAY_GOB_INFO_DISABLED_PARTS.set(parts);
    }
}
