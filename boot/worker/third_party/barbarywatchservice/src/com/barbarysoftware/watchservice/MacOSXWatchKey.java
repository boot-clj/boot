package com.barbarysoftware.watchservice;

import java.util.concurrent.atomic.AtomicBoolean;

public class MacOSXWatchKey extends AbstractWatchKey {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final boolean reportCreateEvents;
    private final boolean reportModifyEvents;
    private final boolean reportDeleteEvents;
    private final WatchableFile watchableFile;

    public MacOSXWatchKey(AbstractWatchService macOSXWatchService, WatchEvent.Kind<?>[] events, WatchableFile wf) {
        super(macOSXWatchService);
        boolean reportCreateEvents = false;
        boolean reportModifyEvents = false;
        boolean reportDeleteEvents = false;

        for (WatchEvent.Kind<?> event : events) {
            if (event == StandardWatchEventKind.ENTRY_CREATE) {
                reportCreateEvents = true;
            } else if (event == StandardWatchEventKind.ENTRY_MODIFY) {
                reportModifyEvents = true;
            } else if (event == StandardWatchEventKind.ENTRY_DELETE) {
                reportDeleteEvents = true;
            }
        }
        this.reportCreateEvents = reportCreateEvents;
        this.reportDeleteEvents = reportDeleteEvents;
        this.reportModifyEvents = reportModifyEvents;
        this.watchableFile = wf;
    }

    @Override
    public boolean isValid() {
        return !cancelled.get() && watcher().isOpen();
    }

    @Override
    public void cancel() {
        cancelled.set(true);
    }

    public WatchableFile watchable() {
        return watchableFile;
    }
    
    public boolean isReportCreateEvents() {
        return reportCreateEvents;
    }

    public boolean isReportModifyEvents() {
        return reportModifyEvents;
    }

    public boolean isReportDeleteEvents() {
        return reportDeleteEvents;
    }
}
