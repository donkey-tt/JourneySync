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
            m.tiles.put(pathHashPairs[i], new TileEntry(pathHashPairs[i + 1], 10));
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
        Set<String> dl = m.diffDownload(local, synced, new HashMap<String, Long>());
        assertEquals(1, dl.size());
        assertTrue(dl.contains("DIM0/day/c.png"));
    }

    @Test
    public void downloadsWhenSyncedMatchesButLocalFileRegressed() {
        // JM re-rendered a synced tile from incomplete region data: the synced hash still
        // matches the server, but the shrunken local file must be restored from the server.
        Manifest m = new Manifest();
        m.tiles.put("DIM0/day/a.png", new TileEntry("h1", 100_000));
        Map<String, String> local = new HashMap<String, String>();
        local.put("DIM0/day/a.png", "hSmall");
        Map<String, String> synced = new HashMap<String, String>();
        synced.put("DIM0/day/a.png", "h1");
        Map<String, Long> sizes = new HashMap<String, Long>();
        sizes.put("DIM0/day/a.png", 5_000L);
        Set<String> dl = m.diffDownload(local, synced, sizes);
        assertEquals(1, dl.size());
        assertTrue(dl.contains("DIM0/day/a.png"));
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
    public void uploadRegressionPruningIsTheCallersJob() {
        // diffUpload deliberately returns everything the server lacks/differs on; the join-time
        // prune against both baselines (server entry + own last sync) happens in one place in
        // the manager via UploadGuards.regresses.
        Manifest m = new Manifest();
        m.tiles.put("DIM0/day/a.png", new TileEntry("hFull", 100_000));
        Map<String, String> local = new HashMap<String, String>();
        local.put("DIM0/day/a.png", "hPartial");
        assertEquals(
            1,
            m.diffUpload(local)
                .size());
    }

    @Test
    public void regressionThresholdSemantics() {
        assertTrue(Manifest.isRegression(100_000, 5_000)); // way smaller
        assertTrue(Manifest.isRegression(100_000, 79_999)); // just under 80%
        assertTrue(!Manifest.isRegression(100_000, 80_000)); // exactly 80% is allowed
        assertTrue(!Manifest.isRegression(100_000, 90_000)); // bigger is fine
        assertTrue(Manifest.isRegression(2_599, 1_545)); // small tiles regress too (no floor)
        assertTrue(!Manifest.isRegression(0, 0));
        assertTrue(!Manifest.isRegression(4_000, 3_500)); // jitter-scale deltas are fine
    }

    @Test
    public void jsonRoundTrip() {
        Manifest m = manifestWith("DIM0/day/a.png", "abc", "DIM-1/night/0,0.png", "def");
        Manifest parsed = Manifest.fromJson(m.toJson());
        assertEquals(2, parsed.tiles.size());
        assertEquals("abc", parsed.tiles.get("DIM0/day/a.png").sha1);
    }

    @Test
    public void emptyJsonGivesEmptyManifest() {
        Manifest parsed = Manifest.fromJson("");
        assertTrue(parsed.tiles.isEmpty());
    }
}
