package haven.automated.pathfinder;

import java.util.HashSet;
import java.util.Set;

/**
 * Graph vertex for the A* visibility graph.
 * Represents a navigable point in the pathfinding grid with edges to other vertices.
 */
public class Vertex {
    public final int x;
    public final int y;
    public final Set<Edge> edges = new HashSet<Edge>();

    public Vertex(int x, int y) {
        this.x = x;
        this.y = y;
    }
}