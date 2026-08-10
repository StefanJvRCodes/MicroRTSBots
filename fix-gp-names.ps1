<#
.SYNOPSIS
  Renames .java files so each filename matches the public type declared inside it.

.DESCRIPTION
  Fixes the "class X is public, should be declared in a file named X.java" family
  of errors caused by file bodies being written to the wrong filenames.

  Renames in TWO PHASES (everything -> *.tmp, then *.tmp -> final) so that a
  cyclic shuffle like Div.java->Add.java while Add.java still exists cannot
  collide.

  Uses `git mv` when the repo is a git working tree, so history follows the file.

.EXAMPLE
  .\fix-gp-names.ps1                 # dry run, shows the plan, changes nothing
  .\fix-gp-names.ps1 -Apply          # performs the renames
  .\fix-gp-names.ps1 -Path src -Apply
#>
[CmdletBinding()]
param(
    [string] $Path  = "src",
    [switch] $Apply
)

Set-Location $PSScriptRoot
$ErrorActionPreference = "Stop"

if (-not (Test-Path $Path)) { throw "No such path: $Path" }

# Is this a git working tree? If so prefer `git mv`.
$useGit = $false
try {
    $null = git rev-parse --is-inside-work-tree 2>$null
    if ($LASTEXITCODE -eq 0) { $useGit = $true }
} catch { $useGit = $false }

# Matches:  public class Foo / public final class Foo / public abstract class Foo
#           public interface Foo / public enum Foo / public record Foo
$typeRegex = '(?m)^\s*public\s+(?:final\s+|abstract\s+|sealed\s+|non-sealed\s+|static\s+)*(?:class|interface|enum|record)\s+(\w+)'

$files    = Get-ChildItem -Path $Path -Recurse -Filter *.java
$plan     = @()
$noPublic = @()
$ok       = 0

foreach ($f in $files) {
    $text = Get-Content $f.FullName -Raw
    $m    = [regex]::Match($text, $typeRegex)

    if (-not $m.Success) {
        $noPublic += $f            # package-private or no top-level public type: legal, leave alone
        continue
    }

    $cls = $m.Groups[1].Value
    if ($cls -eq $f.BaseName) { $ok++; continue }

    $plan += [pscustomobject]@{
        Dir     = $f.DirectoryName
        From    = $f.Name
        To      = "$cls.java"
        FullOld = $f.FullName
        FullNew = Join-Path $f.DirectoryName "$cls.java"
    }
}

Write-Host ""
Write-Host "Scanned $($files.Count) .java file(s) under '$Path'." -ForegroundColor Cyan
Write-Host "  already correct : $ok"
Write-Host "  no public type  : $($noPublic.Count)   (left untouched)"
Write-Host "  need renaming   : $($plan.Count)"
Write-Host ""

if ($noPublic.Count -gt 0) {
    Write-Host "Files with no top-level public type (fine, but listed so nothing is a surprise):" -ForegroundColor DarkGray
    $noPublic | ForEach-Object { Write-Host "    $($_.FullName.Replace($PSScriptRoot,'.'))" -ForegroundColor DarkGray }
    Write-Host ""
}

if ($plan.Count -eq 0) {
    Write-Host "Nothing to rename." -ForegroundColor Green
    return
}

$plan | Select-Object @{n='Folder';e={$_.Dir.Replace($PSScriptRoot,'.')}}, From, To | Format-Table -AutoSize

# Guard: two different files claiming the same destination means a genuine
# duplicate class, which renaming cannot fix. Stop and say so.
$dupes = $plan | Group-Object FullNew | Where-Object Count -gt 1
if ($dupes) {
    Write-Host "ABORT: multiple files declare the same public type:" -ForegroundColor Red
    foreach ($d in $dupes) {
        Write-Host "  -> $($d.Name)" -ForegroundColor Red
        $d.Group | ForEach-Object { Write-Host "       from $($_.From)" -ForegroundColor Red }
    }
    Write-Host "Resolve these by hand before rerunning." -ForegroundColor Red
    return
}

if (-not $Apply) {
    Write-Host "DRY RUN. Nothing changed. Re-run with -Apply to perform these renames." -ForegroundColor Yellow
    return
}

Write-Host "Applying (git mv: $useGit) ..." -ForegroundColor Cyan

function Move-One($from, $to) {
    if ($useGit) {
        git mv -f -- "$from" "$to"
        if ($LASTEXITCODE -ne 0) { Move-Item -LiteralPath $from -Destination $to -Force }
    } else {
        Move-Item -LiteralPath $from -Destination $to -Force
    }
}

# Phase 1: park everything under a temporary name so cycles can't collide.
foreach ($p in $plan) { Move-One $p.FullOld ($p.FullNew + ".tmp") }

# Phase 2: drop the .tmp suffix.
foreach ($p in $plan) { Move-One ($p.FullNew + ".tmp") $p.FullNew }

Write-Host ""
Write-Host "Renamed $($plan.Count) file(s)." -ForegroundColor Green
Write-Host "Next: rebuild, and check config/microrts.params still names classes that exist." -ForegroundColor Green
