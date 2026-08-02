# run.ps1 -- play headless matches  (PowerShell / Windows)
# Usage:  .\run.ps1 maps/8x8/basesWorkers8x8.xml WorkerRush 10

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

java -cp "out;lib/microrts.jar;lib/*" eval.Match @args
