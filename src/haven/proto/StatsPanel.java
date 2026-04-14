package haven.proto;

import haven.*;
import java.awt.Color;
import java.util.*;

public class StatsPanel extends GameUI.Hidewnd {
    private static final int W = UI.scale(400);
    private static final int H = UI.scale(350);
    private static final Text.Foundry fnd = new Text.Foundry(Text.mono, 10);
    private static final Text.Foundry fndb = new Text.Foundry(Text.monobold, 11);
    private static final int LINEH = fnd.height() + UI.scale(2);
    private final Session sess;
    private final Label rateLabel;
    private final Label bwLabel;
    private final Label totalLabel;
    private boolean captureHeld = false;

    public StatsPanel(Session sess) {
	super(new Coord(W, H), "Protocol Stats");
	this.sess = sess;

	int y = 0;
	rateLabel = add(new Label("Rate: 0/s", fndb), UI.scale(4), y);
	y += UI.scale(18);
	bwLabel = add(new Label("Bandwidth: 0 KB/s", fndb), UI.scale(4), y);
	y += UI.scale(18);
	totalLabel = add(new Label("Total: 0 msgs, 0 KB", fnd), UI.scale(4), y);
    }

    @Override
    public void show() {
	super.show();
	if(sess.protoBus != null && !captureHeld) {
	    sess.protoBus.acquireCapture();
	    captureHeld = true;
	}
    }

    @Override
    public void hide() {
	super.hide();
	releaseCapture();
    }

    @Override
    public void destroy() {
	releaseCapture();
	super.destroy();
    }

    private void releaseCapture() {
	if(captureHeld && sess.protoBus != null) {
	    sess.protoBus.releaseCapture();
	    captureHeld = false;
	}
    }

    @Override
    public void tick(double dt) {
	super.tick(dt);
	if(sess.protoBus == null) return;
	ProtoStats stats = sess.protoBus.stats;
	rateLabel.settext(String.format("Rate: %.0f msgs/s", stats.getRate()));
	bwLabel.settext(String.format("Bandwidth: %.1f KB/s", stats.getBandwidth() / 1024.0));
	totalLabel.settext(String.format("Total: %d msgs, %.1f KB", stats.getTotalMessages(), stats.getTotalBytes() / 1024.0));
    }

    @Override
    public void draw(GOut g) {
	super.draw(g);
	if(sess.protoBus == null) return;
	ProtoStats stats = sess.protoBus.stats;

	int tableY = UI.scale(60);
	int x = UI.scale(4);

	g.chcolor(new Color(100, 180, 255));
	g.text("Type", new Coord(x, tableY));
	g.text("Rate", new Coord(x + UI.scale(250), tableY));
	g.chcolor();
	tableY += LINEH + UI.scale(2);

	Map<String, Double> rates = stats.getTypeRates();
	List<Map.Entry<String, Double>> sorted = new ArrayList<>(rates.entrySet());
	sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

	int maxRows = (sz.y - tableY - UI.scale(80)) / LINEH;
	int row = 0;
	for(Map.Entry<String, Double> e : sorted) {
	    if(row >= maxRows) break;
	    g.chcolor(Color.WHITE);
	    g.text(e.getKey(), new Coord(x, tableY));
	    g.chcolor(new Color(200, 200, 200));
	    g.text(String.format("%.0f/s", e.getValue()), new Coord(x + UI.scale(250), tableY));
	    g.chcolor();
	    tableY += LINEH;
	    row++;
	}

	// Sparkline
	int sparkY = sz.y - UI.scale(70);
	int sparkH = UI.scale(50);
	int sparkW = sz.x - UI.scale(16);
	int sparkX = UI.scale(8);

	g.chcolor(new Color(30, 30, 30, 200));
	g.frect(new Coord(sparkX, sparkY), new Coord(sparkW, sparkH));

	double[] history = stats.getRateHistory();
	double max = 1;
	for(double v : history) max = Math.max(max, v);

	g.chcolor(new Color(0, 200, 100, 180));
	for(int i = 1; i < history.length; i++) {
	    int x1 = sparkX + (i - 1) * sparkW / history.length;
	    int x2 = sparkX + i * sparkW / history.length;
	    int y1 = sparkY + sparkH - (int)(history[i - 1] / max * sparkH);
	    int y2 = sparkY + sparkH - (int)(history[i] / max * sparkH);
	    g.line(new Coord(x1, y1), new Coord(x2, y2), 1.5);
	}

	g.chcolor(new Color(140, 140, 140));
	g.text("60s", new Coord(sparkX, sparkY + sparkH + UI.scale(2)));
	g.text(String.format("%.0f", max), new Coord(sparkX + sparkW - UI.scale(30), sparkY - LINEH));
	g.chcolor();
    }
}
