package eu.nurkert.photomaps.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    void escapesJson() {
        assertEquals("\"a\\\"b\\\\c\\u003c\"", WebServer.quote("a\"b\\c<"));
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }
}
