#!/usr/bin/env bash
# Watch two bots play, with the engine's GUI.
#
#   ./run-watch.sh                                          # Chimera vs WorkerRush, 8x8
#   ./run-watch.sh maps/8x8/basesWorkers8x8.xml chimera coac 80
#   ./run-watch.sh maps/16x16/basesWorkers16x16.xml chimera mayari 30 5000
#
# args: [map] [bot0] [bot1] [msPerCycle] [maxCycles]
#
# NOTE the classpath: unlike run.sh this includes lib/bots/*, which is what
# makes coac/mayari/etc. resolvable by reflection.
set -euo pipefail
cd "$(dirname "$0")"
java -cp "out:lib/microrts.jar:lib/*:lib/bots/*" eval.Watch "$@"
