package haven.automated.helpers;

import haven.Coord;

/**
 * Callback invoked when the user finishes a drag-select area on the game map.
 *
 * Used by bots and scripts that need the player to define a rectangular region
 * interactively. The client's map-view drag-select mechanism delivers the two
 * corners once the mouse is released.
 */
public interface AreaSelectCallback {
    void areaselect(Coord a, Coord b);
}
