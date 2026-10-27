package com.donkeytt.journeysync.client;

import java.util.HashSet;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import com.donkeytt.journeysync.client.QuietPeriods.Type;

public class QuietPeriodsTest {

    private final QuietPeriods quiets = new QuietPeriods();

    @Test
    public void quietInsideWindowOnly() {
        quiets.quietUntil(Type.UPLOAD, "DIM0/day/0,0.png", 1_000);
        Assert.assertTrue(quiets.isQuiet(Type.UPLOAD, "DIM0/day/0,0.png", 999));
        Assert.assertFalse(quiets.isQuiet(Type.UPLOAD, "DIM0/day/0,0.png", 1_000)); // deadline reached
        Assert.assertFalse(quiets.isQuiet(Type.UPLOAD, "DIM0/day/0,0.png", 2_000)); // expired entry removed
    }

    @Test
    public void typesAreIndependent() {
        quiets.quietUntil(Type.UPLOAD, "a", 1_000);
        Assert.assertFalse(quiets.isQuiet(Type.INBOUND, "a", 500));
        Assert.assertFalse(quiets.isQuiet(Type.RESTORE, "a", 500));
        Assert.assertTrue(quiets.isQuiet(Type.UPLOAD, "a", 500));
    }

    @Test
    public void untrackedTilesAreNeverQuiet() {
        Assert.assertFalse(quiets.isQuiet(Type.RESTORE, "ghost", 0));
    }

    @Test
    public void retainAllDropsVanishedTilesAcrossTypes() {
        quiets.quietUntil(Type.UPLOAD, "a", 1_000);
        quiets.quietUntil(Type.INBOUND, "b", 1_000);
        quiets.quietUntil(Type.RESTORE, "c", 1_000);
        Set<String> live = new HashSet<String>();
        live.add("a");
        quiets.retainAll(live);
        Assert.assertTrue(quiets.isQuiet(Type.UPLOAD, "a", 500));
        Assert.assertFalse(quiets.isQuiet(Type.INBOUND, "b", 500));
        Assert.assertFalse(quiets.isQuiet(Type.RESTORE, "c", 500));
    }

    @Test
    public void clearDropsEverything() {
        quiets.quietUntil(Type.UPLOAD, "a", Long.MAX_VALUE);
        quiets.clear();
        Assert.assertFalse(quiets.isQuiet(Type.UPLOAD, "a", 0));
    }
}
