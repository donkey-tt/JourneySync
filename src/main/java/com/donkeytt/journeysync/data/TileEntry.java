package com.donkeytt.journeysync.data;

/**
 * One entry of the server-side tile manifest: content hash plus bookkeeping.
 */
public class TileEntry {

    public String sha1;
    /** Server wall-clock millis when this tile was accepted. */
    public long mtime;
    public int size;
    public String uploader;

    public TileEntry() {}

    public TileEntry(String sha1, long mtime, int size, String uploader) {
        this.sha1 = sha1;
        this.mtime = mtime;
        this.size = size;
        this.uploader = uploader;
    }
}
