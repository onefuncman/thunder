package haven;

import me.ender.ui.CFGBox;
import me.ender.ui.ScreenPosGrid;
import me.ender.ui.SizeRow;

public class NoticeMsgCfgWnd extends WindowX {
    private static Window instance;

    public static void toggle(Widget parent) {
	if(instance == null) {
	    instance = parent.add(new NoticeMsgCfgWnd());
	} else {
	    doClose();
	}
    }

    private static void doClose() {
	if(instance != null) {
	    instance.reqdestroy();
	    instance = null;
	}
    }

    public NoticeMsgCfgWnd() {
	super(Coord.z, "Notice message");
	justclose = true;

	int y = 0;
	Widget prev = add(new CFGBox("Use custom position/size below (off = default look)", CFG.ERROR_MSG_CUSTOM_ENABLED), 0, y);
	y = prev.pos("bl").y + UI.scale(10);

	prev = add(new Label("Where action-failure messages (\"Too hard to mine\", etc.) appear:"), 0, y);
	y = prev.pos("bl").y + UI.scale(5);
	Widget grid = add(new ScreenPosGrid(UI.scale(16), UI.scale(2), CFG.ERROR_MSG_POS), 0, y);
	y = grid.pos("bl").y + UI.scale(10);

	prev = add(new Label("Text size:"), 0, y);
	y = prev.pos("bl").y + UI.scale(5);
	Widget sizes = add(new SizeRow(CFG.ERROR_MSG_SIZE), 0, y);
	y = sizes.pos("bl").y + UI.scale(10);

	add(new Button(UI.scale(150), "Send test message", () -> {
	    GameUI gui = getparent(GameUI.class);
	    if(gui != null)
		gui.error("Too hard to mine.");
	}), 0, y);

	pack();
    }
}
