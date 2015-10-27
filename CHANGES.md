# Changes

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
