# boot.util

Namespace containing various utility functions and macros to make
life easier in Boot scripts.

##### Settings (read-only)

 [`*colorize?*`](#colorize?) [`*verbosity*`](#verbosity) [`colorize?-system-default`](#colorize?-system-default)

##### Shell

 [`*sh-dir*`](#sh-dir) [`dosh`](#dosh) [`sh`](#sh)

##### Exit

 [`exit-error`](#exit-error) [`exit-ok`](#exit-ok)

##### Logging

 [`dbug`](#dbug) [`fail`](#fail) [`info`](#info) [`warn`](#warn) [`warn-deprecated`](#warn-deprecated)

##### Useful Macros

 [`do-while-let`](#do-while-let) [`dotoseq`](#dotoseq) [`guard`](#guard) [`while-let`](#while-let) [`with-err-str`](#with-err-str) [`with-let`](#with-let) [`with-resolve`](#with-resolve) [`with-rethrow`](#with-rethrow) [`without-exiting`](#without-exiting)

##### Printing

 [`pp*`](#pp) [`pp-str`](#pp-str) [`print-ex`](#print-ex) [`print-tree`](#print-tree)

##### Miscellaneous

 [`auto-flush`](#auto-flush) [`bind-syms`](#bind-syms) [`dep-as-map`](#dep-as-map) [`extract-ids`](#extract-ids) [`index-of`](#index-of) [`jarname`](#jarname) [`let-assert-keys`](#let-assert-keys) [`path->ns`](#path->ns) [`read-string-all`](#read-string-all) [`with-semaphore`](#with-semaphore) [`with-semaphore-noblock`](#with-semaphore-noblock)

<hr>

### [`*colorize?*`](../../2.5.3/boot/pod/src/boot/util.clj#L45)

```
Atom containing the value that determines whether ANSI colors escape codes
will be printed with boot output.
```

<hr>

### [`*sh-dir*`](../../2.5.3/boot/pod/src/boot/util.clj#L314)

```
The directory to use as CWD for shell commands.
```

<hr>

### [`*verbosity*`](../../2.5.3/boot/pod/src/boot/util.clj#L31)

```
Atom containing the verbosity level, 1 is lowest, 3 highest. Level 2
corresponds to the -v boot option, level 3 to -vv, etc.

Levels:

  1.  Print INFO level messages or higher, colorize and prune stack traces
      for maximum readability.
  2.  Print DEBUG level messages or higher, don't colorize stack traces and
      prune some trace elements for improved readablility.
  3.  Print DEBUG level messages or higher, don't colorize stack traces and
      include full traces with no pruning.
```

<hr>

### [`auto-flush`](../../2.5.3/boot/pod/src/boot/util.clj#L251)

```clojure
(auto-flush writer)
```

```
Returns a PrintWriter suitable for binding to *out* or *err*. This writer
will call .flush() on every write, ensuring that all output is flushed before
boot exits, even if output was written from a background thread.
```

<hr>

### [`bind-syms`](../../2.5.3/boot/pod/src/boot/util.clj#L293)

```clojure
(bind-syms form)
```

```
Returns the names bound in the given destructuring form.
```

<hr>

### [`colorize?-system-default`](../../2.5.3/boot/pod/src/boot/util.clj#L21)

```clojure
(colorize?-system-default)
```

```
Return whether we should colorize output on this system. This is
true, unless we're on Windows, where this is false. The default
console on Windows does not interprete ansi escape codes. The
default can be overriden by setting the environment variable
BOOT_COLOR=1 or BOOT_COLOR=yes to turn it on or any other value to
turn it off.
```

<hr>

### [`dbug`](../../2.5.3/boot/pod/src/boot/util.clj#L57)

```clojure
(dbug & more)
```

```
Print DEBUG level message. Arguments of the form fmt & args suitable for
passing to clojure.core/format.
```

<hr>

### [`dep-as-map`](../../2.5.3/boot/pod/src/boot/util.clj#L274)

```clojure
(dep-as-map [project version & kvs])
```

```
Returns the given dependency vector as a map with :project and :version
keys plus any modifiers (eg. :scope, :exclusions, etc).
```

<hr>

### [`do-while-let`](../../2.5.3/boot/pod/src/boot/util.clj#L126)

```clojure
(do-while-let [binding test] & body)
```

```
Like while-let, except that the body is executed at least once.
```

<hr>

### [`dosh`](../../2.5.3/boot/pod/src/boot/util.clj#L330)

```clojure
(dosh & args)
```

```
Evaluates args as a shell command, blocking on completion and throwing an
exception on non-zero exit status. Output from the shell is streamed to
stdout and stderr as it is produced.
```

<hr>

### [`dotoseq`](../../2.5.3/boot/pod/src/boot/util.clj#L133)

```clojure
(dotoseq obj seq-exprs & body)
```

```
A cross between doto and doseq. For example:

    (-> (System/-err)
        (dotoseq [i (range 0 100)]
          (.printf "i = %d\n" i))
        (.checkError))
```

<hr>

### [`exit-error`](../../2.5.3/boot/pod/src/boot/util.clj#L173)

```clojure
(exit-error & body)
```

```
Binds *out* to *err*, evaluates the body, and exits with non-zero status.

Note: This macro does not call System.exit(), because this instance of boot
may be nested in another boot instance. Instead a special method on boot.App
is called which handles the exit behavior (calling shutdown hooks etc.).
```

<hr>

### [`exit-ok`](../../2.5.3/boot/pod/src/boot/util.clj#L184)

```clojure
(exit-ok & body)
```

```
Evaluates the body, and exits with non-zero status.

Note: This macro does not call System.exit(), because this instance of boot
may be nested in another boot instance. Instead a special method on boot.App
is called which handles the exit behavior (calling shutdown hooks etc.).
```

<hr>

### [`extract-ids`](../../2.5.3/boot/pod/src/boot/util.clj#L267)

```clojure
(extract-ids sym)
```

```
Extracts the group-id and artifact-id from sym, using the convention that
non-namespaced symbols have group-id the same as artifact-id.
```

<hr>

### [`fail`](../../2.5.3/boot/pod/src/boot/util.clj#L75)

```clojure
(fail & more)
```

```
Print ERROR level message. Arguments of the form fmt & args suitable for
passing to clojure.core/format.
```

<hr>

### [`guard`](../../2.5.3/boot/pod/src/boot/util.clj#L160)

```clojure
(guard expr & [default])
```

```
Evaluates expr within a try/catch and returns default (or nil if default is
not given) if an exception is thrown, otherwise returns the result.
```

<hr>

### [`index-of`](../../2.5.3/boot/pod/src/boot/util.clj#L288)

```clojure
(index-of v val)
```

```
Find the index of val in the sequential collection v, or nil if not found.
```

<hr>

### [`info`](../../2.5.3/boot/pod/src/boot/util.clj#L63)

```clojure
(info & more)
```

```
Print INFO level message. Arguments of the form fmt & args suitable for
passing to clojure.core/format.
```

<hr>

### [`jarname`](../../2.5.3/boot/pod/src/boot/util.clj#L282)

```clojure
(jarname project version)
```

```
Generates a friendly name for the jar file associated with the given project
symbol and version.
```

<hr>

### [`let-assert-keys`](../../2.5.3/boot/pod/src/boot/util.clj#L150)

```clojure
(let-assert-keys binding & body)
```

```
Let expression that throws an exception when any of the expected bindings
is missing.
```

<hr>

### [`path->ns`](../../2.5.3/boot/pod/src/boot/util.clj#L245)

```clojure
(path->ns path)
```

```
Returns the namespace symbol corresponding to the source file path.
```

<hr>

### [`pp*`](../../2.5.3/boot/pod/src/boot/util.clj#L299)

```clojure
(pp* expr)
```

```
Pretty-print expr using the code dispatch.
```

<hr>

### [`pp-str`](../../2.5.3/boot/pod/src/boot/util.clj#L304)

```clojure
(pp-str expr)
```

```
Pretty-print expr to a string using the code dispatch.
```

<hr>

### [`print-ex`](../../2.5.3/boot/pod/src/boot/util.clj#L208)

```clojure
(print-ex ex)
```

```
Print exception to *err* as appropriate for the current *verbosity* level.
```

<hr>

### [`print-tree`](../../2.5.3/boot/pod/src/boot/util.clj#L218)

```clojure
(print-tree tree & [prefixes])
```

```
Pretty prints tree, with the optional prefixes prepended to each line. The
output is similar to the tree(1) unix program.

A tree consists of a graph of nodes of the format [<name> <nodes>], where
<name> is a string and <nodes> is a set of nodes (the children of this node).

Example:

    (util/print-tree [["foo" #{["bar" #{["baz"]}]}]] ["--" "XX"])

prints:

    --XX└── foo
    --XX    └── bar
    --XX        └── baz
```

<hr>

### [`read-string-all`](../../2.5.3/boot/pod/src/boot/util.clj#L309)

```clojure
(read-string-all s)
```

```
Reads all forms from the string s, by wrapping in parens before reading.
```

<hr>

### [`sh`](../../2.5.3/boot/pod/src/boot/util.clj#L318)

```clojure
(sh & args)
```

```
Evaluate args as a shell command, asynchronously, and return a thunk which
may be called to block on the exit status. Output from the shell is streamed
to stdout and stderr as it is produced.
```

<hr>

### [`warn`](../../2.5.3/boot/pod/src/boot/util.clj#L69)

```clojure
(warn & more)
```

```
Print WARNING level message. Arguments of the form fmt & args suitable for
passing to clojure.core/format.
```

<hr>

### [`warn-deprecated`](../../2.5.3/boot/pod/src/boot/util.clj#L81)

```clojure
(warn-deprecated & args)
```

```
Print WARNING level message. Arguments of the form fmt & args suitable for
passing to clojure.core/format. Respects the BOOT_WARN_DEPRECATED environment
variable, which if set to no suppresses these messages.
```

<hr>

### [`while-let`](../../2.5.3/boot/pod/src/boot/util.clj#L119)

```clojure
(while-let [binding test] & body)
```

```
Repeatedly executes body while test expression is true. Test expression is
bound to binding.
```

<hr>

### [`with-err-str`](../../2.5.3/boot/pod/src/boot/util.clj#L199)

```clojure
(with-err-str & body)
```

```
Evaluates exprs in a context in which *err* is bound to a fresh StringWriter.
Returns the string created by any nested printing calls.

[1]: http://stackoverflow.com/questions/17314128/get-stacktrace-as-string
```

<hr>

### [`with-let`](../../2.5.3/boot/pod/src/boot/util.clj#L113)

```clojure
(with-let [binding resource] & body)
```

```
Binds resource to binding and evaluates body. Then, returns resource. It's
a cross between doto and with-open.
```

<hr>

### [`with-resolve`](../../2.5.3/boot/pod/src/boot/util.clj#L143)

```clojure
(with-resolve bindings & body)
```

```
Given a set of binding pairs bindings, resolves the righthand sides requiring
namespaces as necessary, binds them, and evaluates the body.
```

<hr>

### [`with-rethrow`](../../2.5.3/boot/pod/src/boot/util.clj#L166)

```clojure
(with-rethrow expr message)
```

```
Evaluates expr. If an exception is thrown it is wrapped in an exception with
the given message and the original exception as the cause, and the wrapped
exception is rethrown.
```

<hr>

### [`with-semaphore`](../../2.5.3/boot/pod/src/boot/util.clj#L89)

```clojure
(with-semaphore sem & body)
```

```
Acquires a permit from the Semaphore sem, blocking if necessary, and then
evaluates the body expressions, returning the result. In all cases the permit
will be released before returning.
```

<hr>

### [`with-semaphore-noblock`](../../2.5.3/boot/pod/src/boot/util.clj#L101)

```clojure
(with-semaphore-noblock sem & body)
```

```
Attempts to acquire a permit from the Semaphore sem. If successful the body
expressions are evaluated and the result returned. In all cases the permit
will be released before returning.
```

<hr>

### [`without-exiting`](../../2.5.3/boot/pod/src/boot/util.clj#L342)

```clojure
(without-exiting & body)
```

```
Evaluates body in a context where System/exit doesn't work. Returns result
of evaluating body, or nil if code in body attempted to exit.
```

<hr>

