package com.donkeytt.journeysync.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Streaming SHA-1 helpers (hex-encoded, lowercase).
 */
public final class Hashing {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Hashing() {}

    public static String sha1Hex(File file) throws IOException {
        MessageDigest digest = newDigest();
        InputStream in = new FileInputStream(file);
        try {
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
        } finally {
            in.close();
        }
        return toHex(digest.digest());
    }

    public static String sha1Hex(byte[] data) {
        return toHex(newDigest().digest(data));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0f];
        }
        return new String(out);
    }
}
