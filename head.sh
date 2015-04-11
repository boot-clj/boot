#!/usr/bin/env bash
self="${BASH_SOURCE[0]}"
selfdir="$(cd "$(dirname "${self}")" ; pwd)"
selfpath="$selfdir/$(basename "$self")"
exec java $BOOT_JVM_OPTIONS -Dboot.app.path="$selfpath" -jar "$0" "$@"
