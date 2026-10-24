package eu.nurkert.photomaps.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServerPaletteTest {

    @Test
    void usesOnlyColoursThatExistInGame() {
        Palette palette = Palette.fromServer();
        int[] colors = palette.colors();
        for (int index = 0; index < colors.length; index++) {
            if (colors[index] >= 0) {
                assertTrue(index >= 4 && index < 248, "index " + index + " is not a map colour");
            }
        }
        // black and white must be real, visible colours (the QR code is drawn with them)
        int black = palette.nearest(0, 0, 0) & 0xFF;
        int white = palette.nearest(255, 255, 255) & 0xFF;
        assertTrue(black >= 4 && black < 248);
        assertEquals(0xFFFFFF, colors[white]);
    }
}
