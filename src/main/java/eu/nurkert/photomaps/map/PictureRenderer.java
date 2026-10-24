package eu.nurkert.photomaps.map;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * Draws one part of a picture. The canvas is shared by all players and keeps its pixels, so it is drawn only once.
 */
final class PictureRenderer extends MapRenderer {

    private final byte[] pixels;
    private boolean drawn;

    PictureRenderer(byte[] pixels) {
        super(false);
        this.pixels = pixels;
    }

    @Override
    @SuppressWarnings("deprecation") // setPixel keeps the exact dithered colour, setPixelColor would match it again
    public void render(MapView map, MapCanvas canvas, Player player) {
        if (drawn) {
            return;
        }
        for (int y = 0; y < PictureStore.SIZE; y++) {
            for (int x = 0; x < PictureStore.SIZE; x++) {
                canvas.setPixel(x, y, pixels[y * PictureStore.SIZE + x]);
            }
        }
        drawn = true;
    }
}
