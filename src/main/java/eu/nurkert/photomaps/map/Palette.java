package eu.nurkert.photomaps.map;

import java.awt.Color;
import java.util.Arrays;

/**
 * The colours a map can show, and the conversion of pictures into them.
 *
 * <p>Pictures are converted with Floyd-Steinberg dithering over the whole picture (not per map), so the parts fit
 * together without seams. The web interface runs the same algorithm for its preview, with the palette this server
 * reports.
 */
public final class Palette {

    /** Map colour index of a transparent pixel. */
    public static final byte TRANSPARENT = 0;

    // index -> 0xRRGGBB, or -1 for indices that are not a visible colour
    private final int[] colors;
    // nearest colour per 6-bit-per-channel RGB value, 0 while not computed yet (0 is never a nearest colour)
    private final byte[] nearestCache = new byte[1 << 18];

    public Palette(int[] colors) {
        this.colors = colors.clone();
    }

    /**
     * Reads the palette of the running server, which knows the colours of its Minecraft version.
     */
    @SuppressWarnings("deprecation") // the only way to get the palette; still present in Spigot and Paper
    public static Palette fromServer() {
        int[] colors = new int[256];
        // indices past the end of the palette must stay unusable, they would show as transparent in game
        Arrays.fill(colors, -1);
        for (int index = 4; index < colors.length; index++) { // 0-3 are transparent
            try {
                Color color = org.bukkit.map.MapPalette.getColor((byte) index);
                if (color.getAlpha() == 255) {
                    colors[index] = color.getRGB() & 0xFFFFFF;
                }
            } catch (IndexOutOfBoundsException exception) {
                break;
            }
        }
        return new Palette(colors);
    }

    /**
     * @return the visible colours as {@code index -> 0xRRGGBB}, {@code -1} for unused indices
     */
    public int[] colors() {
        return colors.clone();
    }

    /**
     * The nearest colour is looked up for the middle of the 4x4x4 block the colour lies in, so the result does not
     * depend on which colour of the block was asked first (the web preview relies on that).
     */
    public byte nearest(int r, int g, int b) {
        int key = (r >> 2) << 12 | (g >> 2) << 6 | (b >> 2);
        byte cached = nearestCache[key];
        if (cached != 0) {
            return cached;
        }
        r = r & ~3 | 2;
        g = g & ~3 | 2;
        b = b & ~3 | 2;
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int index = 0; index < colors.length; index++) {
            int color = colors[index];
            if (color < 0) {
                continue;
            }
            long distance = distance(r, g, b, color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = index;
            }
        }
        // several threads may fill the cache at once; they all write the same value
        nearestCache[key] = (byte) best;
        return (byte) best;
    }

    /**
     * Weighted RGB distance ("redmean"), much closer to how we see colours than plain RGB distance and cheap enough to
     * run for every pixel. The web interface uses the same formula.
     */
    static long distance(int r1, int g1, int b1, int r2, int g2, int b2) {
        int mean = (r1 + r2) >> 1;
        int dr = r1 - r2;
        int dg = g1 - g2;
        int db = b1 - b2;
        return (((512L + mean) * dr * dr) >> 8) + 4L * dg * dg + (((767L - mean) * db * db) >> 8);
    }

    /**
     * Converts a picture into map colours.
     *
     * @param argb the pixels, row by row; pixels with less than half opacity become transparent
     * @return one map colour index per pixel, in the same order
     */
    public byte[] dither(int[] argb, int width, int height) {
        byte[] result = new byte[width * height];
        // accumulated error of the current and the next row, 3 channels per pixel, one pixel of padding on each side
        float[] current = new float[(width + 2) * 3];
        float[] next = new float[(width + 2) * 3];

        for (int y = 0; y < height; y++) {
            // serpentine scan: every other row runs right to left, which avoids the diagonal "worm" patterns
            boolean reverse = (y & 1) == 1;
            for (int step = 0; step < width; step++) {
                int x = reverse ? width - 1 - step : step;
                int pixel = argb[y * width + x];
                if ((pixel >>> 24) < 128) {
                    result[y * width + x] = TRANSPARENT;
                    continue;
                }
                int e = (x + 1) * 3;
                int r = clamp((pixel >> 16 & 0xFF) + current[e]);
                int g = clamp((pixel >> 8 & 0xFF) + current[e + 1]);
                int b = clamp((pixel & 0xFF) + current[e + 2]);
                byte index = nearest(r, g, b);
                result[y * width + x] = index;

                int chosen = colors[index & 0xFF];
                float er = r - (chosen >> 16 & 0xFF);
                float eg = g - (chosen >> 8 & 0xFF);
                float eb = b - (chosen & 0xFF);
                int ahead = reverse ? -3 : 3;
                spread(current, e + ahead, er, eg, eb, 7 / 16f);
                spread(next, e - ahead, er, eg, eb, 3 / 16f);
                spread(next, e, er, eg, eb, 5 / 16f);
                spread(next, e + ahead, er, eg, eb, 1 / 16f);
            }
            float[] swap = current;
            current = next;
            next = swap;
            Arrays.fill(next, 0);
        }
        return result;
    }

    private static void spread(float[] errors, int offset, float r, float g, float b, float weight) {
        errors[offset] += r * weight;
        errors[offset + 1] += g * weight;
        errors[offset + 2] += b * weight;
    }

    private static int clamp(float value) {
        return value <= 0 ? 0 : value >= 255 ? 255 : Math.round(value);
    }
}
