package haven.proto;

import haven.*;
import java.awt.Color;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProtoInspector extends GameUI.Hidewnd {
    private final ProtoEventList eventList;
    private final Label statusLabel;
    private final Map<ProtoEvent.Category, CheckBox> categoryBoxes = new LinkedHashMap<>();
    private final CheckBox showIn, showOut;
    private final TextEntry searchBox;
    private final Button pauseBtn, clearBtn, recordBtn, bookmarkBtn;
    private final CheckBox retroBox, retroAutoBox;
    private final Button retroDumpBtn;
    private final Label retroStatus;
    private final CheckBox hideMovement, hideStats;

    // OBJDATA flag-only events dominated by OD_MOVE/OD_LINSTEP make up ~93% of recorded traffic;
    // an event is "movement noise" when *every* flag it carries is in this set.
    private static final Set<String> MOVEMENT_FLAGS = new HashSet<>(Arrays.asList(
	"OD_MOVE", "OD_LINSTEP", "OD_LINBEG", "OD_CMPEQU"));
    // WDGMSG names that are stat-bar / tooltip spam (~85% of RMSG_WDGMSG).
    private static final Set<String> STAT_WDGMSGS = new HashSet<>(Arrays.asList(
	"glut", "tip", "set", "tt", "prog", "max"));
    private static final Pattern OD_FLAG = Pattern.compile("OD_[A-Z]+");
    private static final Pattern WDGMSG_NAME = Pattern.compile("msg '([^']+)'");
    private boolean paused = false;
    private final Session sess;
    private Consumer<ProtoEvent> listener;
    private boolean captureHeld = false;
    private int eventCount = 0;
    private final RichTextBox detailBox;

    private static final int W = UI.scale(750);
    private static final int H = UI.scale(500);

    public ProtoInspector(Session sess) {
	super(new Coord(W, H), "Protocol Inspector");
	this.sess = sess;

	int y = 0;
	int x = 0;

	for(ProtoEvent.Category cat : ProtoEvent.Category.values()) {
	    CheckBox cb = add(new CheckBox(cat.label, false), x, y);
	    cb.a = true;
	    cb.changed(a -> applyFilters());
	    categoryBoxes.put(cat, cb);
	    x += cb.sz.x + UI.scale(8);
	}

	y += UI.scale(22);
	x = 0;

	showIn = add(new CheckBox("In", false), x, y);
	showIn.a = true;
	showIn.changed(a -> applyFilters());
	x += showIn.sz.x + UI.scale(8);

	showOut = add(new CheckBox("Out", false), x, y);
	showOut.a = true;
	showOut.changed(a -> applyFilters());
	x += showOut.sz.x + UI.scale(16);

	add(new Label("Search:"), x, y + UI.scale(2));
	x += UI.scale(45);
	searchBox = add(new TextEntry(UI.scale(150), "") {
	    @Override
	    public void changed(ReadLine buf) {
		applyFilters();
	    }
	}, x, y);
	x += UI.scale(158);

	pauseBtn = add(new Button(UI.scale(60), "Pause") {
	    public void click() {
		paused = !paused;
		this.change(paused ? "Resume" : "Pause");
	    }
	}, x, y);
	x += UI.scale(68);

	clearBtn = add(new Button(UI.scale(50), "Clear") {
	    public void click() {
		clearEvents();
	    }
	}, x, y);
	x += UI.scale(58);

	recordBtn = add(new Button(UI.scale(60), "Record") {
	    public void click() {
		toggleRecording();
	    }
	}, x, y);
	x += UI.scale(68);

	bookmarkBtn = add(new Button(UI.scale(70), "Bookmark") {
	    public void click() {
		addBookmark();
	    }
	}, x, y);

	y += UI.scale(24);
	x = 0;

	retroBox = add(new CheckBox("Retro capture", false), x, y);
	retroBox.changed(this::onRetroToggle);
	x += retroBox.sz.x + UI.scale(8);

	retroAutoBox = add(new CheckBox("Auto-dump (5m)", false), x, y);
	retroAutoBox.changed(this::onRetroAutoToggle);
	x += retroAutoBox.sz.x + UI.scale(8);

	retroDumpBtn = add(new Button(UI.scale(80), "Dump now") {
	    public void click() {
		dumpRetroNow();
	    }
	}, x, y);
	x += retroDumpBtn.sz.x + UI.scale(8);

	retroStatus = add(new Label(""), x, y + UI.scale(2));

	y += UI.scale(24);
	x = 0;

	add(new Label("Hide noise:"), x, y + UI.scale(2));
	x += UI.scale(70);

	hideMovement = add(new CheckBox("Movement", false), x, y);
	hideMovement.tooltip = Text.render("Hide OBJDATA events carrying only movement flags (OD_MOVE, OD_LINSTEP, OD_LINBEG, OD_CMPEQU). Events with OD_CMPPOSE or other flags remain visible.");
	hideMovement.changed(a -> applyFilters());
	x += hideMovement.sz.x + UI.scale(12);

	hideStats = add(new CheckBox("Stat updates", false), x, y);
	hideStats.tooltip = Text.render("Hide stat-bar / tooltip WDGMSGs (glut, tip, set, tt, prog, max).");
	hideStats.changed(a -> applyFilters());

	y += UI.scale(24);

	int listH = H - y - UI.scale(90);
	int itemH = new Text.Foundry(Text.mono, 10).height() + UI.scale(4);
	int visItems = listH / itemH;
	eventList = add(new ProtoEventList(W - UI.scale(10), visItems) {
	    @Override
	    public void changed(ProtoEvent item, int index) {
		onSelectionChanged(item, index);
	    }
	}, UI.scale(2), y);

	y += listH + UI.scale(4);

	detailBox = add(new RichTextBox(new Coord(W - UI.scale(10), UI.scale(70)), ""), UI.scale(2), y);
	y += UI.scale(74);

	statusLabel = add(new Label("Events: 0 | Rate: 0/s"), UI.scale(4), y);
    }

    private void onSelectionChanged(ProtoEvent item, int idx) {
	if(item != null)
	    detailBox.settext(item.detail);
	else
	    detailBox.settext("");
    }

    private void applyFilters() {
	if(sess.protoBus == null) return;
	List<ProtoEvent> all = sess.protoBus.getHistory();
	eventList.clear();
	String search = searchBox.buf.line().toLowerCase();
	for(ProtoEvent evt : all) {
	    if(matchesFilter(evt, search))
		eventList.addEvent(evt);
	}
    }

    private boolean matchesFilter(ProtoEvent evt, String search) {
	CheckBox catBox = categoryBoxes.get(evt.category);
	if(catBox != null && !catBox.a) return false;
	if(evt.dir == ProtoEvent.Direction.IN && !showIn.a) return false;
	if(evt.dir == ProtoEvent.Direction.OUT && !showOut.a) return false;
	if(hideMovement.a && isMovementOnly(evt)) return false;
	if(hideStats.a && isStatWdgmsg(evt)) return false;
	if(!search.isEmpty()) {
	    if(!evt.summary.toLowerCase().contains(search) &&
	       !evt.typeName.toLowerCase().contains(search))
		return false;
	}
	return true;
    }

    private static boolean isMovementOnly(ProtoEvent evt) {
	if(!"OBJDATA".equals(evt.typeName)) return false;
	Matcher m = OD_FLAG.matcher(evt.summary);
	boolean anyFlag = false;
	while(m.find()) {
	    anyFlag = true;
	    if(!MOVEMENT_FLAGS.contains(m.group())) return false;
	}
	return anyFlag;
    }

    private static boolean isStatWdgmsg(ProtoEvent evt) {
	if(!"RMSG_WDGMSG".equals(evt.typeName)) return false;
	Matcher m = WDGMSG_NAME.matcher(evt.summary);
	return m.find() && STAT_WDGMSGS.contains(m.group(1));
    }

    public void clearEvents() {
	eventList.clear();
	eventCount = 0;
	if(sess.protoBus != null)
	    sess.protoBus.clearHistory();
	detailBox.settext("");
    }

    public void setPaused(boolean p) {
	this.paused = p;
	pauseBtn.change(paused ? "Resume" : "Pause");
    }

    private void toggleRecording() {
	if(sess.protoBus == null) return;
	EnhancedRecorder rec = sess.protoBus.recorder;
	if(rec.isRecording()) {
	    rec.stop();
	    recordBtn.change("Record");
	} else {
	    try {
		Path dir = Debug.somedir("proto-recordings");
		java.io.File dirFile = dir.toFile();
		if(!dirFile.exists()) dirFile.mkdirs();
		String filename = String.format("proto-%tY%<tm%<td-%<tH%<tM%<tS.rec", new java.util.Date());
		Path path = dir.resolve(filename);
		rec.start(path);
		recordBtn.change("Stop");
	    } catch(Exception e) {
		e.printStackTrace(Debug.log);
	    }
	}
    }

    private void onRetroToggle(boolean on) {
	if(sess.protoBus == null) return;
	sess.protoBus.retro.setEnabled(on);
	if(!on && retroAutoBox.a) {
	    retroAutoBox.a = false;
	}
    }

    private void onRetroAutoToggle(boolean on) {
	if(sess.protoBus == null) return;
	if(on && !sess.protoBus.retro.isEnabled()) {
	    retroAutoBox.a = false;
	    return;
	}
	sess.protoBus.retro.setAutoDump(on);
    }

    private void dumpRetroNow() {
	if(sess.protoBus == null) return;
	try {
	    Path path = sess.protoBus.retro.dumpNow();
	    retroStatus.settext("Wrote " + path.getFileName());
	} catch(Exception e) {
	    retroStatus.settext("Dump failed: " + e.getMessage());
	}
    }

    private void addBookmark() {
	if(sess.protoBus == null) return;
	EnhancedRecorder rec = sess.protoBus.recorder;
	if(rec.isRecording()) {
	    rec.bookmark("Manual bookmark at " + String.format("%.2f", Utils.rtime()));
	}
    }

    @Override
    public void show() {
	super.show();
	if(sess.protoBus != null) {
	    if(!captureHeld) {
		sess.protoBus.acquireCapture();
		captureHeld = true;
	    }
	    if(listener == null) {
		listener = this::onEvent;
		sess.protoBus.addListener(listener);
	    }
	    retroBox.a = sess.protoBus.retro.isEnabled();
	    retroAutoBox.a = sess.protoBus.retro.isAutoDump();
	}
    }

    @Override
    public void hide() {
	super.hide();
	releaseCapture();
    }

    private void releaseCapture() {
	if(sess.protoBus != null) {
	    if(listener != null) {
		sess.protoBus.removeListener(listener);
		listener = null;
	    }
	    if(captureHeld) {
		sess.protoBus.releaseCapture();
		captureHeld = false;
	    }
	}
    }

    private void onEvent(ProtoEvent evt) {
	if(paused) return;
	eventCount++;
	String search = searchBox.buf.line().toLowerCase();
	if(matchesFilter(evt, search))
	    eventList.addEvent(evt);
    }

    @Override
    public void tick(double dt) {
	super.tick(dt);
	if(sess.protoBus != null) {
	    ProtoStats stats = sess.protoBus.stats;
	    String recStatus = sess.protoBus.recorder.isRecording() ? " | REC" : "";
	    RetroCapture retro = sess.protoBus.retro;
	    String retroTxt = "";
	    if(retro.isEnabled()) {
		retroTxt = String.format(" | retro: %d ev%s", retro.size(), retro.isAutoDump() ? " auto" : "");
	    }
	    statusLabel.settext(String.format("Events: %d | Rate: %.0f/s | BW: %.1f KB/s | Total: %d%s%s",
					      eventCount, stats.getRate(), stats.getBandwidth() / 1024.0, stats.getTotalMessages(), recStatus, retroTxt));
	}
    }

    @Override
    public void destroy() {
	releaseCapture();
	super.destroy();
    }

    @Override
    public void resize(Coord sz) {
	super.resize(sz);
	if(eventList != null) {
	    int listY = UI.scale(48);
	    int detailH = UI.scale(70);
	    int statusH = UI.scale(20);
	    int listH = sz.y - listY - detailH - statusH - UI.scale(12);
	    eventList.resize(new Coord(sz.x - UI.scale(10), listH));
	    detailBox.c = new Coord(UI.scale(2), listY + listH + UI.scale(4));
	    detailBox.resize(new Coord(sz.x - UI.scale(10), detailH));
	    statusLabel.c = new Coord(UI.scale(4), sz.y - statusH);
	}
    }
}
