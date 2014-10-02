# Boot / Clojure Version Howto

Boot has two separate components–the boot executable and the clojure libraries
it depends on. The executable (or app) should not require updates very often.
The app is simply a thin shim used to load the supporting libraries dynamically
from a Maven repository. This provides a smooth and easy update procedure.

Since the boot build spec is itself a Clojure program, boot must already have a
version of Clojure loaded before the script can be evaluated. The version used
is, of course, configurable, as explained below.

## Update or Set Boot and Clojure Versions

To update the boot libraries to the latest stable release do this:

```
$ boot -u
```

The `DEV` update channel will install the latest testing version of the boot
libs:

```
$ BOOT_CHANNEL=DEV boot -u
```

You can also select the version via environment variables:

```
$ BOOT_VERSION=1.2.3 boot -V
```

The version of Clojure used can be set via environment variables, like this:

```
$ BOOT_CLOJURE_VERSION=1.7.0-alpha1 boot -u
```

This globally sets the version of clojure that boot will use. Note that this can
be used in combination with the other environment variables, too.

### Pinning a Project to a Specific Version

You can fix the version of boot used to build a project by creating a file
named `boot.properties` in the project directory. If such a file exists it will
be read and boot will load the versions specified. To pin a project to the
current clobal versions of boot and Clojure:

```
$ boot -V > boot.properties
```

This method also supports setting the versions via the environment, of course,
to override the current global versions.
