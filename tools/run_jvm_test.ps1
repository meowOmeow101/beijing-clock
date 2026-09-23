# Compile and run the JVM functional verification for BeijingClock.
# Runs the real SNTP / time-offset logic of the app on the desktop JVM.
# NOTE: keep this file pure ASCII - Windows PowerShell 5.1 parses .ps1 as ANSI
# when there is no BOM, which corrupts non-ASCII literals.
$ErrorActionPreference = 'Stop'

# Derive paths at runtime: <workspace>\BeijingClock
$parent = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$root = Join-Path $parent 'BeijingClock'
if (-not (Test-Path $root)) { throw "project root not found: $root" }

$jt = Join-Path $PSScriptRoot 'jvmtest'
$out = Join-Path $jt 'out'
$javac = 'D:\Appdatas\zulu21\bin\javac.exe'
$java = 'D:\Appdatas\zulu21\bin\java.exe'

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$sources = @()
$sources += Get-ChildItem (Join-Path $jt 'stubs') -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
$sources += Get-ChildItem (Join-Path $jt '*.java') | Select-Object -ExpandProperty FullName
foreach ($c in @('SntpClient.java', 'TimeCenter.java', 'TimeFormatter.java')) {
    $sources += (Join-Path $root ('app\src\main\java\com\beijing\clock\' + $c))
}

Write-Output "=== javac: $($sources.Count) source files ==="
$javacOut = & $javac -encoding UTF-8 -d $out @sources 2>&1 | Out-String
if ($javacOut.Trim().Length -gt 0) { Write-Output $javacOut }
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Output "=== run ==="
$prev = [Console]::OutputEncoding
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
& $java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' -cp $out TimeCenterTest
$code = $LASTEXITCODE
[Console]::OutputEncoding = $prev
exit $code
