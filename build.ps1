# build.ps1 -- compile everything under src\ into out\  (PowerShell / Windows)
# Usage:  .\build.ps1
# Note: PowerShell uses ';' as the classpath separator, and we pass the file list
# as an array rather than an @argfile (PowerShell writes UTF-16 and treats '@' as
# its splatting operator, which javac chokes on).

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

New-Item -ItemType Directory -Force out | Out-Null
$src = Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { $_.FullName }
javac -cp "lib/microrts.jar;lib/*" -d out $src
Write-Host "build ok -> out\" -ForegroundColor Green
