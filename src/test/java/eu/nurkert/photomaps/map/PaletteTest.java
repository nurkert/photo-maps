package eu.nurkert.photomaps.map;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PaletteTest {

    // a tiny palette: black, white, red
    private static Palette palette() {
        int[] colors = new int[256];
        Arrays.fill(colors, -1);
        colors[4] = 0x000000;
        colors[5] = 0xFFFFFF;
        colors[6] = 0xFF0000;
        return new Palette(colors);
    }

    @Test
    void picksTheNearestColour() {
        Palette palette = palette();
        assertEquals(4, palette.nearest(10, 12, 8));
        assertEquals(5, palette.nearest(250, 240, 245));
        assertEquals(6, palette.nearest(220, 30, 20));
    }

    @Test
    void keepsPaletteColoursExact() {
        int[] pixels = new int[16];
        Arrays.fill(pixels, 0xFFFF0000);
        byte[] result = palette().dither(pixels, 4, 4);
        for (byte b : result) {
            assertEquals(6, b);
        }
    }

    @Test
    void makesHalfTransparentPixelsTransparent() {
        int[] pixels = {0x00FFFFFF, 0x7FFFFFFF, 0x80FFFFFF, 0xFFFFFFFF};
        byte[] result = palette().dither(pixels, 4, 1);
        assertEquals(Palette.TRANSPARENT, result[0]);
        assertEquals(Palette.TRANSPARENT, result[1]);
        assertEquals(5, result[2]);
        assertEquals(5, result[3]);
    }

    @Test
    void dithersGreyIntoBlackAndWhite() {
        int size = 32;
        int[] pixels = new int[size * size];
        Arrays.fill(pixels, 0xFF808080);
        byte[] result = palette().dither(pixels, size, size);
        long white = 0;
        for (byte b : result) {
            if (b == 5) {
                white++;
            }
        }
        // mid grey is about half white, half black
        double share = white / (double) result.length;
        assertEquals(0.5, share, 0.08);
    }
}
