package haven.combat.log;

/**
 * The four combat opening values, 0-100.
 *
 * Order is fixed and load-bearing: green, blue, yellow, red - matching
 * paginae/atk/{offbalance,dizzy,reeling,cornered}. Analysis code indexes by position.
 */
public final class Openings {
    public static final Openings ZERO = new Openings(0, 0, 0, 0);

    public final int green, blue, yellow, red;

    public Openings(int green, int blue, int yellow, int red) {
        this.green = green;
        this.blue = blue;
        this.yellow = yellow;
        this.red = red;
    }

    public String toJson() {
        return("[" + green + "," + blue + "," + yellow + "," + red + "]");
    }

    public boolean equals(Object o) {
        if(!(o instanceof Openings))
            return(false);
        Openings x = (Openings)o;
        return(x.green == green && x.blue == blue && x.yellow == yellow && x.red == red);
    }

    public int hashCode() {
        return(((green * 101 + blue) * 101 + yellow) * 101 + red);
    }
}
