# boot

Boot is a minimal Clojure program 'bootloader'.  It reads a
specification map from a file `boot.clj` in the current directory
and sets up a JVM with Clojure, dependencies, and directories
on the classpath and runs a function as specified in this map.

This is experimental software and subject to frequent change.

## Boot-based Build Tools

While boot is useful for just running some Clojure function in
a JVM, it can also be used as the foundation for project build
tooling. The idea is: instead of a pseudo-declarative project.clj
file in your Clojure project, multiple JVMs, plugins, etc., you
simply use boot to run a Clojure function which builds your project.

### Boot env

Boot maintains its state in the `env` atom. This atom is
initialized with a default env merged with the map provided in
the `boot.clj` file. The atom is passed as an argument to the
Clojure function specified in its `:main` key. The application
state is also tied to the atom, so adding dependencies to the
`:dependencies` key, for example, will cause those artifacts
to be fetched, installed, and added to the JVM classpath.

### Middlewares

Individual tasks within the build process can be composed as
middlewares (like [ring](https://github.com/mmcgrana/ring) does),
for example. This is much more powerful and straightforward than
the Leiningen plugin architecture. Everything is implemented as
Clojure functions, so it's easy to customize the build process
for individual projects and to package these build processes as
clojure namespaces for distribution.

There are a number of boot middlewares included in the [boot-middleware](#)
repository to do useful things like watch directories for
changed files, sync/copy files between directories, etc.

### Tasks

Boot tasks are similar to Leiningen's
[profiles](https://github.com/technomancy/leiningen/blob/master/doc/PROFILES.md).
Multiple subconfigurations may be sepcified under the `:tasks`
key. When invoked from the command line the selected subconfiguration
is merged into the current env atom.

### Tempfiles

Since most build processes generate files at some point, boot
includes facilities for creating and managing temporary files
and directories. This temporary filesystem facility can be used
to have "auto-cleaning" builds&mdash;builds that don't need to have
a "clean" target because they simply don't create stale garbage.

## Building and Installing Boot

You'll need a recent version of [Leiningen](https://github.com/technomancy/leiningen)
if you want to build boot yourself. Pre-made binaries will be
made available someday.

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
