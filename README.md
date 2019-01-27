<p align="center"><img src="/.github/boot-clj.png" alt="boot-clj" width="100%"></p> 

[![Build Status](https://travis-ci.org/boot-clj/boot.svg?branch=master)](https://travis-ci.org/boot-clj/boot) [![Stories in Ready][waffle-badge]][waffle-board] [![Backers on Open Collective](https://opencollective.com/boot-clj/backers/badge.svg)](#backers)
 [![Sponsors on Open Collective](https://opencollective.com/boot-clj/sponsors/badge.svg)](#sponsors) 

[change log][changes] | [installation][4] | [getting started][start] | [documentation][wiki] | [API docs][api-docs]

Boot is a Clojure build framework and ad-hoc Clojure script evaluator. Boot
provides a runtime environment that includes all of the tools needed to build
Clojure projects from scripts written in Clojure that run in the context of
the project.

> If you have questions or need help, please [visit the Discourse site][discourse].
> You can find other developers and users in [the `#boot` channel on Clojurians Slack][slack].

### Another Build Tool?

Build processes for applications always end up being complex things. A
simple web application, for instance, may require many
integrations–asset pipelines, deployment to different environments,
the compilation of multiple artifacts with different compilers,
packaging, etc.

The more complex the build process becomes, the more flexible the build tool
needs to be. Static build specifications become less and less useful as the
project moves toward completion. Being Lispers we know what to do: Lambda is
the ultimate declarative.

Instead of building the project based on a global configuration map, boot
provides a runtime environment in which a build script written in Clojure
can be evaluated. It is this script&mdash;a Turing-complete build
specification&mdash;which builds the project.

### Features

* Write executable, self-contained scripts in Clojure and run them with or
  without a project context.
* Dynamically add dependencies from Maven repositories to the running script's
  class path.
* Managed filesystem tree provides a scoped, immutable, append-only interface.
* Fine-grained control of classloader isolation–run code in separate Clojure
  runtimes.
* Tasks are functions that return middleware which compose to form build
  pipelines.
* Tasks are not coupled via hardcoded file paths or magical keys in a global
  configuration map.
* Create new, ad-hoc tasks easily in the project, in the build script, or in
  the REPL.
* Compose build pipelines in the project, in the build script, in the REPL, or
  on the command line.
* Artifacts can never be stale–there is no need for a `clean` task.

## Install

Binaries in executable format are available. Follow the instructions for your
operating system (note: boot requires the Java Development Kit (JDK) version
1.8 or greater).

#### Unix, Linux, OSX

Package managers:

* [Homebrew][brew] &mdash; `brew install boot-clj`
* [nix](http://nixos.org/nix) &mdash; `nix-env -i boot`
* [aur](https://aur.archlinux.org) &mdash; `yaourt --noconfirm -Syy boot`
* [docker](https://www.docker.com/) &mdash; Use [`clojure`](https://hub.docker.com/_/clojure/) image with `boot` tag.
    - CircleCI also maintains image with [additional tooling](https://circleci.com/docs/2.0/circleci-images/): [`circleci/clojure`](https://hub.docker.com/r/circleci/clojure/)

Otherwise:

* Download [boot.sh][boot-sh] and save as `boot`
* Make it executable.
* Move it to somewhere in your `$PATH`.

Here is a one-liner to do the above:

```sh
$ sudo bash -c "cd /usr/local/bin && curl -fsSLo boot https://github.com/boot-clj/boot-bin/releases/download/latest/boot.sh && chmod 755 boot"
```

#### Windows

Package managers:

* [Chocolatey](https://chocolatey.org/) &mdash; `choco install boot-clj`
* [Scoop](http://scoop.sh/) &mdash; `scoop bucket add extras && scoop install boot-clj`

Otherwise, download [boot.exe][boot-exe], then:

```bat
:: Using %SystemRoot% here, but can be any folder on user's %PATH%
C:\> move boot.exe %SystemRoot%
```

> **Note:** Windows 10 is fully supported. For other versions please see
> [these outstanding issues][win-issues] for specific limitations.

## Update

The boot.sh/boot.exe wrapper is a very thin shim used to load "the real Boot"
from Maven. With the wrapper installed you can update Boot's JAR files and
keep up to date with the following command:

    boot -u

The boot.sh/boot.exe wrapper itself changes (and thus requires updating) much
less frequently, and will remain compatible with future versions of the JAR
files.

## Getting Started

> The [Modern CLJS](https://github.com/magomimmo/modern-cljs) tutorials are an
> excellent introduction to Boot and ClojureScript. Pretty much everything you
> need to know about Boot to get a project off the ground is covered there.
> Check it out!

Once boot is installed (see [Install][4] above) do this in a terminal:

    boot -h

You should see the boot manual page printed to the terminal. This information
includes command line options recognized by boot, a list of available tasks,
and other information about relevant configuration files and environment
variables.

You can also get help for a specific task, for example the `repl` task:

    boot repl -h

You should see usage info and command line options for the specified task.

### Task Help in the REPL

You can also get help in the REPL. First start a REPL session:

    boot repl

Then, to get help for the `repl` task, do:

```clojure
boot.user=> (doc repl)
```

The output will be slightly different from the command line help info. We'll see
why this is so a little later.

### Build From the Command Line

Let's build a simple project to get our feet wet. We'll create a new directory,
say `my-project`, and a source directory in there named `src` with a source
file, `hello.txt`:

    mkdir -p my-project/src
    cd my-project
    echo "hi there" > src/hello.txt

The directory should now have the following structure:

    my-project
    └── src
        └── hello.txt

Suppose we want to build a jar file now, and install it to our local Maven
repository. We'll use the `pom`, `jar`, and `install` tasks to accomplish this
from the command line:

```bash
# The -- args below are optional. We use them here to visually separate the tasks.
boot -r src -d me.raynes/conch:0.8.0 -- pom -p my-project -v 0.1.0 -- jar -M Foo=bar -- install
```

What we did here was we built a pipeline on the command line and ran it to
build our project.

* We specified the resource directory (files that will end up in the jar) via boot's `-r` option.
* We added the `conch` dependency via boot's `-d` option.

This sets up the build environment. Then we constructed a pipeline of tasks:

* The `pom` task with options to set the project ID and version,
  (by default only compiled artifacts end up in the fileset),
* The `jar` task with options to add a `Foo` key to the jar,
manifest with value `bar`,
* And finally the `install` task with no options.

Boot composes the pipeline and runs it, building your project. Your local
Maven repository will now contain `my-project-0.1.0.jar`.

### Build From the REPL

Anything done on the command line can be done in the REPL or in a build script.
Fire up a REPL in the project directory:

    boot repl

The default namespace is `boot.user`, which is the namespace given to the build
script. Building the project in the REPL is almost identical to what we did on
the command line.

First we'll set some global boot options–we'll set the source directory and add
the `conch` dependency to the build environment:

```clojure
boot.user=> (set-env!
       #_=>   :resource-paths #{"src"}
       #_=>   :dependencies '[[me.raynes/conch "0.8.0"]])
```

This was specified on the command line as the `-r` or `--resource-paths` and `-d` or
`--dependencies` arguments to boot itself. These translate to calls to `set-env!`
in the REPL or in a script. Note that the keyword always corresponds to the long
option from the command line.

Now that boot environment is set up we can build the project:

```clojure
boot.user=> (boot (pom :project 'my-project :version "0.1.0")
       #_=>       (jar :manifest {"Foo" "bar"})
       #_=>       (install))
```

Again, note that the keyword arguments correspond to long options from the
command line.

### Configure Task Options

It gets tedious to specify all of those options on the command line or in the
REPL every time you build your project. Boot provides facilities for setting
task options globally, with the ability to override them by providing options
on the command line or in the REPL later.

The `task-options!` macro does this. Continuing in the REPL:

```clojure
boot.user=> (task-options!
       #_=>   pom {:project 'my-project
       #_=>        :version "0.1.0"}
       #_=>   jar {:manifest {"Foo" "bar"}})
```

Now we can build the project without specifying these options, because the
task functions have been replaced with curried versions of themselves:

```clojure
boot.user=> (boot (pom) (jar) (install))
```

Individual options can still be set by providing arguments to the tasks such
that they override those set with `task-options!`. Let's build our project with
a different version number, for example:

```clojure
boot.user=> (boot (pom :version "0.1.1") (jar) (install))
```

Pretty simple, right? This way of setting options requires no participation by
the tasks themselves. There is no global configuration map or anything like
that. It works because tasks accept only [keyword arguments][9], so partial
application is idempotent and last setting wins.

### Write a Build Script

More sophisticated builds will require one, but even a build as simple as this
one can be made a little simpler by creating a build script containing the
options for the tasks you're using.

Create a file named `build.boot` in the project directory with the following
contents:

```clojure
(set-env!
  :resource-paths #{"src"}
  :dependencies '[[me.raynes/conch "0.8.0"]])

(task-options!
  pom {:project 'my-project
       :version "0.1.0"}
  jar {:manifest {"Foo" "bar"}})
```

Now we can build the project without specifying the options for each task on
the command line–we only need to specify the tasks to create the pipeline.

    boot pom jar install

And we can override these options on the command line as we did in the REPL:

    boot -- pom -v 0.1.1 -- jar -- install

Notice how we did not need a `(boot ...)` expression in the `build.boot` script.
Boot constructs that at runtime from the command line arguments.

You can start a REPL in the context of the boot script (compiled as the
`boot.user` namespace), and build interactively too:

```clojure
boot.user=> (boot (pom) (jar) (install))
```

When boot is run from the command line it actually generates a `boot` expression
according to the command line options provided.

### Define a Task

Custom tasks can be defined in the project or in `build.boot`. This is generally
how boot is expected to be used, in fact. Boot ships with a selection of small
tasks that can be composed uniformly, and the user assembles them into something
that makes sense for the specific project.

As an example let's make a task that performs the last example above, and name
it `build`. We'll modify `build.boot` such that it contains the following:

```clojure
(set-env!
  :resource-paths #{"src"}
  :dependencies '[[me.raynes/conch "0.8.0"]])

(task-options!
  pom {:project 'my-project
       :version "0.1.0"}
  jar {:manifest {"Foo" "bar"}})

(deftask build
  "Build my project."
  []
  (comp (pom) (jar) (install)))
```

Now we should be able to see the `build` task listed among the available tasks
in the output of `boot -h`, and we can run the task from the command line as we
would run any other task:

    boot build

Tasks are functions that return pipelines. Pipelines compose functionally to
produce new pipelines. If you've used [transducers][7] or [ring middleware][8]
this pattern should be familiar. The `pom` and `install` functions we used in
the definition of `build` are, in fact, the same functions that were called
when we used them on the command line before. Boot's command line parsing
implicitly composes them; in our task we compose them using Clojure's `comp`
function.

### Define Tasks In Project

Now let's define a task in a namespace in our project and use it from the
command line.

Create the namespace with the task:

```clojure
(ns demo.boot-build
  (:require [boot.core :as core]
            [boot.task.built-in :as task]))

(core/deftask build
  "Build my project."
  []
  (comp (task/pom) (task/jar) (task/install)))
```

and write it to `src/demo/boot_build.clj` in your project.

Modify the `build.boot` file to incorporate this new task by removing the
definition for `build`. The new `build.boot` file will look like this:

```clojure
(set-env!
  :resource-paths #{"src"}
  :dependencies '[[me.raynes/conch "0.8.0"]])

(task-options!
  pom {:project 'my-project
       :version "0.1.0"}
  jar {:manifest {"Foo" "bar"}})

(require '[demo.boot-build :refer :all])
```

You can now use the `build` task defined in the project namespace from the
command line, as before:

    boot build

...

## Hacking Boot

To build boot from source you will need:

* JDK 1.8
* GNU make
* maven 3
* bash shell, wget
* [boot.sh][boot-sh] (Unix) or [boot.exe][boot-exe] (Windows)

You may increment Boot's version by editing `version.properties`:

```properties
# <version> is the version of your build
version=<version>
```

Then, in a terminal in the project directory do:

    make deps
    make install

- Jars for all of the boot components will be built and installed in your
  local Maven repository.
- The app uberjar will be built and copied to `bin/boot.jar`.
- The app uberjar will be copied to `$HOME/.boot/cache/bin/<version>/boot.jar`.

Make your build the default by editing your `$HOME/.boot/boot.properties` file:

```properties
# <version> is the version of your build
BOOT_VERSION=<version>
```

For guidelines for contributing, see [CONTRIBUTING.md](CONTRIBUTING.md).

## Attribution

Code from other projects was incorporated into boot wherever necessary to
eliminate external dependencies of boot itself. This ensures that the project
classpath remains pristine and free from potential dependency conflicts. We've
pulled in code from the following projects (thanks, guys!)

* [technomancy/leiningen][50]
* [cemerick/pomegranate][51]
* [Raynes/conch][52]
* [tebeka/clj-digest][53]
* [cldwalker/table][54]
* [clojure/tools.cli][55]
* [bbloom/backtick][56]
* [AvisoNovate/pretty][57]
* google/hesokuri
* [barbarysoftware/watchservice][58]

The boot source is also annotated to provide attribution wherever possible.
Look for the `:boot/from` key in metadata attached to vars or namespaces.

## Contributors

This project exists thanks to all the people who contribute. [[Contribute](CONTRIBUTING.md)].
<a href="https://github.com/boot-clj/boot/graphs/contributors"><img src="https://opencollective.com/boot-clj/contributors.svg?width=890&button=false" /></a>


## Backers

Thank you to all our backers! 🙏 [[Become a backer](https://opencollective.com/boot-clj#backer)]

<a href="https://opencollective.com/boot-clj#backers" target="_blank"><img src="https://opencollective.com/boot-clj/backers.svg?width=890"></a>


## Sponsors

Support this project by becoming a sponsor. Your logo will show up here with a link to your website. [[Become a sponsor](https://opencollective.com/boot-clj#sponsor)]

<a href="https://opencollective.com/boot-clj/sponsor/0/website" target="_blank"><img src="https://opencollective.com/boot-clj/sponsor/0/avatar.svg"></a>
<a href="https://opencollective.com/boot-clj/sponsor/1/website" target="_blank"><img src="https://opencollective.com/boot-clj/sponsor/1/avatar.svg"></a>
<a href="https://opencollective.com/boot-clj/sponsor/2/website" target="_blank"><img src="https://opencollective.com/boot-clj/sponsor/2/avatar.svg"></a>
<a href="https://opencollective.com/boot-clj/sponsor/3/website" target="_blank"><img src="https://opencollective.com/boot-clj/sponsor/3/avatar.svg"></a>
<a href="https://opencollective.com/boot-clj/sponsor/4/website" target="_blank"><img src="https://opencollective.com/boot-clj/sponsor/4/avatar.svg"></a>
<a href="https://opencollective.com/boot-clj/sponsor/5/website" target="_blank"><img src="https://opencollective.com/boot-clj/sponsor/5/avatar.svg"></a>
<a href="https://opencollective.com/boot-clj/sponsor/6/website" target="_blank"><img src="https://opencollective.com/boot-clj/sponsor/6/avatar.svg"></a>
<a href="https://opencollective.com/boot-clj/sponsor/7/website" target="_blank"><img src="https://opencollective.com/boot-clj/sponsor/7/avatar.svg"></a>
<a href="https://opencollective.com/boot-clj/sponsor/8/website" target="_blank"><img src="https://opencollective.com/boot-clj/sponsor/8/avatar.svg"></a>
<a href="https://opencollective.com/boot-clj/sponsor/9/website" target="_blank"><img src="https://opencollective.com/boot-clj/sponsor/9/avatar.svg"></a>



## License

Copyright © 2013-2018 Alan Dipert and Micha Niskin

Distributed under the Eclipse Public License, the same as Clojure.

[boot-sh]: https://github.com/boot-clj/boot-bin/releases/download/latest/boot.sh
[boot-exe]: https://github.com/boot-clj/boot-bin/releases/download/latest/boot.exe
[4]: #install
[5]: https://drone.io/github.com/boot-clj/boot/status.png?camocache=1
[6]: https://drone.io/github.com/boot-clj/boot/latest
[7]: http://clojure.org/transducers
[8]: http://drtom.ch/posts/2012-12-10/An_Introduction_to_Webprogramming_in_Clojure_-_Ring_and_Middleware/#ring-middleware
[9]: https://clojurefun.wordpress.com/2012/08/13/keyword-arguments-in-clojure/comment-page-1/

[20]: doc/clojure-scripting-with-boot.md
[21]: doc/overview-of-the-boot-workflow.md
[22]: doc/boot-task-writers-guide.md
[23]: https://boot-clj.github.io/boot
[24]: doc/boot-clojure-version-howto.md
[25]: https://github.com/boot-clj/boot/wiki

[50]: https://github.com/technomancy/leiningen
[51]: https://github.com/cemerick/pomegranate
[52]: https://github.com/Raynes/conch
[53]: https://github.com/tebeka/clj-digest
[54]: https://github.com/cldwalker/table
[55]: https://github.com/clojure/tools.cli
[56]: https://github.com/brandonbloom/backtick
[57]: https://github.com/AvisoNovate/pretty
[58]: https://code.google.com/p/barbarywatchservice/

[l4j]: http://sourceforge.net/projects/launch4j/files/launch4j-3/3.8/
[waffle-badge]: https://badge.waffle.io/boot-clj/boot.svg?label=ready&title=Ready
[waffle-board]: http://waffle.io/boot-clj/boot
[discourse]: https://clojureverse.org/c/projects/boot
[irc]: http://webchat.freenode.net/?channels=bootclj
[slack]: http://clojurians.net/
[changes]: https://github.com/boot-clj/boot/blob/master/CHANGES.md
[brew]: https://github.com/homebrew/homebrew
[win-issues]: https://github.com/boot-clj/boot/issues?q=is%3Aopen+is%3Aissue+label%3Awindows+label%3Ablocked
[start]: #getting-started
[wiki]: https://github.com/boot-clj/boot/wiki
[api-docs]: https://github.com/boot-clj/boot/tree/master/doc
