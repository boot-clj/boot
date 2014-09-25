# Boot [![Build Status][5]][6]

Boot is a Clojure build framework and ad-hoc Clojure script evaluator. Boot
provides a runtime environment that includes all of the tools needed to build
Clojure projects from scripts written in Clojure that run in the context of
the project.

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

Instead of building the project based on a global configuration map,
boot provides a runtime environment in which a build script written in
Clojure can be evaluated. It is this script which builds the project–a
Turing-complete build specification.

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
* Boot itself is "evergreen" (auto-updating) so it will never be out of date.

## Install

Binaries in executable format are available. Follow the instructions for your
operating system (note: boot requires the Java Runtime Environment (JRE)
version 1.7 or greater).

#### Unix, Linux, OSX

* Download [boot.sh][2]
* Rename `boot.sh` to `boot` and make it executable:

  ```
  $ mv boot.sh boot && chmod a+x boot
  ```

* Put `boot` in a directory that's in your `$PATH`:

  ```
  $ sudo mv boot /usr/local/bin
  ```
  
#### Windows

* Download [boot.exe][3]
* Put `boot.exe` in a directory that's in your `%PATH%`:

  ```
  C:\> move boot.exe C:\Windows\System32
  ```

## Documentation

* ~~[Clojure Scripting With Boot][20]~~
* ~~[Overview of the Boot Workflow][21]~~
* ~~[The Boot Task Writer's Guide][22]~~
* ~~[Boot API Documentation][23]~~

## Getting Started

Once boot is installed (see [Download][4] above) do this in a terminal:

```
$ boot -h
```

You should see some usage info and a list of available tasks; something like
the following:

```
Boot Version:  2.0.0-r1
Documentation: http://github.com/tailrecursion/boot

Usage:   boot OPTS <task> TASK_OPTS <task> TASK_OPTS ...

OPTS:    -b --boot-script           Print generated boot script for debugging.
         -d --dependencies ID:VER   Add dependency to project (eg. -d riddley:0.1.7).
         -e --set-env KEY=VAL       Add KEY => VAL to project env map.
         -h --help                  Print basic usage and help info.
         -P --no-profile            Skip loading of profile.boot script.
         -s --src-paths PATH        Add PATH to set of source directories.
         -t --tgt-path PATH         Set the target directory to PATH.
         -V --version               Print boot version info.

Tasks:   debug                      Print the boot environment map.
         dep-tree                   Print the project's dependency graph.
         install                    Install project jar to local Maven repository.
         jar                        Build a jar file for the project.
         pom                        Write the project's pom.xml file.
         push                       Push project jar to Clojars.
         repl                       Start a REPL session for the current project.
         syncdir                    Copy/sync files between directories.
         uberjar                    Build project jar with dependencies included.
         wait                       Wait before calling the next handler.
         watch                      Call the next handler when source files change.

Do `boot <task> -h` to see usage info and TASK_OPTS for <task>.
```

You can also get help for a specific task:

```
$ boot pom -h
```

You should see usage info and command line options for the `pom` task:

```
-------------------------
boot.task.built-in/pom
([& {:keys [help project version description url license scm], :as *opts*}])
  Write the project's pom.xml file.

  Options:
    -h, --help              Print usage info for this task.
    -p, --project PROJECT   The project groupId/artifactId.
    -v, --version VERSION   The project version.
    -d, --description DESC  A description of the project.
    -u, --url URL           The URL for the project homepage.
    -l, --license KEY=VAL   Add KEY => VAL to license map (KEY one of name, url).
    -s, --scm KEY=VAL       Add KEY => VAL to scm map (KEY one of url, tag).
```

### Build a Simple Project

Let's build a simple project to get our feet wet. We'll create a new directory,
say `boot-project`, and a source directory in there named `src` with a source
file, `hello.txt`:

```
$ mkdir -p boot-project/src
$ cd boot-project
$ echo "hi there" > src/greet.txt
```

The directory should now have the following structure:

```
boot-project
└── src
    └── hello.txt
```

Suppose we want to build a jar file now, and install it to our local Maven
repository. We'll use the `pom`, `jar`, and `install` tasks to accomplish this
from the command line:

```
# The -- args below are optional. We use them here to visually separate the tasks.
$ boot -s src -- pom -p boot-project -v 0.1.0 -- jar -M Foo=bar -- install
```

What we did here was we built a pipeline on the command line and ran it to
build our project. We specified the source directory via boot's `-s` option
first. Then we added the `pom` task with options to set the project ID and
version string, the `jar` task with options to add a `Foo` key to the jar
manifest with value `bar`, and finally the `install` task with no options.

### Build From the REPL

Anything done on the command line can be done in the REPL or in a build script.
Fire up a REPL in the project directory:

```
$ boot repl
```

The default namespace is `boot.user`, which is the namespace given to the build
script. Building the project in the REPL is almost identical to what we did on
the command line.

First we'll set some global boot options–the source directories, for instance:

```clojure
boot.user=> (set-env! :src-paths #{"src"})
```

This was given on the command line as the `-s` or `--src-paths` argument to
boot itself. In general arguments to boot correspond to calls to `set-env!` in
the REPL or in a script. Note that the keyword always corresponds to the long
option from the command line.

Now that boot environment is set up we can build the project:

```clojure
boot.user=> (boot (pom :project 'boot-project :version "0.1.0")
       #_=>       (jar :manifest {:Foo "bar"})
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
       #_=>   pom [:project 'boot-project
       #_=>        :version "0.1.0"]
       #_=>   jar [:manifest {:Foo "bar"}])
```

Now we can build the project without specifying these options, because the
task functions have been "curried":

```clojure
boot.user=> (boot (pom) (jar) (install))
```

These built-in tasks actually call the tasks they depend on when they don't
find the inputs they need. The `install` task, for instance, needs a jar file
to install, so it calls the `jar` task when it can't find one. The jar task
needs the `pom.xml` and `pom.properties` files in order to create the jar, so
it calls the `pom` task when necessary, and so on.

Since these tasks now have their options set via `task-options!` we don't need
to call them all anymore. All we need to do now is call the `install` task, and
the others will be called as necessary automatically.

```clojure
boot.user=> (boot (install))
```

Individual options can still be set by providing arguments to the tasks such
that they override those set with `task-options!`. Let's build our project with
a different version number, for example:

```clojure
boot.user=> (boot (pom :version "0.1.1") (install))
```

We'll see later exactly how all of this works, but for now just notice that we
didn't need to specify the `jar` task this time, because it was called by the
`install` task. We did, however, want to specify the `pom` task so that we
could override the `:version` argument.

### Write a Build Script

More sophisticated builds will require one, but even a build as simple as this
one can be made a little simpler by creating a build script containing the
options for the tasks you're using.

Create a file named `build.boot` in the project directory with the following
contents:

```clojure
(set-env!
  :src-paths #{"src"})

(task-options!
  pom [:project 'boot-project
       :version "0.1.0"]
  jar [:manifest {:Foo "bar"}])
```

Now we can build the project without specifying the options for each task on
the command line–we only need to specify the tasks to create the pipeline.

```
$ boot install
```

And we can override these options on the command line as we did in the REPL:

```
$ boot -- pom -v 0.1.1 -- install
```

Notice how we did not need a `(boot ...)` expression in the `build.boot` script.
Boot constructs that at runtime from the command line arguments.

You can start a REPL in the context of the boot script (compiled as the
`boot.user` namespace), and build interactively too:

```clojure
boot.user=> (boot (install))
```

When boot is run from the command line it actually generates a `boot` expression
according to the command line options provided.

### Define a Task

Custom tasks can be defined in the project or in `build.boot`. As an example
let's make a task that performs the last example above, and name it `build`.
We'll modify `build.boot` such that it contains the following:

```clojure
(set-env!
  :src-paths #{"src"})

(task-options!
  pom [:project 'boot-project
       :version "0.1.0"]
  jar [:manifest {:Foo "bar"}])

(deftask build
  "Build project version 0.1.1"
  []
  (comp
    (pom :version "0.1.1")
    (install)))
```

Now we should be able to see the `build` task listed among the available tasks
in the output of `boot -h`, and we can run the task from the command line as we
would run any other task:

```
$ boot build
```
Tasks are functions that return pipelines. Pipelines compose functionally to
produce new pipelines. The `pom` and `install` functions we used in the
definition of `build` are, in fact, the same functions that were called when we
used them on the command line before. Boot's command line parsing implicitly
composes them; in our task we compose them using Clojure's `comp` function.

## Hacking Boot

To build boot from source you will need:

* Java 7+
* GNU make
* maven 3
* launch4j
* bash shell, wget

In a terminal in the project directory do:

```
$ make install
```

Jars for all of the boot components will be created and installed to your local
Maven repository. The executables `bin/boot` and `bin/boot.exe` will be created,
as well.

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
* [google/hesokuri][58]
* [barbarysoftware/watchservice][59]

The boot source is also annotated to provide attribution wherever possible.
Look for the `:boot/from` key in metadata attached to vars or namespaces.

## License

Copyright © 2013 Alan Dipert and Micha Niskin

Distributed under the Eclipse Public License, the same as Clojure.

[1]: https://raw.githubusercontent.com/tailrecursion/boot/master/img/archimedes-lever.gif
[2]: https://github.com/tailrecursion/boot/releases/download/v2-r1/boot.sh
[3]: https://github.com/tailrecursion/boot/releases/download/v2-r1/boot.exe
[4]: #download
[5]: https://drone.io/github.com/tailrecursion/boot/status.png?cache=1
[6]: https://drone.io/github.com/tailrecursion/boot/latest

[20]: doc/clojure-scripting-with-boot.md
[21]: doc/overview-of-the-boot-workflow.md
[22]: doc/boot-task-writers-guide.md
[23]: https://tailrecursion.github.io/boot

[50]: https://github.com/technomancy/leiningen
[51]: https://github.com/cemerick/pomegranate
[52]: https://github.com/Raynes/conch
[53]: https://github.com/tebeka/clj-digest
[54]: https://github.com/cldwalker/table
[55]: https://github.com/clojure/tools.cli
[56]: https://github.com/bbloom/backtick
[57]: https://github.com/AvisoNovate/pretty
[58]: https://github.com/google/hesokuri
[59]: https://code.google.com/p/barbarywatchservice/
