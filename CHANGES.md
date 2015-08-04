# Changes

## 2.2.0

- Add -A/--add-asset, -R/--add-resource, -S/--add-source options to
  sift task (fixes #212).
- Add new merge strategy for uber task that concatenates duplicates
and use it for merging files in `META-INF/services/.*`.
- Support merging duplicate files in uber task, defaulting to standard
  set of mergers. (fixes #217).
- Preserve last modified time of files when unpacking JARs (fixes
  #211).
- Improvements to pom task (fixes #220, see #d8782413).
- Fix file handle leaks when unpacking JAR files. (fixes issues
  relating to invalid uberjars being generated).
- Add support for .cljc files (see reader conditionals introduced in
  Clojure 1.7.0).
- Support passing arguments to javac in javac task.
- Update default Clojure version to 1.7.0.
- Fix `BOOT_LOCAL_REPO` environment variable on Windows (fixes #243).
- Make Clojure artifact name customizable via `BOOT_CLOJURE_NAME`
  environment variable, which defaults to org.clojure/clojure.
