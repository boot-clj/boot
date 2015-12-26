# boot.pod

Namespace containing functions and vars related to pods, dependencies,
jar files, and classloaders.

##### Settings (read-only)

 [`data`](#data) [`env`](#env) [`pod-id`](#pod-id) [`pods`](#pods) [`shutdown-hooks`](#shutdown-hooks) [`worker-pod`](#worker-pod)

##### Pods

 [`call-in*`](#call-in) [`destroy-pod`](#destroy-pod) [`eval-fn-call`](#eval-fn-call) [`eval-in*`](#eval-in) [`get-pods`](#get-pods) [`make-pod`](#make-pod) [`pod-pool`](#pod-pool) [`require-in`](#require-in) [`send!`](#send) [`with-call-in`](#with-call-in) [`with-call-worker`](#with-call-worker) [`with-eval-in`](#with-eval-in) [`with-eval-worker`](#with-eval-worker)

##### Classpath

 [`add-classpath`](#add-classpath) [`add-dependencies`](#add-dependencies) [`add-dependencies-in`](#add-dependencies-in) [`add-dependencies-worker`](#add-dependencies-worker) [`classloader-hierarchy`](#classloader-hierarchy) [`classloader-resources`](#classloader-resources) [`copy-resource`](#copy-resource) [`dependency-loaded?`](#dependency-loaded?) [`get-classpath`](#get-classpath) [`modifiable-classloader?`](#modifiable-classloader?) [`resources`](#resources) [`seal-app-classloader`](#seal-app-classloader)

##### Dependencies

 [`apply-exclusions`](#apply-exclusions) [`apply-global-exclusions`](#apply-global-exclusions) [`canonical-coord`](#canonical-coord) [`copy-dependency-jar-entries`](#copy-dependency-jar-entries) [`default-dependencies`](#default-dependencies) [`dependency-pom-properties`](#dependency-pom-properties) [`dependency-pom-properties-map`](#dependency-pom-properties-map) [`extract-ids`](#extract-ids) [`jars-dep-graph`](#jars-dep-graph) [`jars-in-dep-order`](#jars-in-dep-order) [`outdated`](#outdated) [`resolve-dependencies`](#resolve-dependencies) [`resolve-dependency-jar`](#resolve-dependency-jar) [`resolve-dependency-jars`](#resolve-dependency-jars) [`resolve-nontransitive-dependencies`](#resolve-nontransitive-dependencies)

##### Jars

 [`copy-url`](#copy-url) [`jar-entries`](#jar-entries) [`jar-entries*`](#jar-entries) [`jar-entries-memoized*`](#jar-entries-memoized) [`pom-properties`](#pom-properties) [`pom-properties-map`](#pom-properties-map) [`pom-xml`](#pom-xml) [`pom-xml-map`](#pom-xml-map) [`unpack-jar`](#unpack-jar)

##### Jar Exclusions & Mergers

 [`concat-merger`](#concat-merger) [`first-wins-merger`](#first-wins-merger) [`into-merger`](#into-merger) [`standard-jar-exclusions`](#standard-jar-exclusions) [`standard-jar-mergers`](#standard-jar-mergers)

##### Misc. Utility

 [`add-shutdown-hook!`](#add-shutdown-hook) [`caller-namespace`](#caller-namespace) [`lifecycle-pool`](#lifecycle-pool) [`non-caching-url-input-stream`](#non-caching-url-input-stream)

##### Deprecated / Internal

 [`set-data!`](#set-data) [`set-pod-id!`](#set-pod-id) [`set-pods!`](#set-pods) [`set-worker-pod!`](#set-worker-pod)

<hr>

### [`add-classpath`](../../2.5.3/boot/pod/src/boot/pod.clj#L64)

```clojure
(add-classpath jar-or-dir) (add-classpath jar-or-dir classloader)
```

```
A corollary to the (deprecated) `add-classpath` in clojure.core. This implementation
requires a java.io.File or String path to a jar file or directory, and will attempt
to add that path to the right classloader (with the search rooted at the current
thread's context classloader).
```

<hr>

### [`add-dependencies`](../../2.5.3/boot/pod/src/boot/pod.clj#L480)

```clojure
(add-dependencies env)
```

```
Resolve dependencies specified in the boot environment env and add their
jars to the classpath.
```

<hr>

### [`add-dependencies-in`](../../2.5.3/boot/pod/src/boot/pod.clj#L486)

```clojure
(add-dependencies-in pod env)
```

```
Resolve dependencies specified in the boot environment env and add their
jars to the classpath in the pod.
```

<hr>

### [`add-dependencies-worker`](../../2.5.3/boot/pod/src/boot/pod.clj#L493)

```clojure
(add-dependencies-worker env)
```

```
Resolve dependencies specified in the boot environment env and add their
jars to the classpath in the worker pod.
```

<hr>

### [`add-shutdown-hook!`](../../2.5.3/boot/pod/src/boot/pod.clj#L269)

```clojure
(add-shutdown-hook! f)
```

```
Adds f to the global queue of shutdown hooks for this instance of boot. Note
that boot may be running inside another instance of boot, so shutdown hooks
must be handled carefully as the JVM will not necessarily exit when this boot
instance is finished.

Functions added via add-shutdown-hook! will be processed at the correct time
(i.e. when boot is finished in the case of nested instances of boot, or when
the JVM exits otherwise).
```

<hr>

### [`apply-exclusions`](../../2.5.3/boot/pod/src/boot/pod.clj#L461)

```clojure
(apply-exclusions excl [p v & opts :as dep])
```

```
Merges the seq of dependency ids excl into the :exclusions of the dependency
vector dep, creating the :exclusions key and deduplicating as necessary.
```

<hr>

### [`apply-global-exclusions`](../../2.5.3/boot/pod/src/boot/pod.clj#L474)

```clojure
(apply-global-exclusions excl deps)
```

```
Merges the seq of dependency ids excl into all dependency vectors in deps.
See apply-exclusions.
```

<hr>

### [`call-in*`](../../2.5.3/boot/pod/src/boot/pod.clj#L297)

```clojure
(call-in* expr) (call-in* pod expr)
```

```
Low-level interface by which expressions are evaluated in other pods. The
two-arity version is invoked in the caller with a pod instance and an expr
form. The form is serialized and the one-arity version is invoked in the
pod with the serialized expr, which is deserialized and evaluated. The result
is then serialized and returned to the two-arity where it is deserialized
and returned to the caller. The *print-meta* binding determines whether
metadata is transmitted between pods.

The expr is expected to be of the form (f & args). It is evaluated in the
pod by resolving f and applying it to args.

Note: Since forms must be serialized to pass from one pod to another it is
not always appropriate to include metadata, as metadata may contain eg. File
objects which are not printable/readable by Clojure.
```

<hr>

### [`caller-namespace`](../../2.5.3/boot/pod/src/boot/pod.clj#L694)

```clojure
(caller-namespace)
```

```
When this macro is used in a function, it returns the namespace of the
caller of the function.
```

<hr>

### [`canonical-coord`](../../2.5.3/boot/pod/src/boot/pod.clj#L399)

```clojure
(canonical-coord [id & more :as coord])
```

```
Given a dependency coordinate of the form [id version ...], returns the
canonical form, i.e. the id symbol is always fully qualified.

For example: (canonical-coord '[foo "1.2.3"]) ;=> [foo/foo "1.2.3"]
```

<hr>

### [`classloader-hierarchy`](../../2.5.3/boot/pod/src/boot/pod.clj#L50)

```clojure
(classloader-hierarchy) (classloader-hierarchy tip)
```

```
Returns a seq of classloaders, with the tip of the hierarchy first.
Uses the current thread context ClassLoader as the tip ClassLoader
if one is not provided.
```

<hr>

### [`classloader-resources`](../../2.5.3/boot/pod/src/boot/pod.clj#L94)

```clojure
(classloader-resources resource-name) (classloader-resources classloaders resource-name)
```

```
Returns a sequence of [classloader url-seq] pairs representing all of the
resources of the specified name on the classpath of each classloader. If no
classloaders are given, uses the classloader-heirarchy, in which case the
order of pairs will be such that the first url mentioned will in most
circumstances match what clojure.java.io/resource returns.
```

<hr>

### [`concat-merger`](../../2.5.3/boot/pod/src/boot/pod.clj#L543)

```clojure
(concat-merger prev new out)
```

```
Reads the InputStreams prev and new as strings and appends new to prev,
separated by a single newline character. The result is written to the
OutputStream out.
```

<hr>

### [`copy-dependency-jar-entries`](../../2.5.3/boot/pod/src/boot/pod.clj#L600)

```clojure
(copy-dependency-jar-entries env outdir coord & regexes)
```

```
Resolve all dependencies and transitive dependencies of the dependency given
by the dep vector coord (given the boot environment configuration env), and
explode them into the outdir directory. The outdir directory will be created
if necessary and last modified times of jar entries will be preserved. If the
optional Patterns, regexes, are specified only entries whose paths match at
least one regex will be extracted to outdir.
```

<hr>

### [`copy-resource`](../../2.5.3/boot/pod/src/boot/pod.clj#L194)

```clojure
(copy-resource resource-path out-path)
```

```
Copies the contents of the jar resource at resource-path to the path or File
out-path on the filesystem. The copy operation is not atomic.
```

<hr>

### [`copy-url`](../../2.5.3/boot/pod/src/boot/pod.clj#L209)

```clojure
(copy-url url-str out-path & {:keys [cache], :or {cache true}})
```

```
Copies the URL constructed from url-str to the path or File out-path. When
the :cache option is false caching of URLs is disabled.
```

<hr>

### [`data`](../../2.5.3/boot/pod/src/boot/pod.clj#L221)

```
Set by boot.App/newCore, may be a ConcurrentHashMap for sharing data between
instances of Boot that are running inside of Boot.
```

<hr>

### [`default-dependencies`](../../2.5.3/boot/pod/src/boot/pod.clj#L685)

```clojure
(default-dependencies deps {:keys [dependencies], :as env})
```

```
Adds default dependencies given by deps to the :dependencies in env, but
favoring dependency versions in env over deps in case of conflict.
```

<hr>

### [`dependency-loaded?`](../../2.5.3/boot/pod/src/boot/pod.clj#L144)

```clojure
(dependency-loaded? [project & _])
```

```
Given a dependency coordinate of the form [id version ...], returns a URL
for the pom.properties file in the dependency jar, or nil if the dependency
isn't on the classpath.
```

<hr>

### [`dependency-pom-properties`](../../2.5.3/boot/pod/src/boot/pod.clj#L152)

```clojure
(dependency-pom-properties coord)
```

```
Given a dependency coordinate of the form [id version ...], returns a
Properties object corresponding to the dependency jar's pom.properties file.
```

<hr>

### [`dependency-pom-properties-map`](../../2.5.3/boot/pod/src/boot/pod.clj#L159)

```clojure
(dependency-pom-properties-map coord)
```

```
Given a dependency coordinate of the form [id version ...], returns a map
of the contents of the jar's pom.properties file.
```

<hr>

### [`destroy-pod`](../../2.5.3/boot/pod/src/boot/pod.clj#L722)

```clojure
(destroy-pod pod)
```

```
Closes open resources held by the pod, making the pod eligible for GC.
```

<hr>

### [`env`](../../2.5.3/boot/pod/src/boot/pod.clj#L217)

```
This pod's boot environment.
```

<hr>

### [`eval-fn-call`](../../2.5.3/boot/pod/src/boot/pod.clj#L289)

```clojure
(eval-fn-call [f & args])
```

```
Given an expression of the form (f & args), resolves f and applies f to args.
```

<hr>

### [`eval-in*`](../../2.5.3/boot/pod/src/boot/pod.clj#L351)

```clojure
(eval-in* expr) (eval-in* pod expr)
```

```
Low-level interface by which expressions are evaluated in other pods. The
two-arity version is invoked in the caller with a pod instance and an expr
form. The form is serialized and the one-arity version is invoked in the
pod with the serialized expr, which is deserialized and evaluated. The result
is then serialized and returned to the two-arity where it is deserialized
and returned to the caller. The *print-meta* binding determines whether
metadata is transmitted between pods.

Unlike call-in*, expr can be any expression, without the restriction that it
be of the form (f & args).

Note: Since forms must be serialized to pass from one pod to another it is
not always appropriate to include metadata, as metadata may contain eg. File
objects which are not printable/readable by Clojure.
```

<hr>

### [`extract-ids`](../../2.5.3/boot/pod/src/boot/pod.clj#L22)

```clojure
(extract-ids sym)
```

```
Given a dependency symbol sym, returns a vector of [group-id artifact-id].
```

<hr>

### [`first-wins-merger`](../../2.5.3/boot/pod/src/boot/pod.clj#L553)

```clojure
(first-wins-merger prev _ out)
```

```
Writes the InputStream prev to the OutputStream out.
```

<hr>

### [`get-classpath`](../../2.5.3/boot/pod/src/boot/pod.clj#L79)

```clojure
(get-classpath) (get-classpath classloaders)
```

```
Returns the effective classpath (i.e. _not_ the value of
(System/getProperty "java.class.path") as a seq of URL strings.

Produces the classpath from all classloaders by default, or from a
collection of classloaders if provided.  This allows you to easily look
at subsets of the current classloader hierarchy, e.g.:

(get-classpath (drop 2 (classloader-hierarchy)))
```

<hr>

### [`get-pods`](../../2.5.3/boot/pod/src/boot/pod.clj#L251)

```clojure
(get-pods name-or-pattern) (get-pods name-or-pattern unique?)
```

```
Returns a seq of pod references whose names match name-or-pattern, which
can be a string or a Pattern. Strings are matched by equality, and Patterns
by re-find. The unique? option, if given, will cause an exception to be
thrown unless exactly one pod matches.
```

<hr>

### [`into-merger`](../../2.5.3/boot/pod/src/boot/pod.clj#L533)

```clojure
(into-merger prev new out)
```

```
Reads the InputStreams prev and new as EDN, uses clojure.core/into to merge
the data from new into prev, and writes the result to the OutputStream out.
```

<hr>

### [`jar-entries`](../../2.5.3/boot/pod/src/boot/pod.clj#L521)

```clojure
(jar-entries path-or-jarfile & {:keys [cache], :or {cache true}})
```

```
Given a path to a jar file, returns a list of [resource-path, resource-url]
string pairs corresponding to all entries contained the jar contains.
```

<hr>

### [`jar-entries*`](../../2.5.3/boot/pod/src/boot/pod.clj#L499)

```clojure
(jar-entries* path-or-jarfile)
```

```
Returns a vector containing vectors of the form [name url-string] for each
entry in the jar path-or-jarfile, which can be a string or File.
```

<hr>

### [`jar-entries-memoized*`](../../2.5.3/boot/pod/src/boot/pod.clj#L517)

```
Memoized version of jar-entries*.
```

<hr>

### [`jars-dep-graph`](../../2.5.3/boot/pod/src/boot/pod.clj#L585)

```clojure
(jars-dep-graph env)
```

```
Returns a dependency graph for all jar file dependencies specified in the
boot environment map env, including transitive dependencies.
```

<hr>

### [`jars-in-dep-order`](../../2.5.3/boot/pod/src/boot/pod.clj#L591)

```clojure
(jars-in-dep-order env)
```

```
Returns a seq of all jar file dependencies specified in the boot environment
map env, including transitive dependencies, and in dependency order.

Dependency order means, eg. if jar B depends on jar A then jar A will appear
before jar B in the returned list.
```

<hr>

### [`lifecycle-pool`](../../2.5.3/boot/pod/src/boot/pod.clj#L621)

```clojure
(lifecycle-pool size create destroy & {:keys [priority]})
```

```
Creates a function implementing a lifecycle protocol on a pool of stateful
objects. The pool will attempt to maintain at least size objects, creating
new objects via the create function as needed. The objects are retired when
no longer needed, using the given destroy function. The :priority option can
be given to specify the priority of the worker thread (default NORM_PRIORITY).

The pool maintains a queue of objects with the head of the queue being the
"current" object. The pool may be "refreshed": the current object is
destroyed and the next object in the queue is promoted to current object.

The returned function accepts one argument, which can be :shutdown, :take,
or :refresh, or no arguments.

  none          Returns the current object.

  :shutdown     Stop worker thread and destroy all objects in the pool.

  :take         Remove the current object from the pool and return it to
                the caller without destroying it. The next object in the
                pool will be promoted. Note that it is the responsibility
                of the caller to properly dispose of the returned object.

  :refresh      Destroy the current object and promote the next object.
```

<hr>

### [`make-pod`](../../2.5.3/boot/pod/src/boot/pod.clj#L705)

```clojure
(make-pod) (make-pod {:keys [directories dependencies], :as env})
```

```
Returns a newly constructed pod. A boot environment configuration map, env,
may be given to initialize the pod with dependencies, directories, etc.
```

<hr>

### [`modifiable-classloader?`](../../2.5.3/boot/pod/src/boot/pod.clj#L57)

```clojure
(modifiable-classloader? cl)
```

```
Returns true iff the given ClassLoader is of a type that satisfies
the dynapath.dynamic-classpath/DynamicClasspath protocol, and it can
be modified.
```

<hr>

### [`non-caching-url-input-stream`](../../2.5.3/boot/pod/src/boot/pod.clj#L202)

```clojure
(non-caching-url-input-stream url-str)
```

```
Returns an InputStream from the URL constructed from url-str, with caching
disabled. This is useful for example when accessing jar entries in jars that
may change.
```

<hr>

### [`outdated`](../../2.5.3/boot/pod/src/boot/pod.clj#L444)

```clojure
(outdated env & {:keys [snapshots]})
```

```
Returns a seq of dependency vectors corresponding to outdated dependencies
in the dependency graph associated with the boot environment env. If the
:snapshots option is given SNAPSHOT versions will be considered, otherwise
only release versions will be considered.
```

<hr>

### [`pod-id`](../../2.5.3/boot/pod/src/boot/pod.clj#L230)

```
Each pod is numbered in the order in which it was created.
```

<hr>

### [`pod-pool`](../../2.5.3/boot/pod/src/boot/pod.clj#L729)

```clojure
(pod-pool env & {:keys [size init destroy]})
```

```
Creates a pod pool service. The service maintains a pool of prebuilt pods
with a current active pod and a number of pods in reserve, warmed and ready
to go (it takes ~2s to load clojure.core into a pod).

Pool Service API:
-----------------

The pod-pool function returns a pod service instance, which is itself a
Clojure function. The pod service function can be called with no arguments
or with :refresh or :shutdown.

Calling the function with no arguments produces a reference to the current
pod. Expressions can be evaluated in this pod via with-eval-in, etc.

Calling the function with the :refresh argument swaps out the current pod,
destroys it, and promotes a pod from the reserve pool. A new pod is created
asynchronously to replenish the reserve pool.

Calling the function with the :shutdown argument destroys all pods in the
pool and shuts down the service.

Options:
--------

:size       The total number of pods to be maintained in the pool. Default
            size is 2.
:init       A function that is called when a new pod is created. Takes the
            new pod as an argument, is evaluated for side effects only.
:destroy    A function that is called when a pod is destroyed. Takes the pod
            to be destroyed as an argument, is evaluated for side effects
            before pod is destroyed.
```

<hr>

### [`pods`](../../2.5.3/boot/pod/src/boot/pod.clj#L226)

```
A WeakHashMap whose keys are all of the currently running pods.
```

<hr>

### [`pom-properties`](../../2.5.3/boot/pod/src/boot/pod.clj#L123)

```clojure
(pom-properties jarpath)
```

```
Given a path or File jarpath, finds the jar's pom.properties file, reads
it, and returns the loaded Properties object. An exception is thrown if
multiple pom.properties files are present in the jar.
```

<hr>

### [`pom-properties-map`](../../2.5.3/boot/pod/src/boot/pod.clj#L165)

```clojure
(pom-properties-map prop-or-jarpath)
```

```
Returns a map of the contents of the pom.properties pom-or-jarpath, where
pom-or-jarpath is either a slurpable thing or a Properties object.
```

<hr>

### [`pom-xml`](../../2.5.3/boot/pod/src/boot/pod.clj#L175)

```clojure
(pom-xml jarpath) (pom-xml jarpath pompath)
```

```
Returns a the pom.xml contents as a string. If only jarpath is given the
jar must contain exactly one pom.xml file. If pompath is a file that exists
the contents of that file will be returned. Otherwise, pompath must be the
resource path of the pom.xml file in the jar.
```

<hr>

### [`pom-xml-map`](../../2.5.3/boot/pod/src/boot/pod.clj#L339)

```clojure
(pom-xml-map jarpath) (pom-xml-map jarpath pompath)
```

```
Returns a map of pom data from the pom.xml in the jar specified jarpath,
which can be a string or File. If pompath is not specified there must be
exactly one pom.xml in the jar. Otherwise, the pom.xml will be extracted
from the jar by the resource path specified by pompath.
```

<hr>

### [`require-in`](../../2.5.3/boot/pod/src/boot/pod.clj#L392)

```clojure
(require-in pod ns)
```

```
Evaluates (require 'ns) in the pod. Avoid this function.
```

<hr>

### [`resolve-dependencies`](../../2.5.3/boot/pod/src/boot/pod.clj#L408)

```clojure
(resolve-dependencies env)
```

```
Returns a seq of maps of the form {:jar <path> :dep <dependency vector>}
corresponding to the fully resolved dependency graph as specified in the
env, where env is the boot environment (see boot.core/get-env). The seq of
dependencies includes all transitive dependencies.
```

<hr>

### [`resolve-dependency-jar`](../../2.5.3/boot/pod/src/boot/pod.clj#L436)

```clojure
(resolve-dependency-jar env coord)
```

```
Returns the path to the jar file associated with the dependency specified
by coord, given the boot environment configuration env.
```

<hr>

### [`resolve-dependency-jars`](../../2.5.3/boot/pod/src/boot/pod.clj#L416)

```clojure
(resolve-dependency-jars env & [ignore-clj?])
```

```
Returns a seq of File objects corresponding to the jar files associated with
the fully resolved dependency graph as specified in the env, where env is the
boot environment (see boot.core/get-env). If ignore-clj? is specified Clojure
will be excluded from the result (the clojure dependency is identified by the
BOOT_CLOJURE_NAME environment setting, which defaults to org.clojure.clojure).
```

<hr>

### [`resolve-nontransitive-dependencies`](../../2.5.3/boot/pod/src/boot/pod.clj#L429)

```clojure
(resolve-nontransitive-dependencies env dep)
```

```
Returns a seq of maps of the form {:jar <path> :dep <dependency vector>}
for the dependencies of dep, excluding transitive dependencies (i.e. the
dependencies of dep's dependencies).
```

<hr>

### [`resources`](../../2.5.3/boot/pod/src/boot/pod.clj#L106)

```clojure
(resources resource-name) (resources classloaders resource-name)
```

```
Returns a sequence of URLs representing all of the resources of the
specified name on the effective classpath. This can be useful for finding
ame collisions among items on the classpath. In most circumstances, the
irst of the returned sequence will be the same as what clojure.java.io/resource
eturns.
```

<hr>

### [`seal-app-classloader`](../../2.5.3/boot/pod/src/boot/pod.clj#L28)

```clojure
(seal-app-classloader)
```

```
Implements the DynamicClasspath protocol to the AppClassLoader class such
that instances of this class will refuse attempts at runtime modification
by libraries that do so via dynapath[1]. The system class loader is of the
type AppClassLoader.

The purpose of this is to ensure that Clojure libraries do not pollute the
higher-level class loaders with classes and interfaces created dynamically
in their Clojure runtime. This is essential for pods to work properly[2].

This function is called during Boot's bootstrapping phase, and shouldn't
be needed in client code under normal circumstances.

[1]: https://github.com/tobias/dynapath
[2]: https://github.com/clojure-emacs/cider-nrepl/blob/36333cae25fd510747321f86e2f0369fcb7b4afd/README.md#with-jboss-asjboss-eapwildfly
```

<hr>

### [`send!`](../../2.5.3/boot/pod/src/boot/pod.clj#L283)

```clojure
(send! form)
```

```
This is ALPHA status, it may change, be renamed, or removed.
```

<hr>

### [`set-data!`](../../2.5.3/boot/pod/src/boot/pod.clj#L247)

```clojure
(set-data! x)
```

```
FIXME: document this
```

<hr>

### [`set-pod-id!`](../../2.5.3/boot/pod/src/boot/pod.clj#L248)

```clojure
(set-pod-id! x)
```

```
FIXME: document this
```

<hr>

### [`set-pods!`](../../2.5.3/boot/pod/src/boot/pod.clj#L246)

```clojure
(set-pods! x)
```

```
FIXME: document this
```

<hr>

### [`set-worker-pod!`](../../2.5.3/boot/pod/src/boot/pod.clj#L249)

```clojure
(set-worker-pod! x)
```

```
FIXME: document this
```

<hr>

### [`shutdown-hooks`](../../2.5.3/boot/pod/src/boot/pod.clj#L239)

```
Atom containing shutdown hooks to be performed at exit. This is used instead
of Runtime.getRuntime().addShutdownHook() by boot so that these hooks can be
called without exiting the JVM process, for example when boot is running in
boot. See #'boot.pod/add-shutdown-hook! for more info.
```

<hr>

### [`standard-jar-exclusions`](../../2.5.3/boot/pod/src/boot/pod.clj#L527)

```
Entries matching these Patterns will not be extracted from jars when they
are exploded during uberjar construction.
```

<hr>

### [`standard-jar-mergers`](../../2.5.3/boot/pod/src/boot/pod.clj#L558)

```
A vector containing vectors of the form [<path-matcher> <merger-fn>]. These
pairs will be used to decide how to merge conflicting entries when exploding
or creating jar files. See boot.task.built-in/uber for more info.
```

<hr>

### [`unpack-jar`](../../2.5.3/boot/pod/src/boot/pod.clj#L566)

```clojure
(unpack-jar jar-path dest-dir & opts)
```

```
Explodes the jar identified by the string or File jar-path, copying all jar
entries to the directory given by the string or File dest-dir. The directory
will be created if it does not exist and jar entry modification times will
be preserved. Files will not be written atomically.
```

<hr>

### [`with-call-in`](../../2.5.3/boot/pod/src/boot/pod.clj#L321)

```clojure
(with-call-in pod expr)
```

```
Given a pod and an expr of the form (f & args), resolves f in the pod,
applies it to args, and returns the result to the caller. The expr may be a
template containing the ~ (unqupte) and ~@ (unquote-splicing) reader macros.
These will be evaluated in the calling scope and substituted in the template
like the ` (syntax-quote) reader macro.

Note: Unlike syntax-quote, no name resolution is done on the template forms.
```

<hr>

### [`with-call-worker`](../../2.5.3/boot/pod/src/boot/pod.clj#L334)

```clojure
(with-call-worker expr)
```

```
Like with-call-in, evaluating expr in the worker pod.
```

<hr>

### [`with-eval-in`](../../2.5.3/boot/pod/src/boot/pod.clj#L375)

```clojure
(with-eval-in pod & body)
```

```
Given a pod and an expr, evaluates expr in the pod and returns the result
to the caller. The expr may be a template containing the ~ (unqupte) and
~@ (unquote-splicing) reader macros. These will be evaluated in the calling
scope and substituted in the template like the ` (syntax-quote) reader macro.

Note: Unlike syntax-quote, no name resolution is done on the template forms.
```

<hr>

### [`with-eval-worker`](../../2.5.3/boot/pod/src/boot/pod.clj#L387)

```clojure
(with-eval-worker & body)
```

```
Like with-eval-in, evaluating expr in the worker pod.
```

<hr>

### [`worker-pod`](../../2.5.3/boot/pod/src/boot/pod.clj#L234)

```
A reference to the boot worker pod. All pods share access to the worker
pod singleton instance.
```

<hr>

