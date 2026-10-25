package eu.nurkert.photomaps.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class WebServerTest {

    @Test
    void readsTheChosenParts() {
        assertEquals(List.of(0, 2, 3), WebServer.parts("3,0,2,2", 4));
        assertNull(WebServer.parts("", 4));
        assertNull(WebServer.parts("4", 4));
        assertNull(WebServer.parts("-1", 4));
        assertNull(WebServer.parts("a", 4));
    }

    @Test
    void decodesOnlyPicturesOfTheExpectedSize() throws IOException {
        byte[] png = png(256, 128);
        assertNotNull(WebServer.decode(png, 256, 128));
        assertNull(WebServer.decode(png, 128, 128));
        assertNull(WebServer.decode("not a picture".getBytes(), 256, 128));
    }

    @Test
    void acceptsOnlyPng() throws IOException {
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB), "jpg", jpeg);
        assertNull(WebServer.decode(jpeg.toByteArray(), 128, 128));
        // a PNG signature in front of something else
        byte[] fake = png(128, 128);
        fake[20] ^= 0x55;
        assertNull(WebServer.decode(fake, 128, 128));
        assertNull(WebServer.decode(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, 128, 128));
    }

    @Test
    void limitsUploadsToTheirPictureSize() throws IOException {
        // the largest real PNG of one map, random noise that does not compress, fits
        BufferedImage noise = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        java.util.Random random = new java.util.Random(1);
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                noise.setRGB(x, y, random.nextInt());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(noise, "png", out);
        assertTrue(out.size() <= WebServer.maxUploadSize(128, 128));
        assertTrue(WebServer.maxUploadSize(1280, 1280) < 7 * 1024 * 1024);
    }

    @Test
    void escapesJson() {
        assertEquals("\"a\\\"b\\\\c\\u003c\"", WebServer.quote("a\"b\\c<"));
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }
}
