package boot;

import java.io.*;
import java.nio.channels.FileLock;
import java.nio.channels.FileChannel;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.projectodd.shimdandy.ClojureRuntimeShim;

@SuppressWarnings("unchecked")
public class App {
    private static File[]                  podjars     = null;
    private static File[]                  corejars    = null;
    private static File[]                  workerjars  = null;
    private static File                    bootdir     = null;
    private static File                    aetherfile  = null;
    private static HashMap<String, File[]> depsCache   = null;
    private static String                  cljversion  = null;
    private static String                  bootversion = null;
    private static String                  localrepo   = null;
    private static String                  appversion  = null;
    private static String                  channel     = "RELEASE";
    private static ClojureRuntimeShim      aethershim  = null;

    private static final String            aetherjar   = "aether.uber.jar";
    private static final AtomicLong        counter     = new AtomicLong(0);
    private static final ExecutorService   ex          = Executors.newCachedThreadPool();

    private static long  nextId()         { return counter.addAndGet(1); }

    public static File   getBootDir()     { return bootdir; }
    public static String getVersion()     { return appversion; }
    public static String getBootVersion() { return bootversion; }

    public static class Exit extends Exception {
        public Exit(String m) { super(m); }
        public Exit(String m, Throwable c) { super(m, c); }}
    
    private static String
    jarVersion(File f, String prefix) throws Exception {
        String n = f.getName();
        if (! n.startsWith(prefix))
            return null;
        else return n.substring(prefix.length()).replaceAll(".jar$", ""); }

    private static Properties
    writeProps(File f) throws Exception {
        ClojureRuntimeShim a = aetherShim();
        Properties         p = new Properties();
        String             c = cljversion;
        String             t = null;
        
        if (c == null) c = "1.6.0";
        
        if (bootversion != null) 
            p.setProperty("BOOT_VERSION", bootversion);
        else
            for (File x : resolveDepJars(a, "boot", channel, c))
                if (null != (t = jarVersion(x, "boot-")))
                    p.setProperty("BOOT_VERSION", t);

        p.setProperty("BOOT_CLOJURE_VERSION", c);

        try (FileOutputStream file = new FileOutputStream(f)) {
                p.store(file, "boot: https://github.com/tailrecursion/boot"); }
        
        return p; }
    
    private static Properties
    readProps(File f, boolean create) throws Exception {
        FileLock lock = (new RandomAccessFile(f, "rw")).getChannel().lock();
        Properties p = new Properties();
        try {
            p.load(new FileInputStream(f));
            if (p.getProperty("BOOT_CLOJURE_VERSION") == null
                || p.getProperty("BOOT_VERSION") == null)
                throw new Exception("missing info");
            return p; }
        catch (Throwable e) {
            if (! create) return null;
            else return writeProps(f); }
        finally { lock.release(); }}
        
    private static HashMap<String, File[]>
    seedCache() throws Exception {
        if (depsCache != null) return depsCache;
        else {
            ClojureRuntimeShim a = aetherShim();
        
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
        try (FileOutputStream file = new FileOutputStream(f)) {
                (new ObjectOutputStream(file)).writeObject(m); }
        return m; }
    
    private static Object
    readCache(File f) throws Exception {
        FileLock lock = (new RandomAccessFile(f, "rw")).getChannel().lock();
        try {
            long max = 18 * 60 * 60 * 1000;
            long age = System.currentTimeMillis() - f.lastModified();
            if (age > max) throw new Exception("cache age exceeds TTL");
            return validateCache(f, (new ObjectInputStream(new FileInputStream(f))).readObject()); }
        catch (Throwable e) { return writeCache(f, seedCache()); }
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
    
    private static ClojureRuntimeShim
    aetherShim() throws Exception {
        if (aethershim == null) {
            ensureResourceFile(aetherjar, aetherfile);
            aethershim = newShim(new File[] { aetherfile }); }
        return aethershim; }
        
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
        return resolveDepJars(shim, sym, bootversion, cljversion); }

    public static File[]
    resolveDepJars(ClojureRuntimeShim shim, String sym, String bootversion, String cljversion) {
        shim.require("boot.aether");
        if (localrepo != null)
            shim.invoke("boot.aether/set-local-repo!", localrepo);
        return (File[]) shim.invoke(
            "boot.aether/resolve-dependency-jars", sym, bootversion, cljversion); }
    
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
    usage() throws Exception {
        System.out.printf("Boot App Version: %s\n", appversion);
        System.out.printf("Boot Lib Version: %s\n", bootversion);
        System.out.printf("Clojure Version:  %s\n", cljversion); }
                
    public static String
    readVersion() throws Exception {
        ClassLoader cl  = Thread.currentThread().getContextClassLoader();
        InputStream in  = cl.getResourceAsStream("boot/base/version.properties");
        Properties   p  = new Properties();
        p.load(in);
        return p.getProperty("version"); }

    public static void
    main(String[] args) throws Exception {
        appversion    = readVersion();
        localrepo     = System.getenv("BOOT_LOCAL_REPO");
        String bhome  = System.getenv("BOOT_HOME");
        String homed  = System.getProperty("user.home");
        String boot_v = System.getenv("BOOT_VERSION");
        String clj_v  = System.getenv("BOOT_CLOJURE_VERSION");
        String chan   = System.getenv("BOOT_CHANNEL");
        
        if (chan != null && chan.equals("DEV")) channel = "(0.0.0,)";

        String dir_l  = (localrepo == null) ? "default" : String.valueOf(localrepo.hashCode());
        
        if (clj_v != null) cljversion = clj_v;
        if (boot_v != null) bootversion = boot_v;
        
        if (bhome != null) bootdir = new File(bhome);
        else bootdir = new File(new File(homed), ".boot");
        
        File projectprops = new File("boot.properties");
        File bootprops    = new File(bootdir, "boot.properties");
        File jardir       = new File(new File(bootdir, "lib"), appversion);
        aetherfile        = new File(jardir, aetherjar);

        jardir.mkdirs();

        if (args.length > 0
            && ((args[0]).equals("-u")
                || (args[0]).equals("--update"))) {
            Properties p = writeProps(bootprops);
            p.store(System.out, "boot: https://github.com/tailrecursion/boot");
            System.exit(0); }

        if (cljversion == null || bootversion == null) {
            Properties q = readProps(bootprops, true);
            Properties p = readProps(projectprops, false);
            p = (p == null) ? q : p;
            if (cljversion == null) cljversion = p.getProperty("BOOT_CLOJURE_VERSION");
            if (bootversion == null) bootversion = p.getProperty("BOOT_VERSION"); }
        
        if (args.length > 0
            && ((args[0]).equals("-V")
                || (args[0]).equals("--version"))) {
            Properties p = new Properties();
            p.setProperty("BOOT_VERSION", bootversion);
            p.setProperty("BOOT_CLOJURE_VERSION", cljversion);
            p.store(System.out, "boot: https://github.com/tailrecursion/boot");
            System.exit(0); }

        File cachedir  = new File(new File(new File(new File(bootdir, "cache"), dir_l), cljversion), bootversion);
        File cachefile = new File(cachedir, "deps.cache");
        
        cachedir.mkdirs();
        
        HashMap<String, File[]> cache = (HashMap<String, File[]>) readCache(cachefile);

        podjars    = cache.get("boot/pod");
        corejars   = cache.get("boot/core");
        workerjars = cache.get("boot/worker");
        
        Thread shutdown = new Thread() { public void run() { ex.shutdown(); }};
        Runtime.getRuntime().addShutdownHook(shutdown);

        System.exit(runBoot(newCore(), newWorker(), args)); }}
