# Contributing to Boot

Why, hello! :wave: Thank you for your interest in contributing to Boot.

There are a lot of reasons you might be interested in contributing to Boot.
Because of all the possibilities, there's a chance nothing here will help you.

You should know that we have a friendly and active community that's generally
happy to answer any questions you might have that might not be served by this
document. The best places to find us are:

* [The `#boot` channel on Clojurians Slack][slack]
* [Our Discourse site][discourse].

Thank you in advance for your contribution, and for getting in touch with us if
you have any questions or problems!

## Table of Contents

* [General Contribution Guidelines](#general-contribution-guidelines)
* [Asking a Question](#asking-a-question)
* [Reporting a Bug](#reporting-a-bug)
* [Contributing a Bug Fix](#contributing-a-bug-fix)
* [Contributing a Feature](#contributing-a-feature)

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

TODO

[api-docs]: https://github.com/boot-clj/boot/tree/master/doc
[changes]: https://github.com/boot-clj/boot/blob/master/CHANGES.md
[discourse]: http://hoplon.discoursehosting.net/
[slack]: http://clojurians.net/
[wiki]: https://github.com/boot-clj/boot/wiki
[license]: https://github.com/boot-clj/boot/blob/master/LICENSE
[issues-page]: https://github.com/boot-clj/boot/issues
[semver]: http://semver.org/
[issue-template]: https://github.com/boot-clj/boot/blob/master/ISSUE_TEMPLATE.md
[new-issue]: https://github.com/boot-clj/boot/issues/new
