package boot;

import java.io.*;
import java.nio.channels.FileLock;
import java.nio.channels.FileChannel;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.projectodd.shimdandy.ClojureRuntimeShim;

@SuppressWarnings("unchecked")
public class App {
    private static File[]                  podjars    = null;
    private static File[]                  corejars   = null;
    private static File[]                  workerjars = null;
    private static File                    bootdir    = null;
    private static File                    aetherfile = null;
    private static HashMap<String, File[]> depsCache  = null;
    private static String                  cljversion = "1.6.0";
    private static String                  localrepo  = null;

    private static final String            appversion = "2.0.0";
    private static final String            apprelease = "r1";
    private static final String            depversion = appversion + "-SNAPSHOT";
    private static final String            aetherjar  = "aether-" + depversion + ".uber.jar";
    private static final AtomicLong        counter    = new AtomicLong(0);
    private static final ExecutorService   ex         = Executors.newCachedThreadPool();

    private static long  nextId()     { return counter.addAndGet(1); }
    
    public static File   getBootDir() { return bootdir; }
    public static String getVersion() { return appversion; }
    public static String getRelease() { return apprelease; }

    public static class Exit extends Exception {
        public Exit(String m) { super(m); }
        public Exit(String m, Throwable c) { super(m, c); }}
    
    private static FileLock
    getLock(File f) throws Exception {
        File lockfile = new File(f.getPath() + ".lock");
        return (new RandomAccessFile(lockfile, "rw")).getChannel().lock(); }

    private static HashMap<String, File[]>
    seedCache() throws Exception {
        if (depsCache != null) return depsCache;
        else {
            ensureResourceFile(aetherjar, aetherfile);
            ClojureRuntimeShim a = newShim(new File[] { aetherfile });
        
            HashMap<String, File[]> cache = new HashMap<>();
        
            cache.put("boot/pod",    resolveDepJars(a, "boot/pod"));
            cache.put("boot/core",   resolveDepJars(a, "boot/core"));
            cache.put("boot/worker", resolveDepJars(a, "boot/worker"));

            return depsCache = cache; }}
    
    private static Object
    validateCache(File f, Object cache) throws Exception {
        for (File[] fs : ((HashMap<String, File[]>) cache).values())
            for (File d : fs)
                if (! d.exists() || f.lastModified() < d.lastModified())
                    throw new Exception("dep jar doesn't exist");
        return cache; }

    private static Object
    writeCache(File f, Object m) throws Exception {
        FileOutputStream file = new FileOutputStream(f);
        try { (new ObjectOutputStream(file)).writeObject(m); }
        finally { file.close(); }
        return m; }
    
    private static Object
    readCache(File f) throws Exception {
        FileLock lock = getLock(f);
        try {
            long max = 18 * 60 * 60 * 1000;
            long age = System.currentTimeMillis() - f.lastModified();
            if (age > max) throw new Exception("cache age exceeds TTL");
            return validateCache(f, (new ObjectInputStream(new FileInputStream(f))).readObject()); }
        catch (Throwable e) {
            System.err.println("Checking for boot updates...");
            return writeCache(f, seedCache()); }
        finally { lock.release(); }}
    
    public static ClojureRuntimeShim
    newShim(File[] jarFiles) throws Exception {
        URL[] urls = new URL[jarFiles.length];
        
        for (int i=0; i<jarFiles.length; i++) urls[i] = jarFiles[i].toURI().toURL();
        
        ClassLoader cl = new URLClassLoader(urls, App.class.getClassLoader());
        ClojureRuntimeShim rt = ClojureRuntimeShim.newRuntime(cl);

        rt.require("boot.pod");
        rt.invoke("boot.pod/seal-app-classloader");

        return rt; }
    
    public static ClojureRuntimeShim
    newPod() throws Exception { return newShim(podjars); }
    
    public static ClojureRuntimeShim
    newPod(File[] jarFiles) throws Exception {
        File[] files = new File[jarFiles.length + podjars.length];
        
        for (int i=0; i<podjars.length; i++) files[i] = podjars[i];
        for (int i=0; i<jarFiles.length; i++) files[i + podjars.length] = jarFiles[i];
        
        return newShim(files); }
    
    public static void
    extractResource(String resource, File outfile) throws Exception {
        ClassLoader  cl  = Thread.currentThread().getContextClassLoader();
        InputStream  in  = cl.getResourceAsStream(resource);
        OutputStream out = new FileOutputStream(outfile);
        int          n   = 0;
        byte[]       buf = new byte[4096];

        try { while ((n = in.read(buf)) > 0) out.write(buf, 0, n); }
        finally { in.close(); out.close(); }}
    
    public static void
    ensureResourceFile(String r, File f) throws Exception {
        if (! f.exists()) extractResource(r, f); }
    
    public static File[]
    resolveDepJars(ClojureRuntimeShim shim, String sym) {
        shim.require("boot.aether");
        if (localrepo != null)
            shim.invoke("boot.aether/set-local-repo!", localrepo);
        return (File[]) shim.invoke(
            "boot.aether/resolve-dependency-jars", sym, depversion, cljversion); }
    
    public static Future<ClojureRuntimeShim>
    newShimFuture(final File[] jars) throws Exception {
        return ex.submit(new Callable() {
                public ClojureRuntimeShim
                call() throws Exception { return newShim(jars); }}); }
                
    public static Future<ClojureRuntimeShim>
    newCore() throws Exception { return newShimFuture(corejars); }
    
    public static Future<ClojureRuntimeShim>
    newWorker() throws Exception { return newShimFuture(workerjars); }
    
    public static int
    runBoot(Future<ClojureRuntimeShim> core,
            Future<ClojureRuntimeShim> worker,
            String[] args) throws Exception {
        ConcurrentLinkedQueue<Runnable>
        hooks = new ConcurrentLinkedQueue<>();
        try {
            if (localrepo != null) {
                worker.get().require("boot.aether");
                worker.get().invoke("boot.aether/set-local-repo!", localrepo); }
            core.get().require("boot.main");
            core.get().invoke("boot.main/-main", nextId(), worker.get(), hooks, args);
            return -1; }
        catch (Throwable t) {
            return (t instanceof Exit) ? Integer.parseInt(t.getMessage()) : -2; }
        finally {
            for (Runnable h : hooks) h.run();
            core.get().invoke("clojure.core/shutdown-agents"); }}
                
    public static void
    main(String[] args) throws Exception {
        if (args.length > 0
            && ((args[0]).equals("-V")
                || (args[0]).equals("--version"))) {
            System.err.println(appversion + "-" + apprelease);
            System.exit(0); }
        
        localrepo    = System.getenv("BOOT_LOCAL_REPO");
        String bhome = System.getenv("BOOT_HOME");
        String homed = System.getProperty("user.home");
        String clj_v = System.getenv("BOOT_CLOJURE_VERSION");
        String dir_l = (localrepo == null) ? "default" : String.valueOf(localrepo.hashCode());
        
        if (clj_v != null) cljversion = clj_v;
        
        if (bhome != null) bootdir = new File(bhome);
        else bootdir = new File(new File(homed), ".boot");

        File jardir    = new File(new File(bootdir, "lib"), apprelease);
        aetherfile     = new File(jardir, aetherjar);
        File cachedir  = new File(new File(new File(bootdir, "cache"), dir_l), cljversion);
        File cachefile = new File(cachedir, "deps.cache");
        
        jardir.mkdirs();
        cachedir.mkdirs();
        
        HashMap<String, File[]> cache = (HashMap<String, File[]>) readCache(cachefile);

        podjars    = cache.get("boot/pod");
        corejars   = cache.get("boot/core");
        workerjars = cache.get("boot/worker");
        
        Thread shutdown = new Thread() { public void run() { ex.shutdown(); }};
        Runtime.getRuntime().addShutdownHook(shutdown);

        System.exit(runBoot(newCore(), newWorker(), args)); }}
