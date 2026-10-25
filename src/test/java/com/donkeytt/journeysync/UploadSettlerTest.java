package com.donkeytt.journeysync.client;

import java.util.HashSet;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

public class UploadSettlerTest {

    private static final long SETTLE = 3000;

    private final UploadSettler settler = new UploadSettler(SETTLE);

    @Test
    public void firesOnceAfterStatStaysUnchangedForSettlePeriod() {
        Assert.assertFalse(settler.observe("DIM0/day/0,0.png", 100, 50, 0));
        Assert.assertFalse(settler.observe("DIM0/day/0,0.png", 100, 50, SETTLE - 1));
        Assert.assertTrue(settler.observe("DIM0/day/0,0.png", 100, 50, SETTLE));
        // already fired for this episode - must not fire again while stat is unchanged
        Assert.assertFalse(settler.observe("DIM0/day/0,0.png", 100, 50, SETTLE * 10));
    }

    @Test
    public void statChangeStartsNewEpisode() {
        settler.observe("a", 100, 50, 0);
        Assert.assertTrue(settler.observe("a", 100, 50, SETTLE)); // episode 1 fires
        Assert.assertFalse(settler.observe("a", 100, 50, SETTLE * 2)); // already fired, stat same
        Assert.assertFalse(settler.observe("a", 200, 50, SETTLE * 2 + 1)); // changed: new episode
        Assert.assertFalse(settler.observe("a", 200, 50, SETTLE * 2 + 1 + SETTLE - 1));
        Assert.assertTrue(settler.observe("a", 200, 50, SETTLE * 2 + 1 + SETTLE)); // episode 2 fires
    }

    @Test
    public void sizeChangeAlsoStartsNewEpisode() {
        settler.observe("a", 100, 50, 0);
        Assert.assertFalse(settler.observe("a", 100, 51, 1000));
        Assert.assertTrue(settler.observe("a", 100, 51, 1000 + SETTLE));
    }

    @Test
    public void clearResetsTracking() {
        settler.observe("a", 100, 50, 0);
        settler.clear("a");
        Assert.assertFalse(settler.observe("a", 100, 50, SETTLE * 5)); // fresh episode
        Assert.assertFalse(settler.observe("a", 100, 50, SETTLE * 5 + SETTLE - 1));
        Assert.assertTrue(settler.observe("a", 100, 50, SETTLE * 5 + SETTLE));
    }

    @Test
    public void retainAllDropsUnseenTiles() {
        settler.observe("a", 100, 50, 0);
        settler.observe("b", 100, 50, 0);
        Set<String> seen = new HashSet<String>();
        seen.add("a");
        settler.retainAll(seen);
        Assert.assertEquals(1, settler.pendingCount());
        // b starts a fresh episode after being dropped
        Assert.assertFalse(settler.observe("b", 100, 50, SETTLE * 5));
    }

    @Test
    public void zeroSettleIsDisabled() {
        Assert.assertTrue(new UploadSettler(0).disabled());
        Assert.assertTrue(new UploadSettler(-1).disabled());
        Assert.assertFalse(settler.disabled());
    }

    @Test
    public void tracksTilesIndependently() {
        Assert.assertFalse(settler.observe("a", 100, 50, 0));
        Assert.assertFalse(settler.observe("b", 100, 50, 0));
        Assert.assertTrue(settler.observe("a", 100, 50, SETTLE));
        Assert.assertTrue(settler.observe("b", 100, 50, SETTLE));
    }

    @Test
    public void fireIfSettledIgnoresUntrackedTiles() {
        Assert.assertFalse(settler.fireIfSettled("ghost", 100, 50, SETTLE * 100));
        Assert.assertEquals(0, settler.pendingCount());
    }

    @Test
    public void fireIfSettledCompletesEpisodeObservedOnChangeScans() {
        // The drift scan observes the new stat; later unchanged scans (which never call
        // observe()) must still be able to fire once the stat has stayed stable.
        Assert.assertFalse(settler.observe("a", 100, 50, 1000));
        Assert.assertFalse(settler.fireIfSettled("a", 100, 50, 1000 + SETTLE - 1));
        Assert.assertTrue(settler.fireIfSettled("a", 100, 50, 1000 + SETTLE));
        Assert.assertFalse(settler.fireIfSettled("a", 100, 50, 1000 + SETTLE * 10)); // once only
    }

    @Test
    public void fireIfSettledResetsEpisodeOnStatChange() {
        settler.observe("a", 100, 50, 0);
        Assert.assertFalse(settler.fireIfSettled("a", 200, 50, SETTLE * 5)); // stat moved: new episode
        Assert.assertFalse(settler.fireIfSettled("a", 200, 50, SETTLE * 5 + SETTLE - 1));
        Assert.assertTrue(settler.fireIfSettled("a", 200, 50, SETTLE * 5 + SETTLE));
    }
}
