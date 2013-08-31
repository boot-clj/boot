# boot

`boot` is a minimal Clojure program 'bootloader'.  It provides a
Clojure environment with Clojure and Pomegranate on the classpath.

The idea is: instead of a pseudo-declarative project.clj in your
Clojure project, you run a function in the Clojure/Pomegranate
environment boot provides. This function then builds the project.

Individual tasks within the build process can be composed as
middlewares (like [ring](https://github.com/mmcgrana/ring) does),
for example. This is much more powerful and straightforward than
the Leiningen plugin architecture.

## Build

You'll need a recent version of [Leiningen](https://github.com/technomancy/leiningen)
if you want to build boot yourself. Pre-made binaries will be
made available someday.

    $ make boot
    $ cp ./boot /somewhere/in/your/path/

## Use

There is an example `boot.clj` in this project.  It loads a Maven
dependency, adds a directory to the classpath, and specifies a
function to evaluate when the JVM is all set up.

    $ boot 1 2 3

You can test boot without installing it by running it via `lein run`
in this directory, e.g.:

    $ lein run 1 2 3

## License

Copyright © 2013 Alan Dipert and Micha Niskin

Distributed under the Eclipse Public License, the same as Clojure.
