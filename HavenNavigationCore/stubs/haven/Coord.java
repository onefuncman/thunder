package haven;

/** Compile-only coordinate stub. Not packaged in HavenNavigationCore.jar. */
public class Coord implements Comparable<Coord>, java.io.Serializable {
    public int x, y;
    public static Coord z = of(0, 0);

    public Coord(int x, int y) {
	this.x = x;
	this.y = y;
    }

    public Coord(Coord c) {
	this(c.x, c.y);
    }

    public Coord() {
	this(0, 0);
    }

    public static Coord of(int x, int y) { return(new Coord(x, y)); }
    public static Coord of(int x) { return(of(x, x)); }
    public static Coord of(Coord c) { return(of(c.x, c.y)); }

    public boolean equals(Object o) {
	if(!(o instanceof Coord))
	    return(false);
	Coord c = (Coord)o;
	return((c.x == x) && (c.y == y));
    }

    public int compareTo(Coord c) {
	if(c.y != y)
	    return(c.y - y);
	if(c.x != x)
	    return(c.x - x);
	return(0);
    }

    public int hashCode() {
	return(((y & 0xffff) * 31) + (x & 0xffff));
    }

    public String toString() {
	return("(" + x + ", " + y + ")");
    }
}
