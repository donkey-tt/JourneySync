package com.donkeytt.journeysync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.donkeytt.journeysync.data.TileKey;

public class TileKeyTest {

    @Test
    public void parsesSurfaceTile() {
        TileKey key = TileKey.parse("DIM0/day/-1,0.png");
        assertEquals(0, key.dimension);
        assertEquals("day", key.kind);
        assertEquals(-1, key.regionX);
        assertEquals(0, key.regionZ);
        assertEquals("DIM0/day/-1,0.png", key.relPath());
    }

    @Test
    public void parsesNegativeDimensionAndSlice() {
        TileKey key = TileKey.parse("DIM-1/3/2,-7.png");
        assertEquals(-1, key.dimension);
        assertEquals("3", key.kind);
        assertEquals(2, key.regionX);
        assertEquals(-7, key.regionZ);
    }

    @Test
    public void roundTrip() {
        TileKey key = TileKey.of(42, "night", 123, -456);
        assertEquals(key, TileKey.parse(key.relPath()));
        assertEquals(
            key.hashCode(),
            TileKey.parse(key.relPath())
                .hashCode());
    }

    @Test
    public void rejectsGarbage() {
        assertNull(TileKey.parse(null));
        assertNull(TileKey.parse(""));
        assertNull(TileKey.parse("DIM0/day/1,0.png/extra"));
        assertNull(TileKey.parse("DIM0/day/1.png"));
        assertNull(TileKey.parse("DIM/day/1,0.png"));
        assertNull(TileKey.parse("DIM0/weird/1,0.png"));
        assertNull(TileKey.parse("DIM0/day/1,0.jpg"));
        assertNull(TileKey.parse("DIM0/day/../1,0.png"));
        assertNull(TileKey.parse("../DIM0/day/1,0.png"));
    }
}
