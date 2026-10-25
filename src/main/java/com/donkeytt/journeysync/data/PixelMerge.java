package com.donkeytt.journeysync.data;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * Pixel-level union of two renditions of the same map tile.
 *
 * <p>
 * The shared map is incremental: every client contributes the pixels it has actually explored, and
 * the server merges each upload into the stored tile instead of overwriting it. Coverage is the
 * union - an explored pixel, once stored, is never dropped just because a later render (from
 * incomplete region data) no longer sees it. Where both versions hold a color for the same pixel,
 * the incoming one wins as the newer observation. A stale or partial render can therefore no
 * longer shrink the shared map no matter which guards it slips past.
 * </p>
 */
public final class PixelMerge {

    private PixelMerge() {}

    /**
     * Merge an incoming upload into the stored tile and return the merged PNG. Returns null when
     * the two images cannot be compared (unreadable data or different dimensions); the caller
     * then stores the upload as-is.
     */
    public static byte[] mergePng(byte[] storedPng, byte[] incomingPng) {
        try {
            BufferedImage stored = ImageIO.read(new ByteArrayInputStream(storedPng));
            BufferedImage incoming = ImageIO.read(new ByteArrayInputStream(incomingPng));
            if (stored == null || incoming == null
                || stored.getWidth() != incoming.getWidth()
                || stored.getHeight() != incoming.getHeight()) {
                return null;
            }
            int w = stored.getWidth();
            int h = stored.getHeight();
            int[] merged = stored.getRGB(0, 0, w, h, null, 0, w);
            int[] inc = incoming.getRGB(0, 0, w, h, null, 0, w);
            for (int p = 0; p < merged.length; p++) {
                if ((inc[p] >>> 24) != 0) {
                    merged[p] = inc[p]; // incoming holds a pixel: it wins (coverage union + newer color)
                }
                // otherwise keep the stored pixel: explored coverage is monotonic
            }
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            out.setRGB(0, 0, w, h, merged, 0, w);
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bout);
            return bout.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}
