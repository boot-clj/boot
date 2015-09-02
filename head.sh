#!/usr/bin/env bash
declare -a "options=($BOOT_JVM_OPTIONS)"
self="${BASH_SOURCE[0]}"
selfdir="$(cd "$(dirname "${self}")" ; pwd)"
selfpath="$selfdir/$(basename "$self")"
exec ${BOOT_JAVA_COMMAND:-java} "${options[@]}" -Dboot.app.path="$selfpath" -jar "$0" "$@"
