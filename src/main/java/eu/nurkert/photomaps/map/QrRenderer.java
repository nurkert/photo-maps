package eu.nurkert.photomaps.map;

import eu.nurkert.photomaps.Messages;
import io.nayuki.qrcodegen.QrCode;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.IntFunction;
import javax.imageio.ImageIO;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;

/**
 * Draws a photo code map: the QR code of its upload page while a session is open, otherwise the photo code icon and
 * a hint to right-click it. Drawn per player: the hint is in the player's language, and only the code's owner sees the
 * QR code. Everyone else (a copy of the map, a map in an item frame) sees that it is not theirs, because the QR code
 * is the key to the owner's upload page.
 */
public final class QrRenderer extends MapRenderer {

    /** Creates the renderer of a photo code map. */
    @FunctionalInterface
    public interface Factory {
        QrRenderer create(int mapId);
    }

    private static final int SIZE = PictureStore.SIZE;
    private static BufferedImage icon;

    private final int mapId;
    private final IntFunction<String> urls;
    private final IntFunction<UUID> owners;
    private final Palette palette;
    // what each player's canvas currently shows, so it is only drawn again when that changes; keyed like the server
    // keys the canvases, by player object, so a player who rejoins gets drawn on the fresh canvas
    private final Map<Player, String> shown = new WeakHashMap<>();

    /**
     * @param urls the upload page of a photo code map, {@code null} while it has no open session
     * @param owners the owner of a photo code map
     */
    public QrRenderer(int mapId, IntFunction<String> urls, IntFunction<UUID> owners, Palette palette) {
        super(true);
        this.mapId = mapId;
        this.urls = urls;
        this.owners = owners;
        this.palette = palette;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        UUID owner = owners.apply(mapId);
        boolean mine = player.getUniqueId().equals(owner);
        String url = mine ? urls.apply(mapId) : null;
        String state = mine + "|" + url + "|" + Messages.get(player, "map.scan");
        if (Objects.equals(shown.get(player), state)) {
            return;
        }
        shown.put(player, state);
        if (url != null) {
            drawCode(canvas, url, Messages.get(player, "map.scan"));
        } else if (owner != null && !mine) {
            drawInactive(canvas, Messages.get(player, "map.foreign-1"), Messages.get(player, "map.foreign-2"));
        } else {
            drawInactive(canvas, Messages.get(player, "map.inactive-1"), Messages.get(player, "map.inactive-2"));
        }
    }

    private void drawCode(MapCanvas canvas, String url, String caption) {
        byte white = palette.nearest(255, 255, 255);
        byte black = palette.nearest(0, 0, 0);
        fill(canvas, white);

        QrCode qr = QrCode.encodeText(url, QrCode.Ecc.MEDIUM);
        int border = 2;
        int scale = Math.max(1, SIZE / (qr.size + 2 * border));
        int width = qr.size * scale;
        int captionHeight = MinecraftFont.Font.getHeight() + 3;
        boolean withCaption = SIZE - (qr.size + 2 * border) * scale >= captionHeight;
        int left = (SIZE - width) / 2;
        int top = withCaption ? (SIZE - captionHeight - width) / 2 : (SIZE - width) / 2;
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.getModule(x, y)) {
                    rect(canvas, left + x * scale, top + y * scale, scale, scale, black);
                }
            }
        }
        if (withCaption) {
            text(canvas, caption, top + width + border * scale);
        }
    }

    private void drawInactive(MapCanvas canvas, String line1, String line2) {
        fill(canvas, palette.nearest(232, 230, 222));
        BufferedImage image = icon();
        if (image != null) {
            // the 16x16 icon, 4 map pixels per icon pixel
            int step = image.getWidth() / 16;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int argb = image.getRGB(x * step, y * step);
                    if ((argb >>> 24) >= 128) {
                        byte color = palette.nearest(argb >> 16 & 0xFF, argb >> 8 & 0xFF, argb & 0xFF);
                        rect(canvas, 32 + x * 4, 16 + y * 4, 4, 4, color);
                    }
                }
            }
        }
        text(canvas, line1, 88);
        text(canvas, line2, 88 + MinecraftFont.Font.getHeight() + 2);
    }

    private static void text(MapCanvas canvas, String text, int y) {
        if (!MinecraftFont.Font.isValid(text)) {
            return;
        }
        int x = Math.max(0, (SIZE - MinecraftFont.Font.getWidth(text)) / 2);
        canvas.drawText(x, y, MinecraftFont.Font, text);
    }

    private static void fill(MapCanvas canvas, byte color) {
        rect(canvas, 0, 0, SIZE, SIZE, color);
    }

    @SuppressWarnings("deprecation") // setPixel draws an exact palette colour
    private static void rect(MapCanvas canvas, int left, int top, int width, int height, byte color) {
        for (int y = top; y < top + height; y++) {
            for (int x = left; x < left + width; x++) {
                canvas.setPixel(x, y, color);
            }
        }
    }

    private static synchronized BufferedImage icon() {
        if (icon == null) {
            try (InputStream in = QrRenderer.class.getResourceAsStream("/web/icon.png")) {
                icon = in != null ? ImageIO.read(in) : null;
            } catch (IOException exception) {
                icon = null;
            }
        }
        return icon;
    }
}
