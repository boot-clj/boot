#!/usr/bin/env bash
DFL_OPTS="-client -XX:+TieredCompilation -XX:TieredStopAtLevel=1 -Xmx2g -XX:+CMSClassUnloadingEnabled"
java $DFL_OPTS $BOOT_JVM_OPTIONS -jar $0 "$@"
exit

