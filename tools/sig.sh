#!/bin/sh
# Usage: tools/sig.sh <fq.ClassName> [name-filter]
# Prints Mojmap method signatures (name + JVM descriptor) from the NeoForge merged jar.
JAR=build/moddev/artifacts/neoforge-21.1.251-merged.jar
javap -p -s -cp "$JAR" "$1" 2>/dev/null | awk -v f="${2:-}" '
  /^ / && !/descriptor:/ { sig=$0; next }
  /descriptor:/ { d=$2; if (f=="" || sig ~ f) printf "%s   %s\n", sig, d }'
