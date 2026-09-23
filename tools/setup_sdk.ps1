# 安装 Android SDK 组件（platform-tools、platforms、build-tools）
# Change the two paths below to match your machine, or set ANDROID_HOME / JAVA_HOME first.
$ErrorActionPreference = 'Stop'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'C:\Android\sdk' }
if (-not $env:JAVA_HOME) {
    throw "set JAVA_HOME to a JDK 17+ installation before running this script"
}
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk

$sdkmanager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $sdkmanager)) {
    throw "sdkmanager not found: $sdkmanager (unzip commandline-tools into cmdline-tools\latest first)"
}

# 接受许可：sdkmanager 从标准输入读取确认，这里用重定向而不是管道，
# 否则批处理包装层会把输入吃掉
$answers = Join-Path $env:TEMP 'android_sdk_yes.txt'
[System.IO.File]::WriteAllText($answers, ("y`r`n" * 100))
Write-Output "=== accepting licenses ==="
cmd /c "`"$sdkmanager`" --sdk_root=$sdk --licenses < `"$answers`" 2>&1" | Select-Object -Last 3

Write-Output "=== installing packages ==="
cmd /c "`"$sdkmanager`" --sdk_root=$sdk `"platform-tools`" `"platforms;android-34`" `"build-tools;34.0.0`" 2>&1" | Select-Object -Last 5

Write-Output "=== installed ==="
Get-ChildItem $sdk -Directory | Select-Object -ExpandProperty Name
Write-Output "--- platforms ---"
Get-ChildItem (Join-Path $sdk 'platforms') -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name
Write-Output "--- build-tools ---"
Get-ChildItem (Join-Path $sdk 'build-tools') -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name
