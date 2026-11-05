package com.donkeytt.journeysync.server;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class WaypointEpochTest {

    private static File tempFile() throws Exception {
        File dir = Files.createTempDirectory("jsepoch")
            .toFile();
        dir.deleteOnExit();
        return new File(dir, "epoch");
    }

    @Test
    public void freshCounterStartsAtOne() throws Exception {
        WaypointEpoch e = new WaypointEpoch(tempFile());
        assertEquals(0, e.current());
        assertEquals(1, e.next());
        assertEquals(2, e.next());
    }

    @Test
    public void persistsAcrossInstances() throws Exception {
        File f = tempFile();
        WaypointEpoch e = new WaypointEpoch(f);
        e.next();
        e.next();
        e.next();
        e.save();

        WaypointEpoch reloaded = new WaypointEpoch(f);
        assertEquals(3, reloaded.current());
        assertEquals(4, reloaded.next());
    }

    @Test
    public void raiseFloorOnlyGrows() throws Exception {
        WaypointEpoch e = new WaypointEpoch(tempFile());
        e.next(); // 1
        e.raiseFloor(50);
        assertEquals(50, e.current());
        e.raiseFloor(10); // lower floors never rewind the counter
        assertEquals(50, e.current());
        assertEquals(51, e.next());
    }

    @Test
    public void loadsStoreEpochFileFormat() throws Exception {
        File f = tempFile();
        Files.write(f.toPath(), "{\"epoch\": 17}".getBytes(StandardCharsets.UTF_8));
        WaypointEpoch e = new WaypointEpoch(f);
        assertEquals(18, e.next());
    }
}
