#!/usr/bin/env bash
# Compile everything under src/ (bots, gp, eval) against the bundled engine.
set -e
cd "$(dirname "$0")"
mkdir -p out
javac -cp "lib/microrts.jar:lib/*" -d out $(find src -name "*.java")
echo "built -> out/"
