#!/usr/bin/env bash
# Usage: ./run.sh [map] [opponent] [games]
#   opponent: WorkerRush | LightRush | RandomBiased   (default WorkerRush)
# Example:  ./run.sh maps/8x8/basesWorkers8x8.xml WorkerRush 10
cd "$(dirname "$0")"
java -cp "out:lib/microrts.jar:lib/*" eval.Match "$@"
