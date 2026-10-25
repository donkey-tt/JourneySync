package com.donkeytt.journeysync;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;

import javax.imageio.ImageIO;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.donkeytt.journeysync.data.PixelMerge;

public class PixelMergeTest {

    private File dir;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("pixelmerge-test")
            .toFile();
    }

    @After
    public void tearDown() throws Exception {
        for (File f : dir.listFiles()) {
            f.delete();
        }
        dir.delete();
    }

    private byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bout);
        return bout.toByteArray();
    }

    private BufferedImage image(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    }

    private int[] decode(byte[] png) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    @Test
    public void unionFillsMissingPixels() throws Exception {
        BufferedImage stored = image(16, 16);
        BufferedImage incoming = image(16, 16);
        stored.setRGB(1, 1, 0xff336699);
        incoming.setRGB(9, 9, 0xffaa0000);
        byte[] merged = PixelMerge.mergePng(png(stored), png(incoming));
        assertNotNull(merged);
        int[] px = decode(merged);
        assertEquals(0xff336699, px[1 + 1 * 16]); // stored pixel survives
        assertEquals(0xffaa0000, px[9 + 9 * 16]); // incoming pixel added
    }

    @Test
    public void incomingCannotEraseExploredPixels() throws Exception {
        // A stale re-render that lost explored area: the shared map must keep it.
        BufferedImage stored = image(16, 16);
        BufferedImage incoming = image(16, 16);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 16; x++) {
                stored.setRGB(x, y, 0xff336699);
            }
        }
        byte[] merged = PixelMerge.mergePng(png(stored), png(incoming));
        assertNotNull(merged);
        int[] px = decode(merged);
        assertEquals(0xff336699, px[3 + 3 * 16]);
    }

    @Test
    public void conflictingColorsIncomingWins() throws Exception {
        BufferedImage stored = image(16, 16);
        BufferedImage incoming = image(16, 16);
        stored.setRGB(2, 2, 0xff336699);
        incoming.setRGB(2, 2, 0xff000000); // terrain changed; newer observation wins
        byte[] merged = PixelMerge.mergePng(png(stored), png(incoming));
        int[] px = decode(merged);
        assertEquals(0xff000000, px[2 + 2 * 16]);
    }

    @Test
    public void identicalContentMergesToSamePixels() throws Exception {
        BufferedImage img = image(16, 16);
        img.setRGB(4, 4, 0xff123456);
        int[] before = decode(png(img));
        int[] after = decode(PixelMerge.mergePng(png(img), png(img)));
        assertArrayEquals(before, after);
    }

    @Test
    public void dimensionMismatchReturnsNull() throws Exception {
        BufferedImage a = image(16, 16);
        BufferedImage b = image(8, 8);
        assertNull(PixelMerge.mergePng(png(a), png(b)));
    }

    @Test
    public void unreadableDataReturnsNull() throws Exception {
        BufferedImage a = image(16, 16);
        assertNull(PixelMerge.mergePng(png(a), "not a png".getBytes("UTF-8")));
        assertTrue(PixelMerge.mergePng(png(a), png(a)).length > 0);
    }
}
