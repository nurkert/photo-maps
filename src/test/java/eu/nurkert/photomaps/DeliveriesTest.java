package eu.nurkert.photomaps;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DeliveriesTest {

    @Test
    void cutsTheRightPart() {
        int columns = 3;
        int rows = 2;
        int width = columns * 128;
        byte[] pixels = new byte[width * rows * 128];
        // every pixel holds the number of its part
        for (int y = 0; y < rows * 128; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = (byte) ((y / 128) * columns + x / 128);
            }
        }
        for (int part = 0; part < columns * rows; part++) {
            byte[] tile = Deliveries.tile(pixels, columns, part % columns, part / columns);
            for (byte b : tile) {
                assertEquals(part, b);
            }
        }
    }
}
