package com.donkeytt.journeysync.client;

import org.junit.Assert;
import org.junit.Test;

import com.donkeytt.journeysync.client.ClientState.TileState;
import com.donkeytt.journeysync.client.UploadGuards.Verdict;
import com.donkeytt.journeysync.data.TileEntry;

public class UploadGuardsTest {

    @Test
    public void failedTilesAreNeverRetried() {
        Assert.assertEquals(Verdict.SKIP_FAILED, UploadGuards.verdict("hNew", 100, true, null, null, false));
    }

    @Test
    public void contentMatchingOurLastSyncIsSkipped() {
        TileState recorded = new TileState("hSynced", 1, 100);
        Assert.assertEquals(Verdict.SKIP_SYNCED, UploadGuards.verdict("hSynced", 100, false, recorded, null, false));
    }

    @Test
    public void contentMatchingTheServerEntryIsSkipped() {
        TileEntry server = new TileEntry("hServer", 100);
        Assert.assertEquals(Verdict.SKIP_SERVER_HAS, UploadGuards.verdict("hServer", 100, false, null, server, false));
    }

    @Test
    public void cooldownBlocksJitterButNotGrowth() {
        TileState recorded = new TileState("hSynced", 1, 100);
        // inside the cooldown, same-ish size -> cosmetic jitter, skipped
        Assert.assertEquals(Verdict.SKIP_THROTTLED, UploadGuards.verdict("hNew", 100, false, recorded, null, true));
        // grew meaningfully (> +25%) -> real exploration, allowed through
        Assert.assertEquals(Verdict.PROCEED, UploadGuards.verdict("hNew", 126, false, recorded, null, true));
        // no cooldown active -> allowed
        Assert.assertEquals(Verdict.PROCEED, UploadGuards.verdict("hNew", 100, false, recorded, null, false));
    }

    @Test
    public void cooldownBlocksEverythingWithoutABaseline() {
        // unknown baseline size: cannot prove growth, treat as jitter
        Assert.assertTrue(UploadGuards.cooldownBlocks(1_000_000, 0));
        // threshold semantics: growth beyond +25% is allowed, +25% exactly is still jitter
        Assert.assertTrue(UploadGuards.cooldownBlocks(125, 100));
        Assert.assertFalse(UploadGuards.cooldownBlocks(126, 100));
    }

    @Test
    public void regressionDetectsAgainstEitherBaseline() {
        TileEntry server = new TileEntry("hServer", 100_000);
        TileState recorded = new TileState("hSynced", 1, 100_000);
        // regresses against the server entry
        Assert.assertTrue(UploadGuards.regresses(server, null, 10_000));
        // regresses against our own last sync
        Assert.assertTrue(UploadGuards.regresses(null, recorded, 10_000));
        // both baselines smaller/absent: fine
        Assert.assertFalse(UploadGuards.regresses(server, recorded, 120_000));
        Assert.assertFalse(UploadGuards.regresses(null, null, 10_000));
    }
}
