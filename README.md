# boot

`boot` is a minimal Clojure program 'bootloader'.  It provides a
Clojure environment with Clojure and Pomegranate on the classpath.

The idea is: instead of a pseudo-declarative project.clj in your
Clojure project, you have a boot.clj - an actual Clojure program -
that comes up in the Clojure/Pomegranate environment boot provides.

Capabilities can be added to boot-based projects with middlewares.
Instead of plugins, boot specifies a 'boot map' similar to Ring's
'request map', that is threaded through middlewares implementing
build/compilation steps like AOT, ClojureScript, jar/war, etc.

The structure of the boot map (and which special keys result in IO
being performed by the IO handler) are TBD.

## Usage

There is an example `boot.clj` in this project.  It loads a Maven
dependency, requires a namespace from the dependency, and evaluates
some code.

  lein run 1 2 3

## License

Copyright © 2013 Alan Dipert and Micha Niskin

Distributed under the Eclipse Public License, the same as Clojure.
