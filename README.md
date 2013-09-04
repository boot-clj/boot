# boot

Boot is a minimal Clojure program 'bootloader'.  It reads a
configuration map from a `boot.clj` file in the current directory
and starts a JVM with Clojure, dependencies, and directories
on the classpath. After the JVM is set up it runs a function
or evaluates an expression as specified in the configuration.

*This is experimental software and subject to frequent change.*

## Boot-based Build Tools

While boot can be used for just running some Clojure function in a
JVM, it can also be the foundation for better project build tooling.
The idea is: instead of a pseudo-declarative project.clj file in
your Clojure project, multiple JVMs, plugins, etc., you simply use
boot to run a Clojure function which builds your project.

### Boot Configuration

Boot maintains its state in a configuration atom initially
derived from the data in the `boot.clj` file. The configuration
can be modified at runtime to manipulate the application state,
i.e. add dependencies to the classpath, etc.

### Middlewares

Individual tasks within the build process can be composed as
middlewares (like [ring](https://github.com/mmcgrana/ring) does),
for example. This is much more powerful and straightforward than
the Leiningen plugin architecture. Everything is implemented as
Clojure functions, so it's easy to customize the build process
for individual projects and to package these build processes as
clojure namespaces for distribution.

There are a number of boot middlewares included in the
[boot.task](https://github.com/tailrecursion/boot.task)
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

## Building and Installing Boot

You'll need a recent version of [Leiningen](https://github.com/technomancy/leiningen)
and the `make` tool to build boot from source. Pre-made binaries
will be made available someday.

    $ make boot
    $ cp ./boot /somewhere/in/your/path/

## Using Boot

Check out the [example boot.clj](https://github.com/tailrecursion/boot/blob/master/boot.clj)
in this project.  It loads a Maven dependency, adds a directory
to the classpath, and specifies a function to evaluate when the
JVM is all set up.

    $ boot 1 2 3

Tasks are invoked by passing the name of the task key as the
first command line argument.

    # invoke the :foo task with arguments 1, 2, and 3
    $ boot foo 1 2 3

### Hacking on Boot

You can test boot without installing it by running it via `lein run`
in this directory, e.g.:

    $ lein run 1 2 3

## License

Copyright © 2013 Alan Dipert and Micha Niskin

Distributed under the Eclipse Public License, the same as Clojure.
