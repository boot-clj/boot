# boot.core

The Boot core namespace, containing most of Boot's public API.

##### Settings (read-only)

 [`*app-version*`](#app-version) [`*boot-opts*`](#boot-opts) [`*boot-script*`](#boot-script) [`*boot-version*`](#boot-version) [`*warnings*`](#warnings) [`bootignore`](#bootignore) [`last-file-change`](#last-file-change) [`new-build-at`](#new-build-at)

##### Configuration Helpers

 [`configure-repositories!`](#configure-repositories) [`load-data-readers!`](#load-data-readers)

##### Boot Environment

 [`get-checkouts`](#get-checkouts) [`get-env`](#get-env) [`get-sys-env`](#get-sys-env) [`merge-env!`](#merge-env) [`post-env!`](#post-env) [`pre-env!`](#pre-env) [`set-env!`](#set-env) [`set-sys-env!`](#set-sys-env)

##### Define Tasks

 [`cleanup`](#cleanup) [`deftask`](#deftask) [`reset-build!`](#reset-build) [`reset-fileset`](#reset-fileset) [`with-pass-thru`](#with-pass-thru) [`with-post-wrap`](#with-post-wrap) [`with-pre-wrap`](#with-pre-wrap)

##### Manipulate Task Options

 [`disable-task!`](#disable-task) [`replace-task!`](#replace-task) [`task-options!`](#task-options)

##### REPL Integration

 [`boot`](#boot) [`launch-nrepl`](#launch-nrepl) [`rebuild!`](#rebuild)

##### Create Temp Directories

 [`cache-dir!`](#cache-dir) [`tmp-dir!`](#tmp-dir)

##### TmpFile API

 [`tmp-dir`](#tmp-dir) [`tmp-file`](#tmp-file) [`tmp-path`](#tmp-path) [`tmp-time`](#tmp-time)

##### Query Fileset For TmpFiles

 [`input-files`](#input-files) [`output-files`](#output-files) [`tmp-get`](#tmp-get) [`user-files`](#user-files)

##### Filter Sequences Of TmpFiles

 [`by-ext`](#by-ext) [`by-meta`](#by-meta) [`by-name`](#by-name) [`by-path`](#by-path) [`by-re`](#by-re) [`file-filter`](#file-filter) [`not-by-ext`](#not-by-ext) [`not-by-meta`](#not-by-meta) [`not-by-name`](#not-by-name) [`not-by-path`](#not-by-path) [`not-by-re`](#not-by-re)

##### Other Fileset Queries

 [`fileset-namespaces`](#fileset-namespaces) [`input-dirs`](#input-dirs) [`input-fileset`](#input-fileset) [`ls`](#ls) [`output-dirs`](#output-dirs) [`output-fileset`](#output-fileset) [`user-dirs`](#user-dirs)

##### Manipulate Fileset

 [`add-asset`](#add-asset) [`add-cached-asset`](#add-cached-asset) [`add-cached-resource`](#add-cached-resource) [`add-cached-source`](#add-cached-source) [`add-meta`](#add-meta) [`add-resource`](#add-resource) [`add-source`](#add-source) [`commit!`](#commit) [`cp`](#cp) [`mv`](#mv) [`mv-asset`](#mv-asset) [`mv-resource`](#mv-resource) [`mv-source`](#mv-source) [`new-fileset`](#new-fileset) [`rm`](#rm)

##### Fileset Diffs

 [`fileset-added`](#fileset-added) [`fileset-changed`](#fileset-changed) [`fileset-diff`](#fileset-diff) [`fileset-removed`](#fileset-removed)

##### Misc. Helpers

 [`empty-dir!`](#empty-dir) [`git-files`](#git-files) [`gpg-decrypt`](#gpg-decrypt) [`json-generate`](#json-generate) [`json-parse`](#json-parse) [`patch!`](#patch) [`sync!`](#sync) [`template`](#template) [`touch`](#touch) [`watch-dirs`](#watch-dirs) [`yaml-generate`](#yaml-generate) [`yaml-parse`](#yaml-parse)

##### Deprecated / Internal

 [`fileset-reduce`](#fileset-reduce) [`init!`](#init) [`temp-dir!`](#temp-dir) [`tmpdir`](#tmpdir) [`tmpfile`](#tmpfile) [`tmpget`](#tmpget) [`tmppath`](#tmppath) [`tmptime`](#tmptime)

<hr>

### [`*app-version*`](../../2.6.0/boot/core/src/boot/core.clj#L31)

```
The running version of boot app.
```

<hr>

### [`*boot-opts*`](../../2.6.0/boot/core/src/boot/core.clj#L34)

```
Command line options for boot itself.
```

<hr>

### [`*boot-script*`](../../2.6.0/boot/core/src/boot/core.clj#L32)

```
The script's name (when run as script).
```

<hr>

### [`*boot-version*`](../../2.6.0/boot/core/src/boot/core.clj#L33)

```
The running version of boot core.
```

<hr>

### [`*warnings*`](../../2.6.0/boot/core/src/boot/core.clj#L35)

```
Count of warnings during build.
```

<hr>

### [`add-asset`](../../2.6.0/boot/core/src/boot/core.clj#L487)

```clojure
(add-asset fileset dir & {:keys [mergers include exclude], :as opts})
```

```
Add the contents of the java.io.File dir to the fileset's assets.

  Option :include and :exclude, a #{} of regex expressions, control
  which paths are added; a path is only added if it matches an :include
  regex and does not match any :exclude regexes.

  If the operation produces duplicate entries, they will be merged using
  the rules specified by the :mergers option. A merge rule is a
  [regex fn] pair, where fn takes three parameters:

  - an InputStream for the previous entry,
  - an InputStream of the new entry,
  - and an OutputStream that will replace the entry.

  You will typically use default mergers as in:

    [[ #"data_readers.clj$"    into-merger       ]
     [ #"META-INF/services/.*" concat-merger     ]
     [ #".*"                   first-wins-merger ]]

  The merge rule regular expressions are tested in order, and the fn
  from the first match is applied.
```

<hr>

### [`add-cached-asset`](../../2.6.0/boot/core/src/boot/core.clj#L513)

```clojure
(add-cached-asset fileset cache-key cache-fn & {:keys [mergers include exclude], :as opts})
```

```
FIXME: document
```

<hr>

### [`add-cached-resource`](../../2.6.0/boot/core/src/boot/core.clj#L585)

```clojure
(add-cached-resource fileset cache-key cache-fn & {:keys [mergers include exclude], :as opts})
```

```
FIXME: document
```

<hr>

### [`add-cached-source`](../../2.6.0/boot/core/src/boot/core.clj#L549)

```clojure
(add-cached-source fileset cache-key cache-fn & {:keys [mergers include exclude], :as opts})
```

```
FIXME: document
```

<hr>

### [`add-meta`](../../2.6.0/boot/core/src/boot/core.clj#L595)

```clojure
(add-meta fileset meta-map)
```

```
Adds metadata about the files in the filesystem to their corresponding
TmpFile objects in the fileset. The meta-map is expected to be a map with
string paths as keys and maps of metadata as values. These metadata maps
will be merged into the TmpFile objects associated with the paths.
```

<hr>

### [`add-resource`](../../2.6.0/boot/core/src/boot/core.clj#L559)

```clojure
(add-resource fileset dir & {:keys [mergers include exclude], :as opts})
```

```
Add the contents of the java.io.File dir to the fileset's resources.

  Option :include and :exclude, a #{} of regex expressions, control
  which paths are added; a path is only added if it matches an :include
  regex and does not match any :exclude regexes.

  If the operation produces duplicate entries, they will be merged using
  the rules specified by the :mergers option. A merge rule is a
  [regex fn] pair, where fn takes three parameters:

  - an InputStream for the previous entry,
  - an InputStream of the new entry,
  - and an OutputStream that will replace the entry.

  You will typically use default mergers as in:

    [[ #"data_readers.clj$"    into-merger       ]
     [ #"META-INF/services/.*" concat-merger     ]
     [ #".*"                   first-wins-merger ]]

  The merge rule regular expressions are tested in order, and the fn
  from the first match is applied.
```

<hr>

### [`add-source`](../../2.6.0/boot/core/src/boot/core.clj#L523)

```clojure
(add-source fileset dir & {:keys [mergers include exclude], :as opts})
```

```
Add the contents of the java.io.File dir to the fileset's sources.

  Option :include and :exclude, a #{} of regex expressions, control
  which paths are added; a path is only added if it matches an :include
  regex and does not match any :exclude regexes.

  If the operation produces duplicate entries, they will be merged using
  the rules specified by the :mergers option. A merge rule is a
  [regex fn] pair, where fn takes three parameters:

  - an InputStream for the previous entry,
  - an InputStream of the new entry,
  - and an OutputStream that will replace the entry.

  You will typically use default mergers as in:

    [[ #"data_readers.clj$"    into-merger       ]
     [ #"META-INF/services/.*" concat-merger     ]
     [ #".*"                   first-wins-merger ]]

  The merge rule regular expressions are tested in order, and the fn
  from the first match is applied.
```

<hr>

### [`boot`](../../2.6.0/boot/core/src/boot/core.clj#L940)

```clojure
(boot & argv)
```

```
The REPL equivalent to the command line 'boot'. If all arguments are
strings then they are treated as if they were given on the command line.
Otherwise they are assumed to evaluate to task middleware.
```

<hr>

### [`bootignore`](../../2.6.0/boot/core/src/boot/core.clj#L39)

```
Set of regexes source file paths must not match.
```

<hr>

### [`by-ext`](../../2.6.0/boot/core/src/boot/core.clj#L1187)

```clojure
(by-ext exts files & [negate?])
```

```
This function takes two arguments: `exts` and `files`, where `exts` is a seq
of file extension strings like `[".clj" ".cljs"]` and `files` is a seq of
file objects. Returns a seq of the files in `files` which have file extensions
listed in `exts`.
```

<hr>

### [`by-meta`](../../2.6.0/boot/core/src/boot/core.clj#L1143)

```clojure
(by-meta preds files & [negate?])
```

```
This function takes two arguments: `preds` and `files`, where `preds` is a
seq of predicates to be applied to the file metadata and `files` is a seq of
file objects obtained from the fileset with the help of `boot.core/ls` or any
other way. Returns a seq of files in `files` which match all of the
predicates in `preds`. `negate?` inverts the result.

This function will not unwrap the `File` objects from `TmpFiles`.
```

<hr>

### [`by-name`](../../2.6.0/boot/core/src/boot/core.clj#L1161)

```clojure
(by-name names files & [negate?])
```

```
This function takes two arguments: `names` and `files`, where `names` is
a seq of file name strings like `["foo.clj" "bar.xml"]` and `files` is
a seq of file objects. Returns a seq of the files in `files` which have file
names listed in `names`.
```

<hr>

### [`by-path`](../../2.6.0/boot/core/src/boot/core.clj#L1174)

```clojure
(by-path paths files & [negate?])
```

```
This function takes two arguments: `paths` and `files`, where `path` is
a seq of path strings like `["a/b/c/foo.clj" "bar.xml"]` and `files` is
a seq of file objects. Returns a seq of the files in `files` which have file
paths listed in `paths`.
```

<hr>

### [`by-re`](../../2.6.0/boot/core/src/boot/core.clj#L1200)

```clojure
(by-re res files & [negate?])
```

```
This function takes two arguments: `res` and `files`, where `res` is a seq
of regex patterns like `[#"clj$" #"cljs$"]` and `files` is a seq of
file objects. Returns a seq of the files in `files` whose paths match one of
the regex patterns in `res`.
```

<hr>

### [`cache-dir!`](../../2.6.0/boot/core/src/boot/core.clj#L347)

```clojure
(cache-dir! key & {:keys [global]})
```

```
Returns a directory which is managed by boot but whose contents will not be
deleted after the build is complete. The :global option specifies that the
directory is shared by all projects. The default behavior returns different
directories for the same key when run in different projects.
```

<hr>

### [`cleanup`](../../2.6.0/boot/core/src/boot/core.clj#L868)

```clojure
(cleanup & body)
```

```
Evaluate body after tasks have been run. This macro is meant to be called
from inside a task definition, and is provided as a means to shutdown or
clean up persistent resources created by the task (eg. threads, files, etc.)
```

<hr>

### [`commit!`](../../2.6.0/boot/core/src/boot/core.clj#L446)

```clojure
(commit! fileset)
```

```
Make the underlying temp directories correspond to the immutable fileset
tree structure.
```

<hr>

### [`configure-repositories!`](../../2.6.0/boot/core/src/boot/core.clj#L775)

```clojure
(configure-repositories!) (configure-repositories! f)
```

```
Get or set the repository configuration callback function. The function
will be applied to all repositories added to the boot env, it should return
the repository map with any additional configuration (like credentials, for
example).
```

<hr>

### [`cp`](../../2.6.0/boot/core/src/boot/core.clj#L478)

```clojure
(cp fileset src-file dest-tmpfile)
```

```
Given a fileset and a dest-tmpfile from that fileset, overwrites the dest
tmpfile with the contents of the java.io.File src-file.
```

<hr>

### [`deftask`](../../2.6.0/boot/core/src/boot/core.clj#L844)

```clojure
(deftask sym & forms)
```

```
Define a boot task.
```

<hr>

### [`disable-task!`](../../2.6.0/boot/core/src/boot/core.clj#L1044)

```clojure
(disable-task! & tasks)
```

```
Disables the given tasks by replacing them with the identity task.

  Example:

      (disable-task! repl jar)
```

<hr>

### [`empty-dir!`](../../2.6.0/boot/core/src/boot/core.clj#L639)

```clojure
(empty-dir! & dirs)
```

```
For each directory in dirs, recursively deletes all files and subdirectories.
The directories in dirs themselves are not deleted.
```

<hr>

### [`file-filter`](../../2.6.0/boot/core/src/boot/core.clj#L1134)

```clojure
(file-filter mkpred)
```

```
A file filtering function factory. FIXME: more documenting here.
```

<hr>

### [`fileset-added`](../../2.6.0/boot/core/src/boot/core.clj#L616)

```clojure
(fileset-added before after & props)
```

```
Returns a new fileset containing only files that were added.
```

<hr>

### [`fileset-changed`](../../2.6.0/boot/core/src/boot/core.clj#L626)

```clojure
(fileset-changed before after & props)
```

```
Returns a new fileset containing only files that were changed.
```

<hr>

### [`fileset-diff`](../../2.6.0/boot/core/src/boot/core.clj#L608)

```clojure
(fileset-diff before after & props)
```

```
Returns a new fileset containing files that were added or modified. Removed
files are not considered. The optional props arguments can be any of :time,
:hash, or both, specifying whether to consider changes to last modified time
or content md5 hash of the files (the default is both).
```

<hr>

### [`fileset-namespaces`](../../2.6.0/boot/core/src/boot/core.clj#L631)

```clojure
(fileset-namespaces fileset)
```

```
Returns a set of symbols: the namespaces defined in this fileset.
```

<hr>

### [`fileset-reduce`](../../2.6.0/boot/core/src/boot/core.clj#L1018)

```clojure
(fileset-reduce fileset get-files & reducers)
```

```
Given a fileset, a function get-files that selects files from the fileset,
and a number of reducing functions, composes the reductions. The result of
the previous reduction and the result of get-files applied to it are passed
through to the next reducing function.
```

<hr>

### [`fileset-removed`](../../2.6.0/boot/core/src/boot/core.clj#L621)

```clojure
(fileset-removed before after & props)
```

```
Returns a new fileset containing only files that were removed.
```

<hr>

### [`get-checkouts`](../../2.6.0/boot/core/src/boot/core.clj#L783)

```clojure
(get-checkouts)
```

```
FIXME
```

<hr>

### [`get-env`](../../2.6.0/boot/core/src/boot/core.clj#L790)

```clojure
(get-env & [k not-found])
```

```
Returns the value associated with the key `k` in the boot environment, or
`not-found` if the environment doesn't contain key `k` and `not-found` was
given. Calling this function with no arguments returns the environment map.
```

<hr>

### [`get-sys-env`](../../2.6.0/boot/core/src/boot/core.clj#L822)

```clojure
(get-sys-env) (get-sys-env k) (get-sys-env k not-found)
```

```
Returns the value associated with the system property k, the environment
variable k, or not-found if neither of those are set. If not-found is the
keyword :required, an exception will be thrown when there is no value for
either the system property or environment variable k.
```

<hr>

### [`git-files`](../../2.6.0/boot/core/src/boot/core.clj#L1124)

```clojure
(git-files & {:keys [untracked]})
```

```
Returns a list of files roughly equivalent to what you'd get with the git
command line `git ls-files`. The :untracked option includes untracked files.
```

<hr>

### [`gpg-decrypt`](../../2.6.0/boot/core/src/boot/core.clj#L1089)

```clojure
(gpg-decrypt path-or-file & {:keys [as]})
```

```
Uses gpg(1) to decrypt a file and returns its contents as a string. The
:as :edn option can be passed to read the contents as an EDN form.
```

<hr>

### [`init!`](../../2.6.0/boot/core/src/boot/core.clj#L696)

```clojure
(init!)
```

```
Initialize the boot environment. This is normally run once by boot at
startup. There should be no need to call this function directly.
```

<hr>

### [`input-dirs`](../../2.6.0/boot/core/src/boot/core.clj#L406)

```clojure
(input-dirs fileset)
```

```
Get a list of directories containing files with input roles.
```

<hr>

### [`input-files`](../../2.6.0/boot/core/src/boot/core.clj#L421)

```clojure
(input-files fileset)
```

```
Get a set of TmpFile objects corresponding to files with input role.
```

<hr>

### [`input-fileset`](../../2.6.0/boot/core/src/boot/core.clj#L426)

```clojure
(input-fileset fileset)
```

```
FIXME: document
```

<hr>

### [`json-generate`](../../2.6.0/boot/core/src/boot/core.clj#L1095)

```clojure
(json-generate x & [opt-map])
```

```
Same as cheshire.core/generate-string.
```

<hr>

### [`json-parse`](../../2.6.0/boot/core/src/boot/core.clj#L1101)

```clojure
(json-parse x & [key-fn])
```

```
Same as cheshire.core/parse-string.
```

<hr>

### [`last-file-change`](../../2.6.0/boot/core/src/boot/core.clj#L38)

```
Last source file watcher update time.
```

<hr>

### [`launch-nrepl`](../../2.6.0/boot/core/src/boot/core.clj#L1215)

```clojure
(launch-nrepl & {:keys [pod], :as opts})
```

```
Launches an nREPL server in a pod. See the repl task for options.
```

<hr>

### [`load-data-readers!`](../../2.6.0/boot/core/src/boot/core.clj#L211)

```clojure
(load-data-readers!)
```

```
Refresh *data-readers* with readers from newly acquired dependencies.
```

<hr>

### [`ls`](../../2.6.0/boot/core/src/boot/core.clj#L441)

```clojure
(ls fileset)
```

```
Get a set of TmpFile objects for all files in the fileset.
```

<hr>

### [`merge-env!`](../../2.6.0/boot/core/src/boot/core.clj#L812)

```clojure
(merge-env! & kvs)
```

```
Merges the new values into the current values for the given keys in the env
map. Uses a merging strategy that is appropriate for the given key (eg. uses
clojure.core/into for keys whose values are collections and simply replaces
Keys whose values aren't collections).
```

<hr>

### [`mv`](../../2.6.0/boot/core/src/boot/core.clj#L472)

```clojure
(mv fileset from-path to-path)
```

```
Given a fileset and two paths in the fileset, from-path and to-path, moves
the tmpfile at from-path to to-path, returning a new fileset.
```

<hr>

### [`mv-asset`](../../2.6.0/boot/core/src/boot/core.clj#L518)

```clojure
(mv-asset fileset tmpfiles)
```

```
FIXME: document
```

<hr>

### [`mv-resource`](../../2.6.0/boot/core/src/boot/core.clj#L590)

```clojure
(mv-resource fileset tmpfiles)
```

```
FIXME: document
```

<hr>

### [`mv-source`](../../2.6.0/boot/core/src/boot/core.clj#L554)

```clojure
(mv-source fileset tmpfiles)
```

```
FIXME: document
```

<hr>

### [`new-build-at`](../../2.6.0/boot/core/src/boot/core.clj#L37)

```
Latest build occured at time.
```

<hr>

### [`new-fileset`](../../2.6.0/boot/core/src/boot/core.clj#L100)

```
FIXME: document this
```

<hr>

### [`not-by-ext`](../../2.6.0/boot/core/src/boot/core.clj#L1195)

```clojure
(not-by-ext exts files)
```

```
This function is the same as `by-ext` but negated.
```

<hr>

### [`not-by-meta`](../../2.6.0/boot/core/src/boot/core.clj#L1154)

```clojure
(not-by-meta preds files)
```

```
Negated version of `by-meta`.

  This function will not unwrap the `File` objects from `TmpFiles`.
```

<hr>

### [`not-by-name`](../../2.6.0/boot/core/src/boot/core.clj#L1169)

```clojure
(not-by-name names files)
```

```
This function is the same as `by-name` but negated.
```

<hr>

### [`not-by-path`](../../2.6.0/boot/core/src/boot/core.clj#L1182)

```clojure
(not-by-path paths files)
```

```
This function is the same as `by-path` but negated.
```

<hr>

### [`not-by-re`](../../2.6.0/boot/core/src/boot/core.clj#L1208)

```clojure
(not-by-re res files)
```

```
This function is the same as `by-re` but negated.
```

<hr>

### [`output-dirs`](../../2.6.0/boot/core/src/boot/core.clj#L411)

```clojure
(output-dirs fileset)
```

```
FIXME: document this
```

<hr>

### [`output-files`](../../2.6.0/boot/core/src/boot/core.clj#L431)

```clojure
(output-files fileset)
```

```
Get a set of TmpFile objects corresponding to files with output role.
```

<hr>

### [`output-fileset`](../../2.6.0/boot/core/src/boot/core.clj#L436)

```clojure
(output-fileset fileset)
```

```
FIXME: document
```

<hr>

### [`patch!`](../../2.6.0/boot/core/src/boot/core.clj#L653)

```clojure
(patch! dest srcs & {:keys [ignore state link]})
```

```
Given prev-state
```

<hr>

### [`post-env!`](../../2.6.0/boot/core/src/boot/core.clj#L724)

```
Event handler called when the env atom is modified. This handler is for
performing side-effects associated with maintaining the application state in
the env atom. For example, when `:src-paths` is modified the handler adds
the new directories to the classpath.
```

<hr>

### [`pre-env!`](../../2.6.0/boot/core/src/boot/core.clj#L739)

```
This multimethod is used to modify how new values are merged into the boot
atom when `set-env!` is called. This function's result will become the new
value associated with the given `key` in the env atom.
```

<hr>

### [`rebuild!`](../../2.6.0/boot/core/src/boot/core.clj#L691)

```clojure
(rebuild!)
```

```
Manually trigger build watch.
```

<hr>

### [`replace-task!`](../../2.6.0/boot/core/src/boot/core.clj#L1031)

```clojure
(replace-task! & replacements)
```

```
Given a number of binding form and function pairs, this macro alters the
root bindings of task vars, replacing their values with the given functions.

Example:

(replace-task!
  [r repl] (fn [& xs] (apply r :port 12345 xs))
  [j jar]  (fn [& xs] (apply j :manifest {"howdy" "world"} xs)))
```

<hr>

### [`reset-build!`](../../2.6.0/boot/core/src/boot/core.clj#L888)

```clojure
(reset-build!)
```

```
Resets mutable build state to default values. This includes such things as
warning counters etc., state that is relevant to a single build cycle. This
function should be called before each build iteration.
```

<hr>

### [`reset-fileset`](../../2.6.0/boot/core/src/boot/core.clj#L875)

```clojure
(reset-fileset & [fileset])
```

```
Updates the user directories in the fileset with the latest project files,
returning a new immutable fileset. When called with no args returns a new
fileset containing only the latest project files.
```

<hr>

### [`rm`](../../2.6.0/boot/core/src/boot/core.clj#L453)

```clojure
(rm fileset files)
```

```
Removes files from the fileset tree, returning a new fileset object. This
does not affect the underlying filesystem in any way.
```

<hr>

### [`set-env!`](../../2.6.0/boot/core/src/boot/core.clj#L797)

```clojure
(set-env! & kvs)
```

```
Update the boot environment atom `this` with the given key-value pairs given
in `kvs`. See also `post-env!` and `pre-env!`. The values in the env map must
be both printable by the Clojure printer and readable by its reader. If the
value for a key is a function, that function will be applied to the current
value of that key and the result will become the new value (similar to how
clojure.core/update-in works.
```

<hr>

### [`set-sys-env!`](../../2.6.0/boot/core/src/boot/core.clj#L834)

```clojure
(set-sys-env! & kvs)
```

```
For each key value pair in kvs the system property corresponding to the key
is set. Keys and values must be strings, but the value can be nil or false
to remove the system property.
```

<hr>

### [`sync!`](../../2.6.0/boot/core/src/boot/core.clj#L645)

```clojure
(sync! dest & srcs)
```

```
Given a dest directory and one or more srcs directories, overlays srcs on
dest, removing files in dest that are not in srcs. Uses file modification
timestamps to decide which version of files to emit to dest. Uses hardlinks
instead of copying file contents. File modification times are preserved.
```

<hr>

### [`task-options!`](../../2.6.0/boot/core/src/boot/core.clj#L1054)

```clojure
(task-options! & task-option-pairs)
```

```
Given a number of task/map-of-curried-arguments pairs, replaces the root
bindings of the tasks with their curried values. For example:

    (task-options!
      repl {:port     12345}
      jar  {:manifest {:howdy "world"}})

You can update options, too, by providing a function instead of an option
map. This function will be passed the current option map and its result will
be used as the new one. For example:

    (task-options!
      repl #(update-in % [:port] (fnil inc 1234))
      jar  #(assoc-in % [:manifest "ILike"] "Turtles"))
```

<hr>

### [`temp-dir!`](../../2.6.0/boot/core/src/boot/core.clj#L345)

```clojure
(temp-dir! & args__584__auto__)
```

```
#'boot.core/temp-dir! was deprecated, please use #'boot.core/tmp-dir! instead
```

<hr>

### [`template`](../../2.6.0/boot/core/src/boot/core.clj#L1082)

```clojure
(template form)
```

```
The syntax-quote (aka quasiquote) reader macro as a normal macro. Provides
the unquote ~ and unquote-splicing ~@ metacharacters for templating forms
without performing symbol resolution.
```

<hr>

### [`tmp-dir`](../../2.6.0/boot/core/src/boot/core.clj#L372)

```clojure
(tmp-dir tmpfile)
```

```
Returns the temporary directory containing the tmpfile.
```

<hr>

### [`tmp-dir!`](../../2.6.0/boot/core/src/boot/core.clj#L341)

```clojure
(tmp-dir!)
```

```
Creates a boot-managed temporary directory, returning a java.io.File.
```

<hr>

### [`tmp-file`](../../2.6.0/boot/core/src/boot/core.clj#L378)

```clojure
(tmp-file tmpfile)
```

```
Returns the java.io.File object for the tmpfile.
```

<hr>

### [`tmp-get`](../../2.6.0/boot/core/src/boot/core.clj#L392)

```clojure
(tmp-get fileset path & [not-found])
```

```
Given a fileset and a path, returns the associated TmpFile record. If the
not-found argument is specified and the TmpFile is not in the fileset then
not-found is returned, otherwise nil.
```

<hr>

### [`tmp-path`](../../2.6.0/boot/core/src/boot/core.clj#L366)

```clojure
(tmp-path tmpfile)
```

```
Returns the tmpfile's path relative to the fileset root.
```

<hr>

### [`tmp-time`](../../2.6.0/boot/core/src/boot/core.clj#L384)

```clojure
(tmp-time tmpfile)
```

```
Returns the last modified timestamp for the tmpfile.
```

<hr>

### [`tmpdir`](../../2.6.0/boot/core/src/boot/core.clj#L376)

```clojure
(tmpdir & args__584__auto__)
```

```
#'boot.core/tmpdir was deprecated, please use #'boot.core/tmp-dir instead
```

<hr>

### [`tmpfile`](../../2.6.0/boot/core/src/boot/core.clj#L382)

```clojure
(tmpfile & args__584__auto__)
```

```
#'boot.core/tmpfile was deprecated, please use #'boot.core/tmp-file instead
```

<hr>

### [`tmpget`](../../2.6.0/boot/core/src/boot/core.clj#L398)

```clojure
(tmpget & args__584__auto__)
```

```
#'boot.core/tmpget was deprecated, please use #'boot.core/tmp-get instead
```

<hr>

### [`tmppath`](../../2.6.0/boot/core/src/boot/core.clj#L370)

```clojure
(tmppath & args__584__auto__)
```

```
#'boot.core/tmppath was deprecated, please use #'boot.core/tmp-path instead
```

<hr>

### [`tmptime`](../../2.6.0/boot/core/src/boot/core.clj#L388)

```clojure
(tmptime & args__584__auto__)
```

```
#'boot.core/tmptime was deprecated, please use #'boot.core/tmp-time instead
```

<hr>

### [`touch`](../../2.6.0/boot/core/src/boot/core.clj#L1119)

```clojure
(touch f)
```

```
Same as the Unix touch(1) program.
```

<hr>

### [`user-dirs`](../../2.6.0/boot/core/src/boot/core.clj#L400)

```clojure
(user-dirs fileset)
```

```
Get a list of directories containing files that originated in the project's
source, resource, or asset paths.
```

<hr>

### [`user-files`](../../2.6.0/boot/core/src/boot/core.clj#L415)

```clojure
(user-files fileset)
```

```
Get a set of TmpFile objects corresponding to files that originated in
the project's source, resource, or asset paths.
```

<hr>

### [`watch-dirs`](../../2.6.0/boot/core/src/boot/core.clj#L665)

```clojure
(watch-dirs callback dirs & {:keys [debounce]})
```

```
Watches dirs for changes and calls callback with set of changed files
when file(s) in these directories are modified. Returns a thunk which
will stop the watcher.

The watcher uses the somewhat quirky native filesystem event APIs. A
debounce option is provided (in ms, default 10) which can be used to
tune the watcher sensitivity.
```

<hr>

### [`with-pass-thru`](../../2.6.0/boot/core/src/boot/core.clj#L1008)

```clojure
(with-pass-thru bind & body)
```

```
Given a binding and body expressions, constructs a task handler. The body
expressions are evaluated for side effects with the current fileset bound
to binding. The current fileset is then passed to the next handler and the
result is then returned up the handler stack.
```

<hr>

### [`with-post-wrap`](../../2.6.0/boot/core/src/boot/core.clj#L982)

```clojure
(with-post-wrap bind & body)
```

```
Given a binding and body expressions, constructs a task handler. The next
handler is called with the current fileset, and the result is bound to
binding. The body expressions are then evaluated for side effects and the
bound fileset is returned up the handler stack. Roughly equivalent to:

    (fn [next-handler]
      (fn [fileset]
        (let [binding (next-handler fileset)]
          (do ... ...)
          binding)))

where ... are the given body expressions.
```

<hr>

### [`with-pre-wrap`](../../2.6.0/boot/core/src/boot/core.clj#L958)

```clojure
(with-pre-wrap bind & body)
```

```
Given a binding and body expressions, constructs a task handler. The body
expressions are evaluated with the current fileset bound to binding, and the
result is passed to the next handler in the pipeline. The fileset obtained
from the next handler is then returned up the handler stack. The body must
evaluate to a fileset object. Roughly equivalent to:

    (fn [next-handler]
      (fn [binding]
        (next-handler (do ... ...))))

where ... are the given body expressions.
```

<hr>

### [`yaml-generate`](../../2.6.0/boot/core/src/boot/core.clj#L1107)

```clojure
(yaml-generate x)
```

```
Same as clj-yaml.core/generate-string.
```

<hr>

### [`yaml-parse`](../../2.6.0/boot/core/src/boot/core.clj#L1113)

```clojure
(yaml-parse x)
```

```
Same as clj-yaml.core/parse-string.
```

<hr>

