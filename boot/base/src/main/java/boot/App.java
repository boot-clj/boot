// vim: et:ts=4:sw=4

package boot;

import java.io.*;
import java.nio.channels.FileLock;
import java.nio.channels.FileChannel;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.Formatter;
import java.util.Map;
import java.util.Date;
import java.util.UUID;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Properties;
import java.util.WeakHashMap;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.projectodd.shimdandy.ClojureRuntimeShim;

@SuppressWarnings("unchecked")
public class App {
    private static File                     aetherfile          = null;
    private static File[]                   podjars             = null;
    private static File[]                   corejars            = null;
    private static File[]                   workerjars          = null;
    private static String                   cljversion          = null;
    private static String                   cljname             = null;
    private static String                   bootversion         = null;
    private static String                   localrepo           = null;
    private static String                   appversion          = null;
    private static String                   channel             = "RELEASE";
    private static String                   booturl             = "http://boot-clj.com";
    private static boolean                  update_always       = false;
    private static ClojureRuntimeShim       aethershim          = null;
    private static HashMap<String, File[]>  depsCache           = null;

    private static final File               homedir             = new File(System.getProperty("user.home"));
    private static final File               workdir             = new File(System.getProperty("user.dir"));
    private static final String             aetherjar           = "aether.uber.jar";
    private static final AtomicLong         counter             = new AtomicLong(0);
    private static final ExecutorService    ex                  = Executors.newCachedThreadPool();

    public  static       String             getVersion()        { return appversion; }
    public  static       String             getBootVersion()    { return bootversion; }
    public  static       String             getClojureName()    { return cljname; }

    private static final WeakHashMap<ClojureRuntimeShim, Object> pods  = new WeakHashMap<>();

    public static WeakHashMap<ClojureRuntimeShim, Object>
    getPods() { return pods; }

    public static class
    Exit extends Exception {
        public Exit(String m) { super(m); }
        public Exit(String m, Throwable c) { super(m, c); }}

    private static long
    nextId() {
        return counter.addAndGet(1); }

    public static File
    mkFile(File parent, String... kids) throws Exception {
        File ret = parent;
        for (String k : kids)
            ret = new File(ret, k);
        return ret; }

    public static void
    mkParents(File f) throws Exception {
        File ff = f.getCanonicalFile().getParentFile();
        if (! ff.exists()) ff.mkdirs(); }

    public static ClassLoader
    tccl() throws Exception {
        return Thread.currentThread().getContextClassLoader(); }

    public static File
    bootdir() throws Exception {
        File   h = new File(System.getProperty("user.home"));
        String a = System.getProperty("BOOT_HOME");
        String b = System.getenv("BOOT_HOME");
        String c = new File(h, ".boot").getCanonicalPath();
        return new File((a != null) ? a : ((b != null) ? b : c)); }

    public static ClojureRuntimeShim
    newShim(String name, Object data, File[] jarFiles) throws Exception {
        URL[] urls = new URL[jarFiles.length];

        for (int i=0; i<jarFiles.length; i++) urls[i] = jarFiles[i].toURI().toURL();

        ClassLoader cl = new AddableClassLoader(urls, App.class.getClassLoader());
        ClojureRuntimeShim rt = ClojureRuntimeShim.newRuntime(cl);

        rt.setName(name != null ? name : "anonymous");

        File[] hooks = {new File(bootdir(), "boot-shim.clj"), new File("boot-shim.clj")};

        for (File hook : hooks)
          if (hook.exists())
            rt.invoke("clojure.core/load-file", hook.getPath());

        rt.require("boot.pod");
        rt.invoke("boot.pod/seal-app-classloader");
        rt.invoke("boot.pod/extend-addable-classloader");
        rt.invoke("boot.pod/set-data!", data);
        rt.invoke("boot.pod/set-pods!", pods);
        rt.invoke("boot.pod/set-this-pod!", new WeakReference<ClojureRuntimeShim>(rt));

        pods.put(rt, new Object());
        return rt; }

    private static ClojureRuntimeShim
    aetherShim() throws Exception {
        if (aethershim == null) {
            ensureResourceFile(aetherjar, aetherfile);
            aethershim = newShim("aether", null, new File[] { aetherfile }); }
        return aethershim; }

    public static void
    extractResource(String resource, File outfile) throws Exception {
        mkParents(outfile);
        int    n   = 0;
        byte[] buf = new byte[4096];
        try (InputStream in = tccl().getResourceAsStream(resource);
                OutputStream out = new FileOutputStream(outfile)) {
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n); }}

    public static void
    ensureResourceFile(String r, File f) throws Exception {
        if (! f.exists()) extractResource(r, f); }

    public static File[]
    resolveDepJars(ClojureRuntimeShim shim, String sym) {
        return resolveDepJars(shim, sym, bootversion, cljname, cljversion); }

    public static File[]
    resolveDepJars(ClojureRuntimeShim shim, String sym, String bootversion, String cljname, String cljversion) {
        shim.require("boot.aether");
        if (localrepo != null)
            shim.invoke("boot.aether/set-local-repo!", localrepo);
        if (update_always)
            shim.invoke("boot.aether/update-always!");
        return (File[]) shim.invoke(
            "boot.aether/resolve-dependency-jars", sym, bootversion, cljname, cljversion); }

    public static Future<ClojureRuntimeShim>
    newShimFuture(final String name, final Object data, final File[] jars) throws Exception {
        return ex.submit(new Callable() {
                public ClojureRuntimeShim
                call() throws Exception { return newShim(name, data, jars); }}); }

    public static Future<ClojureRuntimeShim>
    newCore(Object data) throws Exception { return newShimFuture("core", data, corejars); }

    public static Future<ClojureRuntimeShim>
    newWorker() throws Exception { return newShimFuture("worker", null, workerjars); }

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
            if (t instanceof Exit) return Integer.parseInt(t.getMessage());
            System.out.println("Boot failed to start:");
            t.printStackTrace();
            return -2; }
        finally {
            for (Runnable h : hooks) h.run();
            try { core.get().close(); }
            catch (InterruptedException ie) {}}}

    public static void
    main(String[] args) throws Exception {

        Thread shutdown = new Thread() { public void run() { ex.shutdown(); }};
        Runtime.getRuntime().addShutdownHook(shutdown);
        System.exit(runBoot(newCore(null), newWorker(), args)); }}
