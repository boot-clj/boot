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
i.e. add dependencies to the classpath, etc. An additional global
configuration file `~/.boot.clj` in the user's home directory may
contain configuration data which is to be included in all builds.

### Middleware

Individual tasks within the build process are composed of middleware
(like [ring](https://github.com/mmcgrana/ring) does, for example).
Everything is implemented as Clojure functions, so it's easy to
customize the build process for individual projects and to package
these build processes for distribution.

The task middleware is composed into an application at runtime
according to the command line options passed to boot. Individual
tasks may be passed arguments at this time, as well.

There is a selection of generally applicable middleware included
in the [boot.task](https://github.com/tailrecursion/boot.task)
repository to do useful things like watch directories for changed
files, sync/copy files between directories, etc. This is a good
place to look for examples when building custom tasks.

### Tasks

Boot tasks may also be specified as subconfigurations inside the
`boot.clj` file, and are similar to Leiningen's
[profiles](https://github.com/technomancy/leiningen/blob/master/doc/PROFILES.md).
Multiple subconfigurations may be sepcified. When invoked
from the command line the selected subconfiguration is merged
into the current configuration. Tasks can be used to call
pre-packaged middleware stacks, like a lightweight Leiningen
plugin.

### Files

**Temporary files:** Since most build processes generate files at
some point, boot includes facilities for creating and managing
temporary files and directories. This temporary filesystem facility
can be used to have "auto-cleaning" builds&mdash;builds that don't
need to have a "clean" target because they don't create stale garbage.

**Output files:** There is also facility for collecting output files
at the end of the boot process and overlaying and syncing them to
the final project output directories, eliminating the possibility of
stale output files.

## Install

To build and run boot your system must have:
* Java version 7+
* [Leiningen](https://github.com/technomancy/leiningen) version 2+
* GNU `make`

To build boot from source run the following commands in a terminal
in the boot repo directory (pre-made binaries will be made available
someday):

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

Multiple tasks can be invoked in a single build process, too.

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
