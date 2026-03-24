package haven.proto;

public class ProtoEvent {
    public enum Direction { IN, OUT }
    public enum Category {
	WIDGET(java.awt.Color.BLUE, "Widget"),
	OBJECT(new java.awt.Color(0, 180, 0), "Object"),
	MAP(java.awt.Color.YELLOW, "Map"),
	SESSION(java.awt.Color.GRAY, "Session"),
	GLOB(new java.awt.Color(180, 0, 220), "Glob"),
	RESOURCE(java.awt.Color.CYAN, "Resource");

	public final java.awt.Color color;
	public final String label;

	Category(java.awt.Color color, String label) {
	    this.color = color;
	    this.label = label;
	}
    }

    public final double timestamp;
    public final Direction dir;
    public final Category category;
    public final String typeName;
    public final int typeId;
    public final String summary;
    public final String detail;
    public final int sizeBytes;
    public final long gobId;
    public final int widgetId;

    public ProtoEvent(double timestamp, Direction dir, Category category,
		      String typeName, int typeId, String summary, String detail,
		      int sizeBytes, long gobId, int widgetId) {
	this.timestamp = timestamp;
	this.dir = dir;
	this.category = category;
	this.typeName = typeName;
	this.typeId = typeId;
	this.summary = summary;
	this.detail = detail;
	this.sizeBytes = sizeBytes;
	this.gobId = gobId;
	this.widgetId = widgetId;
    }

    public static class Builder {
	private double timestamp;
	private Direction dir = Direction.IN;
	private Category category = Category.SESSION;
	private String typeName = "UNKNOWN";
	private int typeId = -1;
	private String summary = "";
	private String detail = "";
	private int sizeBytes = 0;
	private long gobId = -1;
	private int widgetId = -1;

	public Builder timestamp(double t) { this.timestamp = t; return this; }
	public Builder dir(Direction d) { this.dir = d; return this; }
	public Builder category(Category c) { this.category = c; return this; }
	public Builder typeName(String n) { this.typeName = n; return this; }
	public Builder typeId(int id) { this.typeId = id; return this; }
	public Builder summary(String s) { this.summary = s; return this; }
	public Builder detail(String d) { this.detail = d; return this; }
	public Builder sizeBytes(int s) { this.sizeBytes = s; return this; }
	public Builder gobId(long id) { this.gobId = id; return this; }
	public Builder widgetId(int id) { this.widgetId = id; return this; }

	public ProtoEvent build() {
	    return new ProtoEvent(timestamp, dir, category, typeName, typeId,
				  summary, detail, sizeBytes, gobId, widgetId);
	}
    }
}
