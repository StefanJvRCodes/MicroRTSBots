# evolve.ps1 -- run an ECJ evolutionary run  (PowerShell / Windows)
# Usage:  .\evolve.ps1                      (defaults to the symreg smoke test)
#         .\evolve.ps1 config/other.params
#
# Extra params can also be overridden on the command line, e.g.
#         .\evolve.ps1 config/symreg.params -p generations=10 -p seed.0=time

param([string]$ParamsFile = "config/symreg.params")

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

New-Item -ItemType Directory -Force results | Out-Null
java -cp "out;lib/*" ec.Evolve -file $ParamsFile @args
