package com.donkeytt.journeysync.data;

import java.util.regex.Pattern;

/**
 * A canonical JourneyMap tile relative path, e.g. {@code DIM0/day/-1,0.png}.
 *
 * <p>
 * JourneyMap layout: {@code DIM<dim>/{day|night|<vSlice>}/<regionX>,<regionZ>.png} where region
 * coordinates are chunk coordinates shifted right by 5 (32x32 chunks per 512px tile). The numeric
 * sub-directories are cave vertical slices, NOT zoom levels.
 * </p>
 *
 * <p>
 * Parsing is strict and rejects anything that does not match the pattern exactly, which doubles as
 * path-traversal protection for network-supplied paths.
 * </p>
 */
public final class TileKey {

    private static final Pattern DIM = Pattern.compile("DIM(-?\\d+)");
    private static final Pattern KIND = Pattern.compile("day|night|-?\\d+");
    private static final Pattern FILE = Pattern.compile("(-?\\d+),(-?\\d+)\\.png");

    public final int dimension;
    /** "day", "night" or the decimal vSlice index for cave maps. */
    public final String kind;
    public final int regionX;
    public final int regionZ;

    private final String relPath;

    private TileKey(int dimension, String kind, int regionX, int regionZ) {
        this.dimension = dimension;
        this.kind = kind;
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.relPath = "DIM" + dimension + "/" + kind + "/" + regionX + "," + regionZ + ".png";
    }

    public static TileKey of(int dimension, String kind, int regionX, int regionZ) {
        if (!KIND.matcher(kind)
            .matches()) {
            throw new IllegalArgumentException("Illegal tile kind: " + kind);
        }
        return new TileKey(dimension, kind, regionX, regionZ);
    }

    /**
     * Parse a relative path like {@code DIM0/day/-1,0.png}. Returns null when the path does not
     * match the expected JourneyMap tile layout.
     */
    public static TileKey parse(String relPath) {
        if (relPath == null) {
            return null;
        }
        String[] parts = relPath.split("/");
        if (parts.length != 3) {
            return null;
        }
        java.util.regex.Matcher mDim = DIM.matcher(parts[0]);
        if (!mDim.matches() || !KIND.matcher(parts[1])
            .matches()) {
            return null;
        }
        java.util.regex.Matcher mFile = FILE.matcher(parts[2]);
        if (!mFile.matches()) {
            return null;
        }
        try {
            int dim = Integer.parseInt(mDim.group(1));
            int rx = Integer.parseInt(mFile.group(1));
            int rz = Integer.parseInt(mFile.group(2));
            return new TileKey(dim, parts[1], rx, rz);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The canonical relative path, forward slashes, e.g. {@code DIM0/day/-1,0.png}. */
    public String relPath() {
        return relPath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TileKey)) {
            return false;
        }
        TileKey other = (TileKey) o;
        return relPath.equals(other.relPath);
    }

    @Override
    public int hashCode() {
        return relPath.hashCode();
    }

    @Override
    public String toString() {
        return relPath;
    }
}
