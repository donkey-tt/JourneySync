package com.donkeytt.journeysync.client;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * Pixel-level comparison between a locally rendered tile and a server-authoritative reference
 * copy. JourneyMap periodically re-renders tiles from its own in-memory region data and writes
 * them over newer synced content; those stale renders are a strict subset of the server's
 * version - they add no explored pixels. Size-based heuristics cannot tell a 4% content loss
 * from a 4% compression jitter, so uploads are gated on actual pixels instead.
 */
public final class PixelInfo {

    /** Per-channel RGB delta below which two pixels count as the same block color. */
    private static final int COLOR_TOLERANCE = 2;

    /** Color changes below this many pixels are treated as encoding noise. */
    private static final int CHANGED_PIXEL_THRESHOLD = 32;

    private PixelInfo() {}

    /**
     * Whether the local render shows something the reference does not: pixels explored on the
     * local side but still blank in the reference, or real color changes. When this returns
     * false the local render is a stale subset (or an exact re-encode) and must not be
     * uploaded. Unreadable or mismatched images conservatively count as "adds pixels".
     */
    public static boolean addsNewPixels(File local, File reference) {
        try {
            return addsNewPixels(ImageIO.read(local), ImageIO.read(reference));
        } catch (IOException e) {
            return true;
        }
    }

    public static boolean addsNewPixels(byte[] localPng, byte[] referencePng) {
        try {
            return addsNewPixels(
                ImageIO.read(new ByteArrayInputStream(localPng)),
                ImageIO.read(new ByteArrayInputStream(referencePng)));
        } catch (IOException e) {
            return true;
        }
    }

    static boolean addsNewPixels(BufferedImage local, BufferedImage reference) {
        if (local == null || reference == null) {
            return true;
        }
        int w = Math.min(local.getWidth(), reference.getWidth());
        int h = Math.min(local.getHeight(), reference.getHeight());
        if (w <= 0 || h <= 0) {
            return true;
        }
        int[] localRgb = local.getRGB(0, 0, w, h, null, 0, w);
        int[] refRgb = reference.getRGB(0, 0, w, h, null, 0, w);
        int newPixels = 0;
        int changedPixels = 0;
        for (int i = 0; i < localRgb.length; i++) {
            int la = localRgb[i] >>> 24;
            int ra = refRgb[i] >>> 24;
            if (la == 0 && ra == 0) {
                continue;
            }
            if (la != 0 && ra == 0) {
                newPixels++;
                continue;
            }
            if (la == 0) {
                continue; // local lost this pixel; the regression guards handle that direction
            }
            if (colorDiff(localRgb[i], refRgb[i]) > COLOR_TOLERANCE) {
                changedPixels++;
            }
        }
        return newPixels > 0 || changedPixels > CHANGED_PIXEL_THRESHOLD;
    }

    private static int colorDiff(int a, int b) {
        int d = Math.abs(((a >> 16) & 0xff) - ((b >> 16) & 0xff));
        d = Math.max(d, Math.abs(((a >> 8) & 0xff) - ((b >> 8) & 0xff)));
        d = Math.max(d, Math.abs((a & 0xff) - (b & 0xff)));
        return d;
    }
}
