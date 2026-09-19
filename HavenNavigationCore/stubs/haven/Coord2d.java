package haven;

/** Compile-only coordinate stub. Not packaged in HavenNavigationCore.jar. */
public class Coord2d implements Comparable<Coord2d>, java.io.Serializable {
    public double x, y;
    public static final Coord2d z = new Coord2d(0, 0);

    public Coord2d(double x, double y) {
	this.x = x;
	this.y = y;
    }

    public Coord2d(Coord c) {
	this(c.x, c.y);
    }

    public Coord2d() {
	this(0, 0);
    }

    public static Coord2d of(double x, double y) { return(new Coord2d(x, y)); }
    public static Coord2d of(double x) { return(of(x, x)); }
    public static Coord2d of(Coord c) { return(of(c.x, c.y)); }

    public boolean equals(Object o) {
	if(!(o instanceof Coord2d))
	    return(false);
	Coord2d c = (Coord2d)o;
	return((x == c.x) && (y == c.y));
    }

    public int hashCode() {
	long X = Double.doubleToLongBits(x);
	long Y = Double.doubleToLongBits(y);
	return((((int)(X ^ (X >>> 32))) * 31) + ((int)(Y ^ (Y >>> 32))));
    }

    public int compareTo(Coord2d c) {
	if(c.y < y) return(-1);
	if(c.y > y) return(1);
	if(c.x < x) return(-1);
	if(c.x > y) return(1);
	return(0);
    }

    public Coord2d add(double X, double Y) {
	return(of(x + X, y + Y));
    }

    public Coord2d add(Coord2d b) {
	return(add(b.x, b.y));
    }

    public Coord2d sub(double X, double Y) {
	return(of(x - X, y - Y));
    }

    public Coord2d sub(Coord2d b) {
	return(sub(b.x, b.y));
    }

    public Coord2d mul(double f) {
	return(of(x * f, y * f));
    }

    public double dist(Coord2d o) {
	return(Math.hypot(x - o.x, y - o.y));
    }

    public double abs() {
	return(Math.hypot(x, y));
    }

    public Coord2d norm() {
	double a = abs();
	return(a == 0 ? of(0, 0) : of(x / a, y / a));
    }

    public Coord2d norm(double n) {
	return(norm().mul(n));
    }

    public String toString() {
	return("(" + x + ", " + y + ")");
    }
}
