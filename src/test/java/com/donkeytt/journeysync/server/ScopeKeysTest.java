package com.donkeytt.journeysync.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.UUID;

import org.junit.Test;

public class ScopeKeysTest {

    @Test
    public void globalIsVerbatim() {
        assertEquals("global", ScopeKeys.dirName(ScopeKeys.GLOBAL));
    }

    @Test
    public void colonIsReplaced() {
        // ':' is not allowed in Windows directory names; it becomes '_'.
        assertEquals("team_abc", ScopeKeys.dirName("team:abc"));
        assertEquals(
            "solo_00000000-0000-0000-0000-000000000001",
            ScopeKeys.dirName(ScopeKeys.soloKey(UUID.fromString("00000000-0000-0000-0000-000000000001"))));
    }

    @Test
    public void distinctKeysStayDistinct() {
        assertNotEquals(ScopeKeys.dirName("team:a"), ScopeKeys.dirName("solo:a"));
        assertNotEquals(ScopeKeys.dirName("team:a"), ScopeKeys.dirName("team:b"));
    }
}
