#!/usr/bin/env bash
exec java $BOOT_JVM_OPTIONS -Dboot.app.path="$(which "$0")" -jar "$0" "$@"
