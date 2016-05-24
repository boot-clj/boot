# boot.task.built-in

Boot built-in tasks.

##### Info

 [`help`](#help) [`show`](#show)

##### Develop

 [`notify`](#notify) [`repl`](#repl) [`speak`](#speak) [`wait`](#wait) [`watch`](#watch)

##### Fileset

 [`add-repo`](#add-repo) [`sift`](#sift) [`uber`](#uber)

##### Build

 [`aot`](#aot) [`javac`](#javac)

##### Package

 [`jar`](#jar) [`pom`](#pom) [`war`](#war) [`web`](#web) [`zip`](#zip)

##### Deploy

 [`install`](#install) [`push`](#push) [`target`](#target)

##### Deprecated

 [`checkout`](#checkout)

<hr>

### [`add-repo`](../../2.6.0/boot/core/src/boot/task/built_in.clj#L497)

```clojure
(add-repo & {:keys [help untracked ref], :as *opts*})
```

```
Add all files in project git repo to fileset.

The ref option (default HEAD) facilitates pulling files from tags or specific
commits.

Keyword Args:
  :help       bool  Print this help info.
  :untracked  bool  Add untracked (but not ignored) files.
  :ref        str   The git reference for the desired file tree.
```

<hr>

### [`aot`](../../2.6.0/boot/core/src/boot/task/built_in.clj#L645)

```clojure
(aot & {:keys [help all namespace], :as *opts*})
```

```
Perform AOT compilation of Clojure namespaces.

Keyword Args:
  :help       bool    Print this help info.
  :all        bool    Compile all namespaces.
  :namespace  #{sym}  The set of namespaces to compile.
```

<hr>

### [`checkout`](../../2.6.0/boot/core/src/boot/task/built_in.clj#L75)

```clojure
(checkout & {:keys [help dependencies], :as *opts*})
```

```
Checkout dependencies task. DEPRECATED.

This task facilitates working on a project and its dependencies at the same
time, by extracting the dependency jar contents into the fileset. Transitive
dependencies will be added to the class path automatically.

You'll need at least two boot instances---one to build the dependency jar and
the other to build the project. For example:

    $ boot watch pom -p foo/bar -v 1.2.3-SNAPSHOT jar install

to build the dependency jar, and

    $ boot repl -s watch checkout -d foo/bar:1.2.3-SNAPSHOT cljs serve

to build the project with the checkout dependency [foo/bar "1.2.3"].

Keyword Args:
  :help          bool         Print this help info.
  :dependencies  [[sym str]]  The vector of checkout dependencies.
```

<hr>

### [`help`](../../2.6.0/boot/core/src/boot/task/built_in.clj#L31)

```clojure
(help & {:keys [help], :as *opts*})
```

```
Print usage info and list available tasks.

Keyword Args:
  :help  bool  Print this help info.
```

<hr>

### [`install`](../../2.6.0/boot/core/src/boot/task/built_in.clj#L777)

```clojure
(install & {:keys [help file pom], :as *opts*})
```

```
Install project jar to local Maven repository.

The --file option allows installation of arbitrary jar files. If no
file option is given then any jar artifacts created during the build
will be installed.

The pom.xml file that's required when installing a jar can usually be
found in the jar itself. However, sometimes a jar might contain more
than one pom.xml file or may not contain one at all.

The --pom option can be used in these situations to specify which
pom.xml file to use. The optarg denotes either the path to a pom.xml
file in the filesystem or a subdir of the META-INF/maven/ dir in which
the pom.xml contained in the jar resides.

Example:

  Given a jar file (warp-0.1.0.jar) with the following contents:

      .
      ├── META-INF
      │   ├── MANIFEST.MF
      │   └── maven
      │       └── tailrecursion
      │           └── warp
      │               ├── pom.properties
      │               └── pom.xml
      └── tailrecursion
          └── warp.clj

  The jar could be installed with the following boot command:

      $ boot install -f warp-0.1.0.jar -p tailrecursion/warp

Keyword Args:
  :help  bool  Print this help info.
  :file  str   The jar file to install.
  :pom   str   The pom.xml file to use.
```

<hr>

### [`jar`](../../2.6.0/boot/core/src/boot/task/built_in.clj#L707)

```clojure
(jar & {:keys [help file manifest main], :as *opts*})
```

```
Build a jar file for the project.

Keyword Args:
  :help      bool       Print this help info.
  :file      str        The target jar file name.
  :manifest  {str str}  The jar manifest map.
  :main      sym        The namespace containing the -main function.
```

<hr>

### [`javac`](../../2.6.0/boot/core/src/boot/task/built_in.clj#L665)

```clojure
(javac & {:keys [help options], :as *opts*})
```

```
Compile java sources.

Keyword Args:
  :help     bool   Print this help info.
  :options  [str]  List of options passed to the java compiler.
```

<hr>

### [`notify`](../../2.6.0/boot/core/src/boot/task/built_in.clj#L175)

```clojure
(notify & {:keys [help audible visual audible-notify-fn visual-notify-fn theme soundfiles messages title icon uid], :as *opts*})
```

```
Aural and visual notifications during build.

Default audio themes: system (the default), ordinance, pillsbury,
and woodblock. New themes can be included via jar dependency with
the sound files as resources:

    boot
    └── notify
        ├── <theme-name>_failure.mp3
        ├── <theme-name>_success.mp3
        └── <theme-name>_warning.mp3

Sound files specified individually take precedence over theme sounds.

For visual notifications, there is a default implementation that
tries to use the `terminal-notifier' program on OS X systems, and
the `notify-send' program on Linux systems.

You can also supply custom notification functions via the *-notify-fn
options. Both are functions that take one argument which is a map of
options.

The audible notification function will receive a map with three keys
- :type, :file, and :theme.

The visual notification function will receive a map with four keys
- :title, :uid, :icon, and :message.

Keyword Args:
  :help               bool      Print this help info.
  :audible            bool      Play an audible notification
  :visual             bool      Display a visual notification
  :audible-notify-fn  sym       A function to be used for audible notifications in place of the default method.
  :visual-notify-fn   sym       A function to be used for visual notifications in place of the default method
  :theme              str       The name of the audible notification sound theme
  :soundfiles         {kw str}  Sound files overriding theme sounds. Keys can be :success, :warning or :failure
  :messages           {kw str}  Templates overriding default messages. Keys can be :success, :warning or :failure
  :title              str       Title of the notification
  :icon               str       Full path of the file used as notification icon
  :uid                str       Unique ID identifying this boot process
```

<hr>

### [`pom`](../../2.6.0/boot/core/src/boot/task/built_in.clj#L399)

```clojure
(pom & {:keys [help project version description classifier packaging url scm license developers dependencies], :as *opts*})
```

```
Create project pom.xml file.

The project and version must be specified to make a pom.xml.

Keyword Args:
  :help          bool         Print this help info.
  :project       sym          The project id (eg. foo/bar).
  :version       str          The project version.
  :description   str          The project description.
  :classifier    str          The project classifier.
  :packaging     str          The project packaging type, i.e. war, pom
  :url           str          The project homepage url.
  :scm           {kw str}     The project scm map (KEY is one of url, tag, connection, developerConnection).
  :license       {str str}    The map {name url} of project licenses.
  :developers    {str str}    The map {name email} of project developers.
  :dependencies  [[sym str]]  The project dependencies vector (overrides boot env dependencies).
```

<hr>

### [`push`](../../2.6.0/boot/core/src/boot/task/built_in.clj#L826)

```clojure
(push & {:keys [help file pom file-regex gpg-sign gpg-user-id gpg-keyring gpg-passphrase repo repo-map tag ensure-branch ensure-clean ensure-release ensure-snapshot ensure-tag ensure-version], :as *opts*})
```

```
Deploy jar file to a Maven repository.

If the file option is not specified the task will look for jar files
created by the build pipeline. The jar file(s) must contain pom.xml
entries.

The repo option is required. The repo option is used to get repository
map from Boot envinronment. Additional repo-map option can be used to
add options, like credentials, or to provide complete repo-map if Boot
envinronment doesn't hold the named repository.

Keyword Args:
  :help             bool      Print this help info.
  :file             str       The jar file to deploy.
  :pom              str       The pom.xml file to use (see install task).
  :file-regex       #{regex}  The set of regexes of paths to deploy.
  :gpg-sign         bool      Sign jar using GPG private key.
  :gpg-user-id      str       The name or key-id used to select the signing key.
  :gpg-keyring      str       DEPRECATED: The path to secring.gpg file to use for signing.
  :gpg-passphrase   str       The passphrase to unlock GPG signing key.
  :repo             str       The name of the deploy repository.
  :repo-map         edn       The repository map of the deploy repository.
  :tag              bool      Create git tag for this version.
  :ensure-branch    str       The required current git branch.
  :ensure-clean     bool      Ensure that the project git repo is clean.
  :ensure-release   bool      Ensure that the current version is not a snapshot.
  :ensure-snapshot  bool      Ensure that the current version is a snapshot.
  :ensure-tag       str       The SHA1 of the commit the pom's scm tag must contain.
  :ensure-version   str       The version the jar's pom must contain.
```

<hr>

### [`repl`](../../2.6.0/boot/core/src/boot/task/built_in.clj#L354)

```clojure
(repl & {:keys [help server client eval bind host init skip-init port pod init-ns middleware handler], :as *opts*})
```

```
Start a REPL session for the current project.

If no bind/host is specified the REPL server will listen on 127.0.0.1 and
the client will connect to 127.0.0.1.

If no port is specified the server will choose a random one and the client
will read the .nrepl-port file and use that.

The *default-middleware* and *default-dependencies* atoms in the boot.repl
namespace contain vectors of default REPL middleware and REPL dependencies to
be loaded when starting the server. You may modify these in your build.boot
file.

Keyword Args:
  :help        bool   Print this help info.
  :server      bool   Start REPL server only.
  :client      bool   Start REPL client only.
  :eval        edn    The form the client will evaluate in the boot.user ns.
  :bind        str    The address server listens on.
  :host        str    The host client connects to.
  :init        str    The file to evaluate in the boot.user ns.
  :skip-init   bool   Skip default client initialization code.
  :port        int    The port to listen on and/or connect to.
  :pod         str    The name of the pod to start nREPL server in (core).
  :init-ns     sym    The initial REPL namespace.
  :middleware  [sym]  The REPL middleware vector.
  :handler     sym    The REPL handler (overrides middleware options).
```

<hr>

### [`show`](../../2.6.0/boot/core/src/boot/task/built_in.clj#L245)

```clojure
(show & {:keys [help fake-classpath classpath deps env fileset list-pods pedantic pods update-snapshots updates verify-deps], :as *opts*})
```

```
Print project/build info (e.g. dependency graph, etc).

Keyword Args:
  :help              bool   Print this help info.
  :fake-classpath    bool   Print the project's fake classpath.
  :classpath         bool   Print the project's full classpath.
  :deps              bool   Print project dependency graph.
  :env               bool   Print the boot env map.
  :fileset           bool   Print the build fileset object.
  :list-pods         bool   Print the names of all active pods.
  :pedantic          bool   Print graph of dependency conflicts.
  :pods              regex  The name filter used to select which pods to inspect.
  :update-snapshots  bool   Include snapshot versions in updates searches.
  :updates           bool   Print newer releases of outdated dependencies.
  :verify-deps       bool   Include signature status of each dependency in graph.
```

<hr>

### [`sift`](../../2.6.0/boot/core/src/boot/task/built_in.clj#L437)

```clojure
(sift & {:keys [help to-asset to-resource to-source add-asset add-resource add-source add-jar with-meta add-meta move include invert], :as *opts*})
```

```
Transform the fileset, matching paths against regexes.

The --to-asset, --to-resource, and --to-source options move matching paths
to the corresponding section of the fileset. This can be used to make source
files into resource files, for example, etc. If --invert is also specified
the transformation is done to paths that DO NOT match.

The --add-asset, --add-resource, and --add-source options add the contents
of a directory to the fileset as assets, resources, or sources, respectively.
The --invert option has no effect on these options.

The --add-jar option extracts the contents of a jar file on the classpath
and adds them to the fileset. The PROJECT part of the argument specifies the
group-id/artifact-id symbol associated with the jar, and the MATCH portion
selects which entries in the jar will be extracted. If --invert is also
specified then entries whose paths DO NOT match the regex will be extracted.

The --with-meta option specifies a set of metadata keys files in the fileset
must have. Files without one of these keys will be filtered out. If --invert
is also specified then files that DO have one of these keys will be filtered
out, instead.

The --add-meta option adds a key to the metadata map associated with paths
matching the regex portion of the argument. For example:

    boot sift --add-meta 'foo$':bar

merges {:bar true} into the metadata map associated with all paths that end
with 'foo'. If --invert is also specified the metadata is added to paths
that DO NOT match the regex portion.

The --move option applies a find/replace transformation on all paths in the
output fileset. The --invert option has no effect on this operation.

The --include option specifies a set of regexes that will be used to filter
the fileset. Only paths matching one of these will be kept. If --invert is
also specified then only paths NOT matching one of the regexes will be kept.

Keyword Args:
  :help          bool         Print this help info.
  :to-asset      #{regex}     The set of regexes of paths to move to assets.
  :to-resource   #{regex}     The set of regexes of paths to move to resources.
  :to-source     #{regex}     The set of regexes of paths to move to sources.
  :add-asset     #{str}       The set of directory paths to add to assets.
  :add-resource  #{str}       The set of directory paths to add to resources.
  :add-source    #{str}       The set of directory paths to add to sources.
  :add-jar       {sym regex}  The map of jar to path regex of entries in jar to unpack.
  :with-meta     #{kw}        The set of metadata keys files must have.
  :add-meta      {regex kw}   The map of path regex to meta key to add.
  :move          {regex str}  The map of regex to replacement path strings.
  :include       #{regex}     The set of regexes that paths must match.
  :invert        bool         Invert the sense of matching.
```

<hr>

### [`speak`](../../2.6.0/boot/core/src/boot/task/built_in.clj#L131)

```clojure
(speak & {:keys [help theme success warning failure], :as *opts*})
```

```
Audible notifications during build.

Default themes: system (the default), ordinance, pillsbury, and
woodblock. New themes can be included via jar dependency with the
sound files as resources:

    boot
    └── notify
        ├── <theme-name>_failure.mp3
        ├── <theme-name>_success.mp3
        └── <theme-name>_warning.mp3

Sound files specified individually take precedence over theme sounds.

Keyword Args:
  :help     bool  Print this help info.
  :theme    str   The notification sound theme.
  :success  str   The sound file to play when the build is successful.
  :warning  str   The sound file to play when there are warnings reported.
  :failure  str   The sound file to play when the build fails.
```

<hr>

### [`target`](../../2.6.0/boot/core/src/boot/task/built_in.clj#L296)

```clojure
(target & {:keys [help dir no-link no-clean], :as *opts*})
```

```
Writes output files to the given directory on the filesystem.

Keyword Args:
  :help      bool    Print this help info.
  :dir       #{str}  The set of directories to write to (target).
  :no-link   bool    Don't create hard links.
  :no-clean  bool    Don't clean target before writing project files.
```

<hr>

### [`uber`](../../2.6.0/boot/core/src/boot/task/built_in.clj#L514)

```clojure
(uber & {:keys [help as-jars include-scope exclude-scope include exclude merge], :as *opts*})
```

```
Add jar entries from dependencies to fileset.

Use this task before the packaging task (jar, war, etc.) to create
uberjars, uberwars, etc. This provides the means to package the project
with all of its dependencies included.

By default, entries from dependencies with the following scopes will be
copied to the fileset: compile, runtime, and provided. The --include-scope
and --exclude-scope options may be used to add or remove scope(s) from this
set.

The --as-jars option pulls in dependency jars without exploding them such
that the jarfiles themselves are copied into the fileset. When using the
--as-jars option you need a special classloader like a servlet container
(e.g. Tomcat, Jetty) that will add the jars to the application classloader.

When jars are exploded, the --include and --exclude options control which
paths are added to the uberjar; a path is only added if it matches an
--include regex and does not match any --exclude regexes.

The --exclude option default is:

    #{ #"(?i)^META-INF/INDEX.LIST$"
       #"(?i)^META-INF/[^/]*\.(MF|SF|RSA|DSA)$" }

And --include option default is:

    #{ #".*" }

If exploding the jars results in duplicate entries, they will be merged
using the rules specified by the --merge option. A merge rule is a
[regex fn] pair, where fn takes three parameters:

  - an InputStream for the previous entry,
  - an InputStream of the new entry,
  - and an OutputStream that will replace the entry.

The --merge option default is:

    [[ #"data_readers.clj$"    into-merger       ]
     [ #"META-INF/services/.*" concat-merger     ]
     [ #".*"                   first-wins-merger ]]

The merge rule regular expressions are tested in order, and the fn from
the first match is applied.

Setting the --include, --exclude, or --merge options replaces the default.

Keyword Args:
  :help           bool            Print this help info.
  :as-jars        bool            Copy entire jar files instead of exploding them.
  :include-scope  #{str}          The set of scopes to add.
  :exclude-scope  #{str}          The set of scopes to remove.
  :include        #{regex}        The set of regexes that paths must match.
  :exclude        #{regex}        The set of regexes that paths must not match.
  :merge          [[regex code]]  The list of duplicate file mergers.
```

<hr>

### [`wait`](../../2.6.0/boot/core/src/boot/task/built_in.clj#L285)

```clojure
(wait & {:keys [help time], :as *opts*})
```

```
Wait before calling the next handler.

Waits forever if the --time option is not specified.

Keyword Args:
  :help  bool  Print this help info.
  :time  int   The interval in milliseconds.
```

<hr>

### [`war`](../../2.6.0/boot/core/src/boot/task/built_in.clj#L738)

```clojure
(war & {:keys [help file], :as *opts*})
```

```
Create war file for web deployment.

Keyword Args:
  :help  bool  Print this help info.
  :file  str   The target war file name.
```

<hr>

### [`watch`](../../2.6.0/boot/core/src/boot/task/built_in.clj#L307)

```clojure
(watch & {:keys [help quiet verbose manual], :as *opts*})
```

```
Call the next handler when source files change.

Debouncing time is 10ms by default.

Keyword Args:
  :help     bool  Print this help info.
  :quiet    bool  Suppress all output from running jobs.
  :verbose  bool  Print which files have changed.
  :manual   bool  Use a manual trigger instead of a file watcher.
```

<hr>

### [`web`](../../2.6.0/boot/core/src/boot/task/built_in.clj#L611)

```clojure
(web & {:keys [help serve create destroy context-create context-destroy], :as *opts*})
```

```
Create project web.xml file.

The --serve option is required. The others are optional.

Keyword Args:
  :help             bool  Print this help info.
  :serve            sym   The 'serve' callback function.
  :create           sym   The 'create' callback function.
  :destroy          sym   The 'destroy' callback function.
  :context-create   sym   The context 'create' callback function, called when the servlet is first loaded by the container.
  :context-destroy  sym   The context 'destroyed' callback function, called when the servlet is unloaded by the container.
```

<hr>

### [`zip`](../../2.6.0/boot/core/src/boot/task/built_in.clj#L762)

```clojure
(zip & {:keys [help file], :as *opts*})
```

```
Build a zip file for the project.

Keyword Args:
  :help  bool  Print this help info.
  :file  str   The target zip file name.
```

<hr>

