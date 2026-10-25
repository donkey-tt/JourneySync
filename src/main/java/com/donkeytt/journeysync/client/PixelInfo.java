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
 * version - they add no explored pixels - or a mixed render that regains a sliver while losing
 * far more elsewhere. Size-based heuristics cannot tell a 4% content loss from a 4% compression
 * jitter, so uploads are gated on actual pixels instead.
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
     * uploaded. A render that loses far more explored pixels than it gains is treated the same
     * way - JourneyMap produces those from incomplete region data and uploading one would
     * clobber a fuller known version. Unreadable or mismatched images conservatively count as
     * "adds pixels".
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

    /**
     * Strict variant of {@link #addsNewPixels(File, File)} used while a freshly inbound tile is in
     * its quiet period: color changes (dithering/lighting noise between two clients rendering the
     * same chunks) no longer count as new information - only pixels the reference does not have.
     * The net-loss rejection still applies.
     */
    public static boolean addsNewPixelsStrict(File local, File reference) {
        try {
            return addsNewPixels(ImageIO.read(local), ImageIO.read(reference), true);
        } catch (IOException e) {
            return true;
        }
    }

    public static boolean addsNewPixelsStrict(byte[] localPng, byte[] referencePng) {
        try {
            return addsNewPixels(
                ImageIO.read(new ByteArrayInputStream(localPng)),
                ImageIO.read(new ByteArrayInputStream(referencePng)),
                true);
        } catch (IOException e) {
            return true;
        }
    }

    static boolean addsNewPixels(BufferedImage local, BufferedImage reference) {
        return addsNewPixels(local, reference, false);
    }

    static boolean addsNewPixels(BufferedImage local, BufferedImage reference, boolean strict) {
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
        int lostPixels = 0;
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
                lostPixels++; // explored in the reference but blank locally; stale/incomplete render
                continue;
            }
            if (colorDiff(localRgb[i], refRgb[i]) > COLOR_TOLERANCE) {
                changedPixels++;
            }
        }
        if (lostPixels > newPixels + CHANGED_PIXEL_THRESHOLD) {
            return false; // net loss: a fuller version exists and must not be clobbered
        }
        if (strict) {
            return newPixels > 0;
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
