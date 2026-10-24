package com.donkeytt.journeysync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.donkeytt.journeysync.data.Manifest;
import com.donkeytt.journeysync.data.TileEntry;

public class ManifestDiffTest {

    private Manifest manifestWith(String... pathHashPairs) {
        Manifest m = new Manifest();
        for (int i = 0; i < pathHashPairs.length; i += 2) {
            m.tiles.put(pathHashPairs[i], new TileEntry(pathHashPairs[i + 1], 1L, 10, "u"));
        }
        return m;
    }

    @Test
    public void downloadsOnlyMissingOrDifferent() {
        Manifest m = manifestWith("DIM0/day/a.png", "h1", "DIM0/day/b.png", "h2", "DIM0/day/c.png", "h3");
        Map<String, String> local = new HashMap<String, String>();
        local.put("DIM0/day/a.png", "h1"); // identical on disk -> skip
        Map<String, String> synced = new HashMap<String, String>();
        synced.put("DIM0/day/b.png", "h2"); // previously synced content matches -> skip (e.g. partial file)
        Set<String> dl = m.diffDownload(local, synced);
        assertEquals(1, dl.size());
        assertTrue(dl.contains("DIM0/day/c.png"));
    }

    @Test
    public void uploadsLocalOnlyAndDifferent() {
        Manifest m = manifestWith("DIM0/day/a.png", "h1", "DIM0/day/b.png", "h2");
        Map<String, String> local = new HashMap<String, String>();
        local.put("DIM0/day/a.png", "h1"); // same -> no upload
        local.put("DIM0/day/b.png", "hX"); // different -> upload
        local.put("DIM0/day/new.png", "h9"); // server lacks -> upload
        Set<String> ul = m.diffUpload(local);
        assertEquals(2, ul.size());
        assertTrue(ul.contains("DIM0/day/b.png"));
        assertTrue(ul.contains("DIM0/day/new.png"));
    }

    @Test
    public void jsonRoundTrip() {
        Manifest m = manifestWith("DIM0/day/a.png", "abc", "DIM-1/night/0,0.png", "def");
        m.epoch = 42;
        Manifest parsed = Manifest.fromJson(m.toJson());
        assertEquals(42, parsed.epoch);
        assertEquals(2, parsed.tiles.size());
        assertEquals("abc", parsed.tiles.get("DIM0/day/a.png").sha1);
    }

    @Test
    public void emptyJsonGivesEmptyManifest() {
        Manifest parsed = Manifest.fromJson("");
        assertTrue(parsed.tiles.isEmpty());
    }
}
