package com.donkeytt.journeysync.data;

/**
 * One entry of the server-side tile manifest: content hash plus size (the size feeds the
 * regression heuristic in {@link Manifest#isRegression}).
 */
public class TileEntry {

    public String sha1;
    public int size;

    public TileEntry() {}

    public TileEntry(String sha1, int size) {
        this.sha1 = sha1;
        this.size = size;
    }
}
