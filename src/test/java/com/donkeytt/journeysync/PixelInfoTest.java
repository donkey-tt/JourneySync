package com.donkeytt.journeysync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;

import javax.imageio.ImageIO;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.donkeytt.journeysync.client.PixelInfo;

public class PixelInfoTest {

    private File dir;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("pixelinfo-test")
            .toFile();
    }

    @After
    public void tearDown() throws Exception {
        for (File f : dir.listFiles()) {
            f.delete();
        }
        dir.delete();
    }

    private BufferedImage image(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    }

    private File write(BufferedImage img, String name) throws Exception {
        File f = new File(dir, name);
        ImageIO.write(img, "png", f);
        return f;
    }

    @Test
    public void subsetRenderAddsNothing() throws Exception {
        // Reference explored a 10x10 area; the local render only covers half of it with the
        // same colors - a stale re-render that must not upload.
        BufferedImage ref = image(32, 32);
        BufferedImage local = image(32, 32);
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                ref.setRGB(x, y, 0xff336699);
                if (x < 5) {
                    local.setRGB(x, y, 0xff336699);
                }
            }
        }
        assertFalse(PixelInfo.addsNewPixels(write(local, "a.png"), write(ref, "b.png")));
    }

    @Test
    public void supersetRenderAddsPixels() throws Exception {
        BufferedImage ref = image(32, 32);
        BufferedImage local = image(32, 32);
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                ref.setRGB(x, y, 0xff336699);
                local.setRGB(x, y, 0xff336699);
            }
        }
        local.setRGB(20, 20, 0xffaa0000); // genuinely new exploration
        assertTrue(PixelInfo.addsNewPixels(write(local, "a.png"), write(ref, "b.png")));
    }

    @Test
    public void identicalPixelsAreNotNewEvenWithDifferentEncoding() throws Exception {
        BufferedImage ref = image(32, 32);
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                ref.setRGB(x, y, 0xff336699);
            }
        }
        File a = write(ref, "a.png");
        File b = write(ref, "b.png");
        assertFalse(PixelInfo.addsNewPixels(a, b));
    }

    @Test
    public void colorChangeBeyondThresholdAddsPixels() throws Exception {
        BufferedImage ref = image(64, 64);
        BufferedImage local = image(64, 64);
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 20; x++) {
                ref.setRGB(x, y, 0xff336699);
                local.setRGB(x, y, 0xff336699);
            }
        }
        for (int i = 0; i < 40; i++) { // terrain actually changed
            local.setRGB(i % 20, i / 20, 0xff000000);
        }
        assertTrue(PixelInfo.addsNewPixels(write(local, "a.png"), write(ref, "b.png")));
    }

    @Test
    public void tinyColorJitterIsIgnored() throws Exception {
        BufferedImage ref = image(64, 64);
        BufferedImage local = image(64, 64);
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 20; x++) {
                ref.setRGB(x, y, 0xff336699);
                local.setRGB(x, y, 0xff336699);
            }
        }
        local.setRGB(3, 3, 0xff33669a); // single-channel delta of 1
        local.setRGB(7, 7, 0xff3366cc); // delta of 3, but only one pixel
        assertFalse(PixelInfo.addsNewPixels(write(local, "a.png"), write(ref, "b.png")));
    }

    @Test
    public void mixedRenderWithNetLossIsRejected() throws Exception {
        // JourneyMap re-rendered from incomplete region data: it regained a thin sliver of the
        // explored area but lost far more of it - uploading would clobber the fuller version.
        BufferedImage ref = image(64, 64);
        BufferedImage local = image(64, 64);
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                ref.setRGB(x, y, 0xff336699);
            }
        }
        for (int y = 0; y < 10; y++) { // only the top strip survived the stale re-render
            for (int x = 0; x < 40; x++) {
                local.setRGB(x, y, 0xff336699);
            }
        }
        local.setRGB(50, 50, 0xffaa0000); // a handful of genuinely new pixels
        local.setRGB(51, 50, 0xffaa0000);
        assertFalse(PixelInfo.addsNewPixels(write(local, "a.png"), write(ref, "b.png")));
    }

    @Test
    public void mixedRenderWithNetGainIsAccepted() throws Exception {
        // Mostly the same explored area plus real new exploration and a tiny dropout - net gain
        // must upload.
        BufferedImage ref = image(64, 64);
        BufferedImage local = image(64, 64);
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                ref.setRGB(x, y, 0xff336699);
                local.setRGB(x, y, 0xff336699);
            }
        }
        local.setRGB(39, 39, 0); // one lost pixel
        for (int i = 0; i < 40; i++) { // a real swath of new exploration
            local.setRGB(i, 50, 0xffaa0000);
        }
        assertTrue(PixelInfo.addsNewPixels(write(local, "a.png"), write(ref, "b.png")));
    }

    @Test
    public void strictModeIgnoresColorChangesButKeepsNewPixels() throws Exception {
        // Two clients rendering the same chunks differ by dithering noise: many color changes,
        // no new pixels. Normal mode treats large changes as real; strict (inbound quiet) mode
        // must not, or the two clients ping-pong the tile back and forth.
        BufferedImage ref = image(64, 64);
        BufferedImage local = image(64, 64);
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                ref.setRGB(x, y, 0xff336699);
                local.setRGB(x, y, 0xff3366aa); // per-pixel dithering delta beyond the color tolerance
            }
        }
        assertTrue(PixelInfo.addsNewPixels(write(local, "a.png"), write(ref, "b.png")));
        assertFalse(PixelInfo.addsNewPixelsStrict(write(local, "a.png"), write(ref, "b.png")));

        local.setRGB(50, 50, 0xffaa0000); // genuine new exploration still uploads in strict mode
        assertTrue(PixelInfo.addsNewPixelsStrict(write(local, "a.png"), write(ref, "b.png")));
    }

    @Test
    public void strictModeStillRejectsNetLoss() throws Exception {
        BufferedImage ref = image(64, 64);
        BufferedImage local = image(64, 64);
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                ref.setRGB(x, y, 0xff336699);
            }
        }
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 40; x++) {
                local.setRGB(x, y, 0xff336699);
            }
        }
        local.setRGB(50, 50, 0xffaa0000);
        assertFalse(PixelInfo.addsNewPixelsStrict(write(local, "a.png"), write(ref, "b.png")));
    }

    @Test
    public void unreadableImageCountsAsNew() throws Exception {
        BufferedImage ref = image(16, 16);
        ref.setRGB(1, 1, 0xffffffff);
        File garbage = new File(dir, "garbage.png");
        Files.write(garbage.toPath(), "not a png".getBytes("UTF-8"));
        assertTrue(PixelInfo.addsNewPixels(garbage, write(ref, "b.png")));
    }

    @Test
    public void byteArraysBehaveLikeFiles() throws Exception {
        BufferedImage ref = image(16, 16);
        BufferedImage local = image(16, 16);
        for (int i = 0; i < 8; i++) {
            ref.setRGB(i, 0, 0xff112233);
            local.setRGB(i, 0, 0xff112233);
        }
        local.setRGB(9, 9, 0xff112233); // new pixel
        byte[] localBytes = Files.readAllBytes(write(local, "a.png").toPath());
        byte[] refBytes = Files.readAllBytes(write(ref, "b.png").toPath());
        assertTrue(PixelInfo.addsNewPixels(localBytes, refBytes));
    }
}
