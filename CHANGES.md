# Changes

## 2.5.6

- Support SSL certificates for repositories [#380][380].

## 2.5.5

???

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
