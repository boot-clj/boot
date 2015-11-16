// vim: et:ts=4:sw=4

package boot;

import java.io.*;
import java.nio.channels.FileLock;
import java.nio.channels.FileChannel;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.Date;
import java.util.UUID;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Properties;
import java.util.WeakHashMap;
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
    private static String                   githuburl           = "https://api.github.com/repos/boot-clj/boot/releases";
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

    private static final WeakHashMap<ClojureRuntimeShim, Object> pods = new WeakHashMap<>();
    private static final ConcurrentHashMap<String, String> stash = new ConcurrentHashMap<>();

    public static WeakHashMap<ClojureRuntimeShim, Object>
    getPods() {
        return pods; }

    public static String
    getStash(String key) throws Exception {
        String ret = stash.get(key);
        stash.remove(key);
        return ret; }

    public static String
    setStash(String value) throws Exception {
        String key = UUID.randomUUID().toString();
        stash.put(key, value);
        return key; }

    public static class
    Exit extends Exception {
        public Exit(String m) { super(m); }
        public Exit(String m, Throwable c) { super(m, c); }}

    private static long
    nextId() {
        return counter.addAndGet(1); }

    public static File
    getBootDir() throws Exception {
        return bootdir(); }

    public static boolean
    isWindows() throws Exception {
        return (System.getProperty("os.name").toLowerCase().indexOf("win") >= 0); }

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

    public static InputStream
    resource(String path) throws Exception {
        return tccl().getResourceAsStream(path); }

    public static Properties
    propertiesResource(String path) throws Exception {
        Properties p = new Properties();
        try (InputStream is = resource(path)) { p.load(is); }
        return p; }

    public static File
    bootdir() throws Exception {
        File   h = new File(System.getProperty("user.home"));
        String a = System.getProperty("BOOT_HOME");
        String b = System.getenv("BOOT_HOME");
        String c = new File(h, ".boot").getCanonicalPath();
        return new File((a != null) ? a : ((b != null) ? b : c)); }

    public static String
    md5hash(String data) throws Exception {
        java.security.MessageDigest algo = java.security.MessageDigest.getInstance("MD5");
        return javax.xml.bind.DatatypeConverter.printHexBinary(algo.digest(data.getBytes())); }

    public static File
    projectDir() throws Exception {
        for (File f = workdir; f != null; f = f.getParentFile()) {
            File tmp = new File(f, ".git");
            if (tmp.exists() && tmp.isDirectory()) return f; }
        return null; }

    public static HashMap<String, String>
    properties2map(Properties p) throws Exception {
        HashMap<String, String> m = new HashMap<>();
        for (Map.Entry<Object, Object> e : p.entrySet())
            m.put((String) e.getKey(), (String) e.getValue());
        return m; }

    public static Properties
    map2properties(HashMap<String, String> m) throws Exception {
        Properties p = new Properties();
        for (Map.Entry<String, String> e : m.entrySet())
            p.setProperty(e.getKey(), e.getValue());
        return p; }

    public static HashMap<String, File>
    propertiesFiles() throws Exception {
        HashMap<String, File> ret = new HashMap<>();
        String[] names  = new String[]{"boot", "project", "cwd"};
        File[]   dirs   = new File[]{bootdir(), projectDir(), workdir};
        for (int i = 0; i < dirs.length; i++)
            ret.put(names[i], new File(dirs[i], "boot.properties"));
        return ret; }

    public static Properties
    mergeProperties() throws Exception {
        Properties p = new Properties();
        HashMap<String, File> fs = propertiesFiles();
        for (String k : new String[]{"boot", "project", "cwd"})
            try (FileInputStream is = new FileInputStream(fs.get(k))) {
                p.load(is); }
            catch (FileNotFoundException e) {}
        return p; }

    public static void
    setDefaultProperty(Properties p, String k, String dfl) throws Exception {
        if (p.getProperty(k) == null) p.setProperty(k, dfl); }

    public static HashMap<String, String>
    config() throws Exception {
        HashMap<String, String> ret = new HashMap<>();

        ret.putAll(properties2map(mergeProperties()));
        ret.remove("BOOT_HOME");
        ret.putAll(System.getenv());
        ret.putAll(properties2map(System.getProperties()));

        Iterator<String> i = ret.keySet().iterator();
        while (i.hasNext()) {
            String k = i.next();
            if (! k.startsWith("BOOT_")) i.remove(); }

        return ret; }

    public static String
    config(String k) throws Exception {
        return config().get(k); }

    public static String
    config(String k, String dfl) throws Exception {
        String v = config(k);
        if (v != null) return v;
        else { System.setProperty(k, dfl); return dfl; }}

    private static String
    jarVersion(File f, String prefix) throws Exception {
        String n = f.getName();
        if (! n.startsWith(prefix)) return null;
        else return n.substring(prefix.length()).replaceAll(".jar$", ""); }

    private static Properties
    writeProps(File f) throws Exception {
        mkParents(f);
        ClojureRuntimeShim a = aetherShim();
        Properties         p = new Properties();
        String             c = cljversion;
        String             n = cljname;
        String             t = null;

        try (FileInputStream is = new FileInputStream(f)) {
            p.load(is); }
        catch (FileNotFoundException e) {}

        if (bootversion == null)
            for (File x : resolveDepJars(a, "boot", channel, n, c))
                if (null != (t = jarVersion(x, "boot-"))) bootversion = t;

        p.setProperty("BOOT_VERSION", bootversion);
        setDefaultProperty(p, "BOOT_CLOJURE_NAME",    n);
        setDefaultProperty(p, "BOOT_CLOJURE_VERSION", c);

        try (FileOutputStream os = new FileOutputStream(f)) {
                p.store(os, booturl); }

        return p; }

    private static Properties
    readProps(File f, boolean create) throws Exception {
        mkParents(f);
        FileLock lock = null;
        Properties p  = new Properties();

        if (!isWindows() && f.exists())
            lock = (new RandomAccessFile(f, "rw")).getChannel().lock();

        try (FileInputStream is = new FileInputStream(f)) {
            p.load(is);
            if (p.getProperty("BOOT_CLOJURE_VERSION") == null
                || p.getProperty("BOOT_VERSION") == null)
                throw new Exception("missing info");
            return p; }
        catch (Throwable e) {
            if (! create) return null;
            else return writeProps(f); }
        finally { if (lock != null) lock.release(); }}

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
        mkParents(f);
        try (FileOutputStream os = new FileOutputStream(f);
                ObjectOutputStream oos = new ObjectOutputStream(os)) {
            oos.writeObject(m); }
        return m; }

    private static Object
    readCache(File f) throws Exception {
        mkParents(f);
        FileLock lock = null;
        if (!isWindows())
            lock = (new RandomAccessFile(f, "rw")).getChannel().lock();
        try {
            long max = 18 * 60 * 60 * 1000;
            long age = System.currentTimeMillis() - f.lastModified();
            if (age > max) throw new Exception("cache age exceeds TTL");
            try (FileInputStream is = new FileInputStream(f);
                    ObjectInputStream ois = new ObjectInputStream(is)) {
                return validateCache(f, ois.readObject()); }}
        catch (Throwable e) { return writeCache(f, seedCache()); }
        finally { if (lock != null) lock.release(); }}

    public static ClojureRuntimeShim
    newShim(String name, Object data, File[] jarFiles) throws Exception {
        URL[] urls = new URL[jarFiles.length];

        for (int i=0; i<jarFiles.length; i++) urls[i] = jarFiles[i].toURI().toURL();

        ClassLoader cl = new URLClassLoader(urls, App.class.getClassLoader());
        ClojureRuntimeShim rt = ClojureRuntimeShim.newRuntime(cl);

        rt.setName(name != null ? name : "anonymous");

        File[] hooks = {new File(bootdir(), "boot-shim.clj"), new File("boot-shim.clj")};

        for (File hook : hooks)
          if (hook.exists())
            rt.invoke("clojure.core/load-file", hook.getPath());

        rt.require("boot.pod");
        rt.invoke("boot.pod/seal-app-classloader");
        rt.invoke("boot.pod/set-data!", data);
        rt.invoke("boot.pod/set-pods!", pods);

        pods.put(rt, new Object());
        return rt; }

    public static ClojureRuntimeShim
    newPod(String name, Object data) throws Exception {
        return newShim(name, data, podjars); }

    public static ClojureRuntimeShim
    newPod(String name, Object data, File[] jarFiles) throws Exception {
        File[] files = new File[jarFiles.length + podjars.length];

        for (int i=0; i<podjars.length; i++) files[i] = podjars[i];
        for (int i=0; i<jarFiles.length; i++) files[i + podjars.length] = jarFiles[i];

        return newShim(name, data, files); }

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
        try (InputStream in = resource(resource);
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
            return (t instanceof Exit) ? Integer.parseInt(t.getMessage()) : -2; }
        finally {
            for (Runnable h : hooks) h.run();
            try { core.get().close(); }
            catch (InterruptedException ie) {}}}

    public static String
    readVersion() throws Exception {
        Properties p = new Properties();
        try (InputStream in = resource("boot/base/version.properties")) {
            p.load(in); }
        return p.getProperty("version"); }

    public static void
    printVersion() throws Exception {
        Properties p = new Properties();
        p.setProperty("BOOT_VERSION",         config("BOOT_VERSION"));
        p.setProperty("BOOT_CLOJURE_NAME",    config("BOOT_CLOJURE_NAME"));
        p.setProperty("BOOT_CLOJURE_VERSION", config("BOOT_CLOJURE_VERSION"));
        p.store(System.out, booturl); }

    public static void
    main(String[] args) throws Exception {
        if (System.getProperty("user.name").equals("root")
                && ! config("BOOT_AS_ROOT", "no").equals("yes"))
            throw new Exception("refusing to run as root (set BOOT_AS_ROOT=yes to force)");

        // BOOT_VERSION is decided by the loader; it will respect the
        // boot.properties files, env vars, system properties, etc.
        // or it will use the latest installed version.
        //
        // Since 2.4.0 we can assume that bootversion and appversion
        // are the same (or boot.main will throw an exception).
        bootversion = appversion = readVersion();

        File cachehome   = mkFile(bootdir(), "cache");
        File bootprops   = mkFile(bootdir(), "boot.properties");
        File jardir      = mkFile(cachehome, "lib", appversion);
        File bootcache   = mkFile(cachehome, "cache", "boot");

        localrepo        = config("BOOT_LOCAL_REPO");
        cljversion       = config("BOOT_CLOJURE_VERSION", "1.7.0");
        cljname          = config("BOOT_CLOJURE_NAME", "org.clojure/clojure");
        aetherfile       = mkFile(cachehome, "lib", appversion, aetherjar);

        readProps(bootprops, true);

        if (args.length > 0
            && ((args[0]).equals("-u")
                || (args[0]).equals("--update"))) {
            bootversion  = null;
            Properties p = writeProps(bootprops);
            p.store(System.out, booturl);
            System.exit(0); }

        if (args.length > 0
            && ((args[0]).equals("-V")
                || (args[0]).equals("--version"))) {
            printVersion();
            System.exit(0); }

        String repo  = (localrepo == null)
            ? "default"
            : md5hash((new File(localrepo)).getCanonicalFile().getPath());

        File cachefile = mkFile(bootcache, repo, cljversion, bootversion, "deps.cache");
        HashMap<String, File[]> cache = (HashMap<String, File[]>) readCache(cachefile);

        podjars    = cache.get("boot/pod");
        corejars   = cache.get("boot/core");
        workerjars = cache.get("boot/worker");

        Thread shutdown = new Thread() { public void run() { ex.shutdown(); }};
        Runtime.getRuntime().addShutdownHook(shutdown);
        System.exit(runBoot(newCore(null), newWorker(), args)); }}
