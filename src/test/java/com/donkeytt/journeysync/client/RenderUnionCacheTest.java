package com.donkeytt.journeysync.client;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.junit.Assert;
import org.junit.Test;

public class RenderUnionCacheTest {

    private static final int RED = 0xFFCC0000;
    private static final int BLUE = 0xFF0000CC;

    /** A 2x1 PNG with the given two pixels. */
    private static byte[] png(int left, int right) throws IOException {
        BufferedImage img = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, left);
        img.setRGB(1, 0, right);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static int[] pixels(byte[] png) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        return new int[] { img.getRGB(0, 0), img.getRGB(1, 0) };
    }

    @Test
    public void unionsDisjointRenders() throws IOException {
        RenderUnionCache cache = new RenderUnionCache();
        cache.accumulate("a", png(RED, 0));
        cache.accumulate("a", png(0, BLUE));
        int[] merged = pixels(cache.get("a"));
        Assert.assertEquals(RED, merged[0]); // kept from the first render
        Assert.assertEquals(BLUE, merged[1]); // kept from the second render
    }

    @Test
    public void removeDropsTheUnion() throws IOException {
        RenderUnionCache cache = new RenderUnionCache();
        cache.accumulate("a", png(RED, 0));
        cache.remove("a");
        Assert.assertNull(cache.get("a"));
        // re-accumulating after removal seeds fresh (no stale union resurrected)
        cache.accumulate("a", png(0, BLUE));
        Assert.assertEquals(BLUE, pixels(cache.get("a"))[1]);
    }

    @Test
    public void evictsOldestTileWhenOverTheTileLimit() throws IOException {
        RenderUnionCache cache = new RenderUnionCache(Long.MAX_VALUE, 1);
        cache.accumulate("a", png(RED, 0));
        cache.accumulate("b", png(0, BLUE));
        Assert.assertNull(cache.get("a")); // eldest evicted
        Assert.assertNotNull(cache.get("b"));
        Assert.assertEquals(1, cache.tileCount());
    }

    @Test
    public void evictsOldestTileWhenOverTheByteBudget() throws IOException {
        byte[] a = png(RED, 0);
        RenderUnionCache cache = new RenderUnionCache(a.length, 100);
        cache.accumulate("a", a);
        Assert.assertNotNull(cache.get("a"));
        cache.accumulate("b", png(0, BLUE)); // pushes over budget -> a evicted
        Assert.assertNull(cache.get("a"));
        Assert.assertNotNull(cache.get("b"));
    }

    @Test
    public void clearDropsEverything() throws IOException {
        RenderUnionCache cache = new RenderUnionCache();
        cache.accumulate("a", png(RED, 0));
        cache.clear();
        Assert.assertNull(cache.get("a"));
        Assert.assertEquals(0, cache.tileCount());
    }
}
