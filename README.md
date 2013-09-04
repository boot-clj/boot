# boot

Boot is a minimal Clojure program 'bootloader'.  It reads a
configuration map from a `boot.clj` file in the current directory
and starts a JVM with Clojure, dependencies, and directories
on the classpath. After the JVM is set up it runs a function
or evaluates an expression as specified in the configuration.

*This is experimental software and subject to frequent change.*

## Overview

While boot can be used for just running some Clojure function in a
JVM, it can also be the foundation for better project build tooling.
The idea is: instead of a pseudo-declarative project.clj file in
your Clojure project, multiple JVMs, plugins, etc., you simply use
boot to run a Clojure function which builds your project.

### Configuration

Boot maintains its state in a configuration atom initially
derived from the data in the `boot.clj` file. The configuration
can be modified at runtime to manipulate the application state,
i.e. add dependencies to the classpath, etc.

### Middleware

Individual tasks within the build process can be composed of
middleware (like [ring](https://github.com/mmcgrana/ring) does,
for example). Everything is implemented as Clojure functions, so
it's easy to customize the build process for individual projects
and to package these build processes for distribution.

There is a selection of generally applicable middleware included
in the [boot.task](https://github.com/tailrecursion/boot.task)
repository to do useful things like watch directories for
changed files, sync/copy files between directories, etc.

### Tasks

Boot tasks are similar to Leiningen's
[profiles](https://github.com/technomancy/leiningen/blob/master/doc/PROFILES.md).
Multiple subconfigurations may be sepcified. When invoked
from the command line the selected subconfiguration is merged
into the current configuration. Tasks can be used to call
pre-packaged middleware stacks, like a lightweight Leiningen
plugin.

### Tempfiles

Since most build processes generate files at some point, boot
includes facilities for creating and managing temporary files
and directories. This temporary filesystem facility can be used
to have "auto-cleaning" builds&mdash;builds that don't need to have
a "clean" target because they don't create stale garbage.

## Install

You'll need a recent version of [Leiningen](https://github.com/technomancy/leiningen)
and the `make` tool to build boot from source. Pre-made binaries
will be made available someday.

    $ make boot
    $ cp ./boot /somewhere/in/your/path/

## Usage

Check out the [example boot.clj](https://github.com/tailrecursion/boot/blob/master/boot.clj)
in this project.  It loads a Maven dependency, adds a directory
to the classpath, and specifies a function to evaluate when the
JVM is all set up.

    $ boot

Tasks are invoked by passing the name of the task key as the
first command line argument.

    # invoke the (built-in) :help task
    $ boot help

    # invoke the :foo task and then the :bar task
    $ boot foo bar

If the task takes arguments it can be invoked by enclosing the
task name and arguments in square brackets.

    # invoke the :foo task with arguments "bar" and "baz"
    $ boot [foo bar baz]

You can test boot without installing it by running it via `lein run`
in this directory, e.g.:

    $ lein run foo

## License

Copyright © 2013 Alan Dipert and Micha Niskin

Distributed under the Eclipse Public License, the same as Clojure.
