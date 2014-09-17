package com.barbarysoftware.watchservice;

import com.barbarysoftware.jna.*;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class contains the bulk of my implementation of the Watch Service API. It hooks into Carbon's
 * File System Events API.
 *
 * @author Steve McLeod
 */
class MacOSXListeningWatchService extends AbstractWatchService {

    // need to keep reference to callbacks to prevent garbage collection
    @SuppressWarnings({"MismatchedQueryAndUpdateOfCollection"})
    private final List<CarbonAPI.FSEventStreamCallback> callbackList = new ArrayList<CarbonAPI.FSEventStreamCallback>();
    private final List<CFRunLoopThread> threadList = new ArrayList<CFRunLoopThread>();

    @Override
    WatchKey register(WatchableFile watchableFile, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifers) throws IOException {
        final File file = watchableFile.getFile();
        final Map<File, Long> lastModifiedMap = createLastModifiedMap(file);
        final String s = file.getAbsolutePath();
        final Pointer[] values = {CFStringRef.toCFString(s).getPointer()};
        final CFArrayRef pathsToWatch = CarbonAPI.INSTANCE.CFArrayCreate(null, values, CFIndex.valueOf(1), null);
        final MacOSXWatchKey watchKey = new MacOSXWatchKey(this, events, watchableFile);

        final double latency = 1.0; /* Latency in seconds */

        final long kFSEventStreamEventIdSinceNow = -1; //  this is 0xFFFFFFFFFFFFFFFF
        final int kFSEventStreamCreateFlagNoDefer = 0x00000002;
        final CarbonAPI.FSEventStreamCallback callback =
                new MacOSXListeningCallback(file, watchKey, lastModifiedMap);
        callbackList.add(callback);
        final FSEventStreamRef stream = CarbonAPI.INSTANCE.FSEventStreamCreate(
                Pointer.NULL,
                callback,
                Pointer.NULL,
                pathsToWatch,
                kFSEventStreamEventIdSinceNow,
                latency,
                kFSEventStreamCreateFlagNoDefer);

        final CFRunLoopThread thread = new CFRunLoopThread(stream, file);
        thread.setDaemon(true);
        thread.start();
        threadList.add(thread);
        return watchKey;
    }

    public static class CFRunLoopThread extends Thread {

        private final FSEventStreamRef streamRef;
        private CFRunLoopRef runLoop;

        public CFRunLoopThread(FSEventStreamRef streamRef, File file) {
            super("WatchService for " + file);
            this.streamRef = streamRef;
        }

        @Override
        public void run() {
            runLoop = CarbonAPI.INSTANCE.CFRunLoopGetCurrent();
            final CFStringRef runLoopMode = CFStringRef.toCFString("kCFRunLoopDefaultMode");
            CarbonAPI.INSTANCE.FSEventStreamScheduleWithRunLoop(streamRef, runLoop, runLoopMode);
            CarbonAPI.INSTANCE.FSEventStreamStart(streamRef);
            CarbonAPI.INSTANCE.CFRunLoopRun();
        }

        public CFRunLoopRef getRunLoop() {
            return runLoop;
        }

        public FSEventStreamRef getStreamRef() {
            return streamRef;
        }
    }

    private Map<File, Long> createLastModifiedMap(File file) {
        Map<File, Long> lastModifiedMap = new ConcurrentHashMap<File, Long>();
        for (File child : listFiles(file)) {
            lastModifiedMap.put(child, child.lastModified());
        }
        return lastModifiedMap;
    }

    private static Set<File> listFiles(File directory) {
        Set<File> files = new HashSet<File>();
        File[] children = directory.listFiles();
        if (children != null) {
            files.addAll(Arrays.asList(children));
        }
        return files;
    }

    @Override
    void implClose() throws IOException {
        for (CFRunLoopThread thread : threadList) {
            CarbonAPI.INSTANCE.CFRunLoopStop(thread.getRunLoop());
            CarbonAPI.INSTANCE.FSEventStreamStop(thread.getStreamRef());
        }
        threadList.clear();
        callbackList.clear();
    }

    private static File canonical(File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            return file;
        }
    }

    private static class MacOSXListeningCallback implements CarbonAPI.FSEventStreamCallback {
        private final File root;
        private final MacOSXWatchKey watchKey;
        private final Map<File, Long> lastModifiedMap;

        private MacOSXListeningCallback(File root, MacOSXWatchKey watchKey,
                                        Map<File, Long> lastModifiedMap) {
            this.root = canonical(root);
            this.watchKey = watchKey;
            this.lastModifiedMap = lastModifiedMap;
        }

        public void invoke(FSEventStreamRef streamRef, Pointer clientCallBackInfo, NativeLong numEvents, Pointer eventPaths, Pointer /* array of unsigned int */ eventFlags, /* array of unsigned long */ Pointer eventIds) {
            final int length = numEvents.intValue();

            for (String folderName : eventPaths.getStringArray(0, length)) {
                File folder = canonical(new File(folderName));
                if (!folder.equals(root) &&
                        !folder.getParentFile().equals(root)) {
                    continue;
                }
                final Set<File> filesOnDisk = listFiles(root);

                final List<File> createdFiles = findCreatedFiles(filesOnDisk);
                final List<File> modifiedFiles = findModifiedFiles(filesOnDisk);
                final List<File> deletedFiles = findDeletedFiles(filesOnDisk);

                for (File file : createdFiles) {
                    if (watchKey.isReportCreateEvents()) {
                        watchKey.signalEvent(StandardWatchEventKind.ENTRY_CREATE,
                                             new File(file.getName()));
                    }
                    lastModifiedMap.put(file, file.lastModified());
                }

                for (File file : modifiedFiles) {
                    if (watchKey.isReportModifyEvents()) {
                        watchKey.signalEvent(StandardWatchEventKind.ENTRY_MODIFY,
                                             new File(file.getName()));
                    }
                    lastModifiedMap.put(file, file.lastModified());
                }

                for (File file : deletedFiles) {
                    if (watchKey.isReportDeleteEvents()) {
                        watchKey.signalEvent(StandardWatchEventKind.ENTRY_DELETE,
                                             new File(file.getName()));
                    }
                    lastModifiedMap.remove(file);
                }
            }
        }

        private List<File> findModifiedFiles(Set<File> filesOnDisk) {
            List<File> modifiedFileList = new ArrayList<File>();
            for (File file : filesOnDisk) {
                final Long lastModified = lastModifiedMap.get(file);
                if (lastModified != null && lastModified != file.lastModified()) {
                    modifiedFileList.add(file);
                }
            }
            return modifiedFileList;
        }

        private List<File> findCreatedFiles(Set<File> filesOnDisk) {
            List<File> createdFileList = new ArrayList<File>();
            for (File file : filesOnDisk) {
                if (!lastModifiedMap.containsKey(file)) {
                    createdFileList.add(file);
                }
            }
            return createdFileList;
        }

        private List<File> findDeletedFiles(Set<File> filesOnDisk) {
            List<File> deletedFileList = new ArrayList<File>();
            for (File file : lastModifiedMap.keySet()) {
                if (!filesOnDisk.contains(file)) {
                    deletedFileList.add(file);
                }
            }
            return deletedFileList;
        }
    }
}
