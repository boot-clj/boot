// vim: et:ts=4:sw=4

package boot;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.Pattern;
import java.lang.reflect.Method;

@SuppressWarnings("unchecked")
public class Loader {

    public static final String initialVersion = "2.3.0";
    public static final File   homedir        = new File(System.getProperty("user.home"));
    public static final File   workdir        = new File(System.getProperty("user.dir"));

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

    public static void
    tccl(ClassLoader cl) throws Exception {
        Thread.currentThread().setContextClassLoader(cl); }

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

    public static File
    bindir() throws Exception {
        return mkFile(bootdir(), "cache", "bin"); }

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
        String[] names  = new String[]{"old-boot", "boot", "project", "cwd"};
        File     olddir = new File(bootdir(), "cache");
        File[]   dirs   = new File[]{olddir, bootdir(), projectDir(), workdir};
        for (int i = 0; i < dirs.length; i++)
            ret.put(names[i], new File(dirs[i], "boot.properties"));
        return ret; }

    public static Properties
    mergeProperties() throws Exception {
        Properties p = new Properties();
        HashMap<String, File> fs = propertiesFiles();
        for (String k : new String[]{"old-boot", "boot", "project", "cwd"})
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

    public static Integer[]
    parseVersion(String v) throws Exception {
        String[]  seg = v.split("\\.", 3);
        Integer[] ret = new Integer[3];
        try {
            for (int i = 0; i < 3; i++)
                ret[i] = Integer.parseInt(seg[i]); }
        catch (Throwable e) { return null; }
        return ret; }

    public static int
    compareVersions(Integer[] a, Integer[] b) {
        if (b == null) { return 1; }
        for (int i = 0; i < 3; i++) {
            if (a[i] > b[i]) { return 1; }
            else if (a[i] < b[i]) { return -1; }}
        return 0; }

    public static String
    latestInstalledVersion(File[] fs) throws Exception {
        String    r = null;
        Integer[] b = null;
        if (fs != null)
            for (File f : fs) {
                if ((new File(f, "boot.jar")).exists()) {
                    Integer[] a = parseVersion(f.getName());
                    if (a != null && compareVersions(a, b) > 0) {
                        b = a;
                        r = f.getName(); }}}
        return r; }

    public static File
    download(String url, File f) throws Exception {
        if (f.exists()) return f;
        mkParents(f);
        int    n   = -1;
        byte[] buf = new byte[4096];
        System.err.print("Downloading " + url + "...");
        try (InputStream is = (new URL(url)).openStream();
                OutputStream os = new FileOutputStream(f)) {
            while (-1 != (n = is.read(buf)))
                os.write(buf, 0, n); }
        System.err.println("done.");
        return f; }

    public static File
    install(String version) throws Exception {
        Properties p  = propertiesResource("boot/tag-release.properties");
        String     v  = p.getProperty(version);
        String     vv = (v == null) ? version : v;
        String     nn = "boot." + ((v == null) ? "jar" : "sh");
        return download(downloadUrl(vv, nn), binaryFile(vv, false)); }

    public static String
    downloadUrl(String version, String name) throws Exception {
        return String.format("https://github.com/boot-clj/boot/releases/download/%s/%s", version, name); }

    public static File
    binaryFile(String version, boolean mustExist) throws Exception {
        if (version == null) return null;
        File f = mkFile(bindir(), version, "boot.jar");
        return (f.exists() || ! mustExist) ? f : null; }

    public static File
    binaryFile(String version) throws Exception {
        return binaryFile(version, true); }

    public static File
    latestBinaryFile() throws Exception {
        return binaryFile(latestInstalledVersion(bindir().listFiles())); }

    public static void
    main(String[] args) throws Exception {
        String[] a = args;
        File     f = null;
        String   v = config("BOOT_VERSION");

        if (v != null && (f = binaryFile(v)) == null)
            f = install(v);

        if (v == null)
            f = latestBinaryFile();

        if (f == null) {
            a = new String[]{"-u"};
            f = install(initialVersion);
            System.setProperty("BOOT_VERSION", initialVersion);
            System.err.println("Running for the first time: updating to latest version."); }

        URL         url = f.toURI().toURL();
        ClassLoader cl  = new URLClassLoader(new URL[]{url});
        Class       c   = Class.forName("boot.App", true, cl);
        Method      m   = c.getMethod("main", String[].class);

        tccl(cl);
        m.invoke(null, new Object[]{a}); }}
