package haven;

import haven.render.*;

import java.awt.*;
import java.util.*;

public class ToggleRadius extends Sprite {
    private static final Map<CFG<Boolean>, Collection<ToggleRadius>> byToggle = new HashMap<>();

    private final ColoredRadius circle;
    private final CFG<Boolean> toggle;
    private final Collection<RenderTree.Slot> slots = new ArrayList<>(1);

    public ToggleRadius(Owner owner, float r, Color scol, Color ecol, CFG<Boolean> toggle) {
	super(owner, null);
	this.toggle = toggle;
	Gob gob = owner.context(Gob.class);
	circle = new ColoredRadius(gob, r, scol, ecol);
	synchronized(byToggle) {
	    byToggle.computeIfAbsent(toggle, k -> {
		k.observe(cfg -> showAll(k, cfg.get()));
		return new WeakList<>();
	    }).add(this);
	}
    }

    private static void showAll(CFG<Boolean> toggle, boolean show) {
	Collection<ToggleRadius> list;
	synchronized(byToggle) {
	    list = byToggle.get(toggle);
	}
	if(list == null) return;
	for(ToggleRadius tr : list)
	    tr.show(show);
    }

    private void show(boolean show) {
	if(show) {
	    Loading.waitfor(() -> RUtils.multiadd(slots, circle));
	} else {
	    for(RenderTree.Slot slot : slots)
		slot.clear();
	}
    }

    public void added(RenderTree.Slot slot) {
	if(toggle.get())
	    slot.add(circle);
	slots.add(slot);
    }

    public void removed(RenderTree.Slot slot) {
	slots.remove(slot);
    }

    @Override
    public void gtick(Render g) {
	circle.gtick(g);
    }
}
