#!/bin/sh
# Usage: tools/sig.sh <fq.ClassName> [name-filter]
# Prints Mojmap method signatures (name + JVM descriptor) from the Forge merged jar.
JAR=$(ls build/moddev/artifacts/*-merged.jar 2>/dev/null | head -1)
javap -p -s -cp "$JAR" "$1" 2>/dev/null | awk -v f="${2:-}" '
  /^ / && !/descriptor:/ { sig=$0; next }
  /descriptor:/ { d=$2; if (f=="" || sig ~ f) printf "%s   %s\n", sig, d }'
