package haven.automated.pathfinder;

/**
 * Graph edge for the A* visibility graph.
 * Represents a direct traversable connection between two vertices with an associated cost.
 */
public class Edge {
    public final Vertex src;
    public final Vertex dest;
    public double weight;

    public Edge(Vertex src, Vertex dest, double w) {
        this.src = src;
        this.dest = dest;
        this.weight = w;
    }
}