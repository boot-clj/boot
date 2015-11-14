# Changes

## Unreleased

- Improved documentation of core functions [#303][303].
- Improved uber task performance.
- Improved sift task performance.
- Improved fileset performance.
- Improved pod-pool performance [#271][271].
- Update tools.nrepl version to support evaluating forms with reader
  conditionals in the repl [#343][343].
- Add last modified time to immutable fileset data [#72][72].
- Add target task and BOOT_EMIT_TARGET env var [#305][305].
- Add --developers and --dependencies options to pom task [#233][233].
- Resetting fileset merges initial fileset over user source files [#330][330].
- Improved cli option error messages [#285][285] & [#322][322].
- Throw exception when fileset mv source doesn't exist [#325][325].
- Prevent duplicate tagging of commits in push task [#328][328].
- Bind *compile-path* in nREPL server environment [#294][294].
- Added (ALPHA) send! function to work around issues passing large collections
  to pods via with-eval-in [#339][339].
- Added add-cached-{asset,source,resource} core functions.
- Added support for reading repository credentials from encrypted file
  (BOOT_HOME/credentials.clj.gpg) and environment variables [#311][311] & [#274][274].
  - BREAKING: gpg binary is now used for signing jars and reading encrypted
    credentials file
      - Deprecated push option --gpg-keyring
  - Push task can now be provided with --repo-map option to set the deployment
    repository. This is useful for example in case a repository needs different
    settings for downloading dependencies and deploying, like additional
    credentials.

[72]:  https://github.com/boot-clj/boot/issues/72
[233]: https://github.com/boot-clj/boot/issues/233
[271]: https://github.com/boot-clj/boot/issues/271
[274]: https://github.com/boot-clj/boot/issues/274
[285]: https://github.com/boot-clj/boot/issues/285
[294]: https://github.com/boot-clj/boot/issues/294
[303]: https://github.com/boot-clj/boot/issues/303
[305]: https://github.com/boot-clj/boot/issues/305
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
