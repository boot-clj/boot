# Changes

## 2.4.0

- Self-downloading binaries--no longer need to manually download this when a
  new version is released. Also the provided binary works with all versions
  of boot since 2.0.0 inclusive (fixes #300).
- All boot env vars can now be set in properties files (fixes #229).
- Fix pod memory leaks (fixes #314 & #268).
- Fix issue with Cider and boot's auto-flush PrintWriter (fixes #298).
- Avoid calling javac on a fileset with no Java source files (fixes #309).

## 2.3.0

- Stop 'boot show -u' from displaying "LATEST" dependencies as out of date.
- Add boot.core/rebuild! function and add -M,--manual option to watch task to
  manually trigger rebuild from REPL instead of when source files change.
- Fix issue where uber task was trying to explode dependencies that have only
  a pom file and no jar (fixes [#292](https://github.com/boot-clj/boot/pull/292)).
- Improve uber task docstring/help text.

## 2.2.0

- Add -A/--add-asset, -R/--add-resource, -S/--add-source options to
  sift task (fixes [#212](https://github.com/boot-clj/boot/issues/212)).
- Add new merge strategy for uber task that concatenates duplicates
  and use it for merging files in `META-INF/services/.*`.
- Support merging duplicate files in uber task, defaulting to standard
  set of mergers. (fixes [#217](https://github.com/boot-clj/boot/issues/217)).
- Preserve last modified time of files when unpacking JARs (fixes
  [#211](https://github.com/boot-clj/boot/issues/211)).
- Improvements to pom task (fixes [#220](https://github.com/boot-clj/boot/issues/220),
  see [d8782413](https://github.com/boot-clj/boot/commit/d8782413a16bfafbc0a069bf2a77ae74c029a5ca)).
- Fix file handle leaks when unpacking JAR files. (fixes issues
  relating to invalid uberjars being generated).
- Add support for .cljc files (see reader conditionals introduced in
  Clojure 1.7.0).
- Support passing arguments to javac in javac task.
- Update default Clojure version to 1.7.0.
- Fix `BOOT_LOCAL_REPO` environment variable on Windows
  (fixes [#243](https://github.com/boot-clj/boot/issues/243)).
- Make Clojure artifact name customizable via `BOOT_CLOJURE_NAME`
  environment variable, which defaults to org.clojure/clojure.
