# Contributing to Boot

Why, hello! :wave: Thank you for your interest in contributing to Boot.

Before reading, please know that we have a friendly and active community. We're
generally happy to answer any questions you might have that aren't served by
this document. The best places to find us are:

* [The `#boot` channel on Clojurians Slack][slack]
* [The Boot section on the ClojureVerse forums][discourse]

Thank you in advance for your contribution, and for getting in touch with us if
you have any questions or problems!

## Table of Contents

* [General Contribution Guidelines](#general-contribution-guidelines)
* [Asking a Question](#asking-a-question)
* [Reporting a Bug](#reporting-a-bug)
* [Contributing a Bug Fix](#contributing-a-bug-fix)
* [Contributing a Feature](#contributing-a-feature)
* [Release Process](#release-process)

## General Contribution Guidelines

### The Boot maintenance philosophy

We are happy to review and accept contributions of any kind, from anyone, under
the provisions of [our license, the EPL][license].

Boot is relatively mature software and a piece of core infrastructure for many
organizations. We believe it generally solves the problems we had in mind when
we first built it, and hope to efficiently maintain it and to slowly improve it
for a *very* long time. As such, we are careful and deliberate about the
contributions we choose to accept.

### Contribution guidelines

From our perspective, the best contributions:

* Are GitHub "issues" filed on the project's [Issues page][issues-page] and are
  labeled, if possible.
* Clearly state the motivation for the contribution, enumerate its tradeoffs,
  and provide relevant examples.
* Refer to any related issues, contributions, or documentation that would help a
  reviewer further understand the contributor's motivation and the
  contribution's wider context.
* Include new tests and documentation, as appropriate.
* Include an addition to `CHANGES.md`, as appropriate.
* Are backwards compatible.

As a mature project that's already met its high level goals, we're most
enthusiastic about accepting bug reports, fixes, and improved documentation.

We still welcome new features and general improvements, but the chances
they'll be ultimately accepted are lower, especially if they are not backward
compatible.

### Release schedule and version scheme

Boot has no set release schedule, but we generally release every 6-8 months. We
loosely follow [semantic versioning][semver] with a `major.minor.patch` semantic
to our version numbers. The current `major` version is `2`, and will be for the
foreseeable future.

## Asking a Question

Currently, the best place to ask a question and receive feedback quickly is in
the [`#boot` channel of Clojurians Slack][slack].

An alternative is to [create a new issue][new-issue] and label the issue with
`question`.

## Reporting a Bug

To report a Boot bug, [create a new issue][new-issue] and fill in the provided
report template.

## Contributing a Bug Fix

A bug fix contribution should contain all of the information that
a [bug report](#reporting-a-bug) would contain. In addition, it should follow
the [General Contribution Guidelines](#general-contribution-guidelines).

## Contributing a Feature

Features are things like new tasks, new arguments for existing "builtin" tasks, or
anything else that changes or augments the behavior of Boot.

### New arguments to builtin tasks

We accept these pretty regularly. They're backward-compatible from a naming
perspective because we the Boot maintainers "own" the namespace of argument
names. That is, builtin task arguments are not user-extensible, and so can't
conflict with users' `build.boot` files.

However, the number of available argument names for a task is finite, because
arguments are limited to the alphabetic short option character, and there are only 25
available: 26 - 1 for `h`.

The best new task arguments:

* Don't change the behavior of any existing argument or combination of
  arguments. That is, if an argument `-c` is made available, the meaning of `-a
  -b` should stay the same. `-a -b -c` can do something different.
* Are clearly documented.

### New tasks

We're least likely to accept these, because from a naming perspective, they can
interfere with names a user creates in `boot.user` via `build.boot`. All of the
builtin tasks are referred by default into the user's `build.boot`, and each
time we make one, we effectively take away a potentially good name from a user.

Of course, the user can `ns-unmap` names they don't want to conflict with, but
this requires a `build.boot` or `~/.boot/profile` code change on their part. We
try very hard to avoid requiring Boot users to change code because of incidental
changes.

#### Your own task library

If you have an idea for a task that you'd like to distribute because you think
it would be useful to others, you should first consider making your own library
that contains that task. It's best to do this when:

* The task doesn't *need* to run inside Boot: it doesn't influence other builtin
  tasks or leverage Boot's ownership of the filesystem, classpath, and entry
  point.
* You want to distribute the task before the next Boot release.

For example, Boot has no built-in `new` task. Instead, there is a `boot/new`
library that provides one. The `new` task can be invoked like:

    boot -d boot/new new -t app -n my-app

This works because `boot/new` exports
the
[`new`](https://github.com/boot-clj/boot-new/blob/19c0cf2f585cfe0a3d379af854f5e77f4834bf04/src/boot/new.clj#L4) task
in its `ns` declaration. The `-d` option to Boot looks for exported tasks when
it loads dependencies, and makes them available at the command line.

If you had idea for a better `new` task, you could create it, and push it to
Clojars yourself. It would then be accessible to Boot users like this:

    boot -d YourName/new new -t app -n my-app

This task is also available to users programmatically. They could use your task via:

```clojure
(set-env! :dependencies '[[YourName/new "1.0.0"]])
(require '[YourName.new :refer [new]]')
```

## Release Process

Currently the release process requires that you run everything with Java 7.

#### Stable Releases

1. Run the tests via `make test`
1. Ensure the `version.properties` file contains the correct version number
1. Running `make deploy` will upload all jars to Clojars
1. Boot currently uses GitHub releases to serve an initial jarfile:
    1. Create a release on Github with the version string from `version.properties`
    1. Attach the file `boot/base/target/base-$NEW_VERSION-jar-with-dependencies.jar` as `boot.jar` to the Github release.
1. Once that done it's usually a good idea to check that everything uploaded correctly:

       $ rm -rf ~/.m2/repository/boot; BOOT_VERSION=$NEW_VERSION boot -h

1. If that works fine it's time to tag the release:

       $ git tag $NEW_VERSION

1. Now edit the `version.properties` file to introduce a new release cycle. Usually you should bump the least significant version digit. An example in which you just released Boot `10.0.0`:

       $ cat version.properties
       version=10.0.0
       $ echo "version=10.0.1-SNAPSHOT" > version.properties

1. The changelog should then be updated to have all unreleased changes be part of the new release.
1. Now commit the changes you made to version properties and push everything. Don't forget to push the tag!

       $ git push
       $ git push --tags

#### SNAPSHOT Releases

1. Run the tests via `make test`
1. Ensure that the `version.properties` contains the expected SNAPSHOT version numbers as per the release process for stable versions
1. Running `make deploy` will upload all jars to Clojars
1. Boot currently uses GitHub releases to serve an initial jarfile:
    1. Create a release on Github with the version string from `version.properties`
    1. Attach the file `boot/base/target/base-$NEW_VERSION-jar-with-dependencies.jar` as `boot.jar` to the Github release.
1. Once that is done it's usually a good idea to check that everything uploaded correctly:

       $ rm -rf ~/.m2/repository/boot ~/.boot/cache/bin/; BOOT_VERSION=$NEW_VERSION boot -h

1. Since SNAPSHOT releases may change over time there is no need to tag them
1. After you've done all this it's usually a good idea to tell others in `#boot-dev` on [Slack][slack] so that everyone can help testing

[api-docs]: https://github.com/boot-clj/boot/tree/master/doc
[changes]: https://github.com/boot-clj/boot/blob/master/CHANGES.md
[discourse]: https://clojureverse.org/c/projects/boot
[slack]: http://clojurians.net/
[wiki]: https://github.com/boot-clj/boot/wiki
[license]: https://github.com/boot-clj/boot/blob/master/LICENSE
[issues-page]: https://github.com/boot-clj/boot/issues
[semver]: http://semver.org/
[issue-template]: https://github.com/boot-clj/boot/blob/master/ISSUE_TEMPLATE.md
[new-issue]: https://github.com/boot-clj/boot/issues/new
