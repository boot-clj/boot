# Changes

## 2.7.1

#### Fixed

- Fixed a Windows regression in the user script generation code introduced by [f339a8d](https://github.com/boot-clj/boot/commit/f339a8d9464bfc0e05f9c963744377e91a042c48). [#541][541]

#### Tasks

- Added `-m, --mode` option to the `target` task &mdash; specifies the file
  mode for written files &mdash; should only be used when default `rw-------`
  is not enough. [#537][537]

## 2.7.0

#### Improved

- Follow symlinks when building fileset from project dirs [#483][483].
- Documented `boot.core/add-cached-{asset,source,resource}` fns.
- Documented `boot.core/patch!` fn [#497][497].
- Warn when asked to load a version of Clojure into the core pod (via
  `:dependencies`) that is different from the implicitly loaded version
  specified by `BOOT_CLOJURE_VERSION` [#230][230], [#469][469].
- Corrected docstring for `boot.pod/canonical-coord`.
- Throw helpful exception when `deftask` argument vector isn't a vector [#487][487].
- Now uses io.aviso/pretty 0.1.33: this affects the order of reported stack frames [#355][355].
  The old behavior [can be restored with user configuration][pretty-config].
- Exceptions are now always reported using pretty, regardless of the setting of 
  BOOT_COLOR (or the -C flag), but when colorization is disabled, pretty
  exception reporting will not use an ANSI color codes in its output.
  This is often preferable when output from Boot is being logged to a
  file.
- Support [managed dependencies](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html) by upgrading [pomegranate](https://github.com/cemerick/pomegranate) to 0.3.1. [#526][526]
- Use the [Fastly CDN version of Clojars](https://groups.google.com/d/msg/clojure/WhBu4CB_ekg/YzE9e-iBAAAJ) by default. [#540][540]

#### Fixed

- Print stack trace when core pod fails to load [#480][480].
- Fix issue where `boot.util/map-as-dep` would flatten collections like
  `:exclusions` in the dependency vector [#479][479].
- Use default voice when calling `say` on OSX [#476][476].
- Fix typo in the `notify` task that prevented OSX from finding the
  `terminal-notifier` program [#478][478].
- Fix typo in the `notify` task that caused exceptions on OSX [#491][491].
- Don't throw exceptions when source files are missing during filesystem patch
  operations [#471][471], [#477][477].
- Preserve fileset metadata when TmpFiles are overwritten with `add-resource`,
  `add-source`, etc.
- Format paths in `boot.class.path` and `fake.class.path` system properties
  with correct, platform-specific paths [#488][488].
- Eliminate runtime reflection in `boot.core/deftask` macro [#490][490].
d- Create bootscript tmpfile with mode `0600` instead of `0664`.
- Previously, setting BOOT_COLOR to false was ignored, and the isWindows
  check overruled the BOOT_COLOR selection. Now, the default for colorization
  from isWindows is set only if BOOT_COLOR is blank [#536][536]

#### Tasks

- Added the `with-cp` task &mdash; use `java -cp` style classpath strings
  instead of Maven dependencies.
- The `pom` task now adds `:project` metadata to the created pom.xml and
  pom.properties TmpFiles in the fileset. This metadata is used by eg. the
  `jar` task to select the "real" pom from multiple poms that might be in the
  fileset from the `uber` task, etc. [#451][451]
- The `watch` task now accepts `--include` and `--exclude` options to restrict
  the set of paths that will trigger a rebuild [#312][312].
- The `watch` task now accepts `--debounce` option to adjust how long it will
  wait for all filesystem events to have fired before a rebuild is triggered.
- On systems without audio output ,the `notify` task now prints an error
  message instead of throwing an exception [#523][523]

##### API Functions

- Added `boot.pod/make-pod-cp` &mdash; creates a new pod from a given classpath.
- Added `boot.pod/canonical-id` &mdash; returns the canonical form of a maven
  dependency id symbol.
- Added `boot.pod/full-id` &mdash; returns the fully-qualified form of a maven
  dependency id symbol.
- Added `:meta` option to `boot.core/add-{asset,source,resource}` fns (and their
  `add-cached-{asset,source,resource}` variants &mdash; merges a map of metadata
  into all TmpFiles added to the fileset.

##### Boot CLI Parsing

- The `[` and `]` characters can now be used to group tasks with their options
  and, more importantly, positional parameters. In the task body the positional
  parameters are bound to `*args*` [#374][374].

##### Boot Options

- Added `-E, --exclusions` &mdash; adds symbol to env `:exclusions` [#472][472].
- Added `-f, --file` &mdash; evaluates the contents of a file just like with
  the shebang script, but easier to use on platforms like Windows that don't
  have great shebang support [#465][465].
- Added `-i, --init` &mdash; evaluates a form after evaluating the profile.boot
  forms but before the main script or build.boot forms [#465][465].
- Added `-x, --exclude-clojure` &mdash; adds `org.clojure/clojure` as a global
  exclusion (useful in combination with `--dependencies` when you don't have a
  build.boot file, as most dependencies will depend on some random version of
  clojure and you'll get a warning about it) [#230][230], [#469][469].
- Removed `-t, --target-path` and `-T, --no-target` [#475][475].

##### Task Options

- Added `-p, --project` option to the `jar` task &mdash; specifies the project
  id when there are multiple pom.xml files &mdash; should only be needed in the
  case where the jar will contain multiple poms and either the desired pom was
  not created via the `pom` task or there are multiple poms created by the `pom`
  task in the fileset [#451][451].
- Added `-C, --no-color` option to the `repl` task &mdash; disables ANSI color
  codes in REPL client output.
  
##### Pods

- Upgraded dynapath to 0.2.5 in order to support Java 9. [#528][528], [#539][539]

##### Boot Environment

- Removed `:target-path` [#475][475].

#### Deprecated

- The `speak` task, replaced by `notify`.

#### Java 9

> Java 9 is slated for release sometime next year. It introduces breaking
> changes, and Boot might need to be continually updated to ensure that we're
> compatible with Java 9 once it's released.

- Improvements to work with Java 9 (boot repl works on Java 9-ea+148) that
  upgrade dynapath to 0.2.5. These changes require a newer boot-bin to function,
  but are backward compatible on Java 7 and 8. [#539][539]

[230]: https://github.com/boot-clj/boot/issues/230
[312]: https://github.com/boot-clj/boot/issues/312
[374]: https://github.com/boot-clj/boot/issues/374
[451]: https://github.com/boot-clj/boot/issues/451
[465]: https://github.com/boot-clj/boot/issues/465
[469]: https://github.com/boot-clj/boot/issues/469
[471]: https://github.com/boot-clj/boot/issues/471
[472]: https://github.com/boot-clj/boot/issues/472
[475]: https://github.com/boot-clj/boot/issues/475
[476]: https://github.com/boot-clj/boot/issues/476
[477]: https://github.com/boot-clj/boot/issues/477
[478]: https://github.com/boot-clj/boot/issues/478
[479]: https://github.com/boot-clj/boot/issues/479
[480]: https://github.com/boot-clj/boot/issues/480
[483]: https://github.com/boot-clj/boot/issues/483
[487]: https://github.com/boot-clj/boot/issues/487
[488]: https://github.com/boot-clj/boot/issues/488
[490]: https://github.com/boot-clj/boot/issues/490
[491]: https://github.com/boot-clj/boot/issues/491
[497]: https://github.com/boot-clj/boot/issues/497
[528]: https://github.com/boot-clj/boot/pull/528
[523]: https://github.com/boot-clj/boot/pull/523
[526]: https://github.com/boot-clj/boot/pull/526
[536]: https://github.com/boot-clj/boot/pull/536
[540]: https://github.com/boot-clj/boot/pull/540
[539]: https://github.com/boot-clj/boot/pull/539
[541]: https://github.com/boot-clj/boot/issues/541
[537]: https://github.com/boot-clj/boot/pull/537
[355]: https://github.com/boot-clj/boot/issues/355

## 2.6.0

#### Improved

- More efficient syncing of project directories with Boot's internal ones.
- Easier to read tree representation for the `show --fileset` output.

#### Fixed

- Don't set `:update :always` in aether when resolving Boot's own dependencies
  unless Boot is being updated.
- Correctly handle case when `:source-paths` or `:resource-paths` are set to
  the empty set (`#{}`).
- Correctly set last modified time when copying classpath resource.

#### Development

- Boot test suite (!!!) to test Boot itself, with parallel test runner capability
  and continuous integration.

##### API Functions

- `boot.pod/this-pod` &mdash; a `WeakReference` to the current pod
- `boot.pod/with-invoke-in` &mdash; low-level invocation, no serialization
- `boot.pod/with-invoke-worker` &mdash; as above but invokes in the worker pod
- `boot.pod/pod-name` &mdash; get/set the name of a pod
- `boot.pod/coord->map` &mdash; dependency vector to map helper function
- `boot.pod/map->coord` &mdash; map to dependency vector helper function
- `boot.pod/resource-last-modified` &mdash; returns last modified time of a classpath resource
- `boot.core/get-checkouts` &mdash; returns a map of info about loaded checkout dependencies
- `boot.util/dbug*` &mdash; like `boot.util/dbug` but a macro (doesn't eval its
  arguments unless the verbosity level is DEBUG or above)

##### Boot Options

- `-c`, `--checkouts` boot option / `:checkouts` env key &mdash; deeper
  integration for checkout dependencies
- `-o`, `--offline` boot option &mdash; disable downloading Maven dependencies
  from remote repositories (doesn't apply to Boot's own dependencies)
- `-U`, `--update-snapshot` boot option &mdash; updates boot to latest snapshot
  version
- optional argument to `-u`, `--update` &mdash; sets global default boot version

##### Task Options

- `-v`, `--verify-deps` option to `show` task &mdash; verify jar signatures and
  show deps tree [#375][375]

##### Boot Environment

- wagon dependencies now accept a `:schemes` key &mdash; specify the handler
  classes for the wagon when the wagon jar has no `leiningen/wagons.clj` entry.
- `BOOT_CERTIFICATES` &mdash; specify file paths for SSL certificates.
- `BOOT_CLOJARS_REPO` &mdash; specify Maven repo url for `clojars`.
- `BOOT_CLOJARS_MIRROR` &mdash; specify Maven mirror url for `clojars`.
- `BOOT_MAVEN_CENTRAL_REPO` &mdash; specify Maven repo url for `maven-central`.
- `BOOT_MAVEN_CENTRAL_MIRROR` &mdash; specify Maven mirror url for `maven-central`.

#### Deprecated

- The `checkout` task, replaced by the `--checkouts` boot option

[345]: https://github.com/boot-clj/boot/issues/345

## 2.5.5

#### Fixed

- Issue with 2.5.4 where it was possible for boot to exit before all files
  were written to target dir.

## 2.5.4

#### Fixed

- The `target` task falls back to copying when hardlinks aren't possible
  [#373][373].
- Use a `ByteArrayInputStream` instead of a `StringBufferInputStream` when
  parsing `pom.xml` strings.

[373]: https://github.com/boot-clj/boot/issues/373

## 2.5.3

#### Improved

- Added docstrings to all previously undocumented vars in API namespaces.

## 2.5.2

#### Fixed

- Warn and remove conflicting files from the fileset [#361][361], [#364][364].
- Restore `--as-jars` option to `uber` task that was accidentally removed.
- Don't try to read `pom.xml` if there are none in the fileset.
- Correctly handle extra arguments to `clifn` and throw on unknown options [#346][346].

#### Improved

- Pretty-print boot script when `--verbose` boot option is specified [#315][315].
- Copy jars into cache when using the `--as-jars` option to `uber` [#290][290].
- Ignore source files matching regexes specified in `.bootignore` file [#348][348].

[290]: https://github.com/boot-clj/boot/issues/290
[315]: https://github.com/boot-clj/boot/issues/315
[346]: https://github.com/boot-clj/boot/issues/346
[348]: https://github.com/boot-clj/boot/issues/348
[361]: https://github.com/boot-clj/boot/issues/361
[364]: https://github.com/boot-clj/boot/issues/364

## 2.5.1

#### Fixed

- Misleading warning about `BOOT_EMIT_TARGET` [#356][356].
- Cross-device link errors when moving files from temp dirs [#362][362].
- Issue with `--repo-map` option for `push` task [#358][358].
- Jar task not creating `MANIFEST.MF` in uberjar [#360][360].
- Jar task not using "friendly name" when there is exactly one pom.xml.
- Issue where `target` task would do nothing when no `--dir` option given.
- Add `--no-clean` option to disable cleaning of destination in `target` task.
- Don't throw exception when `deftask` argument specs are invalid; warn instead.

[356]: https://github.com/boot-clj/boot/issues/356
[358]: https://github.com/boot-clj/boot/issues/358
[360]: https://github.com/boot-clj/boot/issues/360
[362]: https://github.com/boot-clj/boot/issues/362

## 2.5.0

#### Breaking

- The `gpg` binary is now used instead of bouncycastle for signing
  jars and reading encrypted credentials files. There may be different
  behavior when resolving default keys, etc.

#### Added

- Added `target` task and `BOOT_EMIT_TARGET` env var [#305][305].
- Added `--developers` and `--dependencies` options to `pom` task [#233][233].
- Added (ALPHA) `send!` function to work around issues passing large
  collections to pods via `with-eval-in` [#339][339].
- Added `add-cached-{asset,source,resource}` core functions.
- Added `launch-nrepl` core function for starting repl servers in pods from
  the repl.
- Added `gpg-decrypt` core function to decrypt gpg encrypted files.
- Added `configure-repositories!` core function to configure maven repos
  dynamically (as a callback to add credentials, etc) [#274][274], [#311][311].
- The `push` task can now be provided with `--repo-map` option to set the
  deployment repository. This is useful for example in case a repository
  needs different settings for downloading dependencies and deploying,
  like additional credentials [#274][274], [#311][311].
- The `install` and `push` tasks now accept a `--pom` option which can be
  used to specify which `pom.xml` file to use [#112][112], [#278][278].
- The `repl` task now accepts a `--pod` option which can be used to start
  a repl in a specific pod.
- The `show` task now accepts a `--list-pods` option to show the names of
  all active pods.

#### Improved

- Better `uber` task performance [#94][94].
- Better `sift` task performance.
- Better fileset performance.
- Better pod-pool performance [#271][271].
- Better cli option error messages [#285][285] & [#322][322].
- Throw exception when source, resource, or asset paths overlap [#235][235].

#### Fixed

- Added last modified time to immutable fileset data [#72][72].
- Resetting fileset merges initial fileset over user source files [#330][330].
- Throw exception when fileset `mv` source doesn't exist [#325][325].
- Prevent duplicate tagging of commits in `push` task [#328][328].
- Bind `*compile-path*` in nREPL server environment [#294][294].
- Updated `tools.nrepl` version to support evaluating forms with reader
  conditionals in the repl [#343][343].
- Default jar exclusions no longer exclude `pom.{xml,properties}` [#278][278].
- Jars built without the `--file` option that contain multiple `pom.xml`
  files are now named _project.jar_ instead of named for coordinates derived
  from an arbitrarily selected pom [#278][278].
- Installing or pushing a jar without the `--pom` option that contains more
  than one `pom.xml` now results in an exception instead of installing to
  coordinates derived from an arbitrarily selected pom [#278][278].
- `Stream Closed` exceptions when multiple pod pools are used [#270][270]

#### Deprecated

- The `push` task option `--gpg-keyring`.
- Implicit writing of artifacts to target directory.

[72]:  https://github.com/boot-clj/boot/issues/72
[94]:  https://github.com/boot-clj/boot/issues/94
[112]: https://github.com/boot-clj/boot/issues/112
[233]: https://github.com/boot-clj/boot/issues/233
[235]: https://github.com/boot-clj/boot/issues/235
[270]: https://github.com/boot-clj/boot/issues/270
[271]: https://github.com/boot-clj/boot/issues/271
[274]: https://github.com/boot-clj/boot/issues/274
[278]: https://github.com/boot-clj/boot/issues/278
[285]: https://github.com/boot-clj/boot/issues/285
[294]: https://github.com/boot-clj/boot/issues/294
[303]: https://github.com/boot-clj/boot/issues/303
[305]: https://github.com/boot-clj/boot/issues/305
[311]: https://github.com/boot-clj/boot/issues/311
[322]: https://github.com/boot-clj/boot/issues/322
[325]: https://github.com/boot-clj/boot/issues/325
[328]: https://github.com/boot-clj/boot/issues/328
[330]: https://github.com/boot-clj/boot/issues/330
[339]: https://github.com/boot-clj/boot/issues/339
[343]: https://github.com/boot-clj/boot/issues/343

## 2.4.2

- Fix issue where the wrong classloader was being used to
  load the boot application ([270ec3d][270ec3d])
- Make sure exceptions during boot startup are flushed
  stdout before the process exits ([4a20c74][4a20c74])

[270ec3d]: https://github.com/boot-clj/boot/commit/270ec3d85d41766c5d3a72bb4e6ef0f704630d1d
[4a20c74]: https://github.com/boot-clj/boot/commit/4a20c74b814aab82f3a04706f0116f1857149241

## 2.4.1

**Fix issues with 2.4.0 release** [61c948f][61c948f]

- Need to delete files in the fileset dirs before writing to them
  because the underlying blob files are immutable.
- Remove BOOT_CLOJURE_NAME dependency before adding jars to the
  classpath--this is a workaround for a really weird issue:

      clojure.lang.ExceptionInfo: loader constraint violation:
      loader (instance of java/net/URLClassLoader) previously initiated
      loading for a different type with name "clojure/lang/Compiler$Expr"

[61c948f]: https://github.com/boot-clj/boot/commit/61c948fdede178e3364c0238d9f368f180757659

## 2.4.0

- Self-downloading binaries--no longer need to manually download this when a
  new version is released. Also the provided binary works with all versions
  of boot since 2.0.0 inclusive (fixes [#300][300]).
- All boot env vars can now be set in properties files (fixes [#229][229]).
- Fix pod memory leaks (fixes [#314][314] & [#268][268]).
- Fix issue with Cider and boot's auto-flush PrintWriter (fixes [#298][298]).
- Avoid calling javac on a fileset with no Java source files (fixes [#309][309]).

[300]: https://github.com/boot-clj/boot/issues/300
[229]: https://github.com/boot-clj/boot/issues/229
[314]: https://github.com/boot-clj/boot/pull/314
[268]: https://github.com/boot-clj/boot/issues/268
[298]: https://github.com/boot-clj/boot/issues/298
[309]: https://github.com/boot-clj/boot/pull/309

## 2.3.0

- Stop 'boot show -u' from displaying "LATEST" dependencies as out of date.
- Add boot.core/rebuild! function and add -M,--manual option to watch task to
  manually trigger rebuild from REPL instead of when source files change.
- Fix issue where uber task was trying to explode dependencies that have only
  a pom file and no jar (fixes [#292][292]).
- Improve uber task docstring/help text.

[292]: https://github.com/boot-clj/boot/pull/292

## 2.2.0

- Add -A/--add-asset, -R/--add-resource, -S/--add-source options to
  sift task (fixes [#212][212]).
- Add new merge strategy for uber task that concatenates duplicates
  and use it for merging files in `META-INF/services/.*`.
- Support merging duplicate files in uber task, defaulting to standard
  set of mergers. (fixes [#217][217]).
- Preserve last modified time of files when unpacking JARs (fixes [#211][211]).
- Improvements to pom task (fixes [#220][220], see [d8782413][d8782413]).
- Fix file handle leaks when unpacking JAR files. (fixes issues
  relating to invalid uberjars being generated).
- Add support for .cljc files (see reader conditionals introduced in
  Clojure 1.7.0).
- Support passing arguments to javac in javac task.
- Update default Clojure version to 1.7.0.
- Fix `BOOT_LOCAL_REPO` environment variable on Windows (fixes [#243][243]).
- Make Clojure artifact name customizable via `BOOT_CLOJURE_NAME`
  environment variable, which defaults to org.clojure/clojure.

[212]: https://github.com/boot-clj/boot/issues/212
[217]: https://github.com/boot-clj/boot/issues/217
[211]: https://github.com/boot-clj/boot/issues/211
[220]: https://github.com/boot-clj/boot/issues/220
[d8782413]: https://github.com/boot-clj/boot/commit/d8782413a16bfafbc0a069bf2a77ae74c029a5ca
[243]: https://github.com/boot-clj/boot/issues/243
[pretty-config]: https://github.com/boot-clj/boot/wiki/Configuring-Boot#configuring-stack-trace-display
