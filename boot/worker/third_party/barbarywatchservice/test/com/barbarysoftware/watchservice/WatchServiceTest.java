package com.barbarysoftware.watchservice;

import static com.barbarysoftware.watchservice.StandardWatchEventKind.*;
import org.junit.Assert;

import java.io.File;

public class WatchServiceTest {
    @org.junit.Test
    public void testNewWatchService() throws Exception {
        Assert.assertNotNull(WatchService.newWatchService());
    }

    @org.junit.Test
    public void testWatchingInvalidFolder() throws Exception {
        final WatchService watcher = WatchService.newWatchService();
        WatchableFile f = new WatchableFile(new File("/thisfolderdoesntexist"));
        f.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
    }

    @org.junit.Test
    public void testNonsensePath() throws Exception {
        final WatchService watcher = WatchService.newWatchService();
        WatchableFile f = new WatchableFile(new File("/path/to/watch"));
        f.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
    }

    @org.junit.Test(expected = NullPointerException.class)
    public void testWatchingNull() throws Exception {
        new WatchableFile(null);
    }

    @org.junit.Test
    public void testWatchingFile() throws Exception {
        final WatchService watcher = WatchService.newWatchService();
        WatchableFile f = new WatchableFile(File.createTempFile("watcher_", null));
        f.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
    }
}
