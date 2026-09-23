# 安装 Android SDK 组件（平台、构建工具、平台工具）
$ErrorActionPreference = 'Stop'
$sdk = 'D:\Android\sdk'
$env:JAVA_HOME = 'D:\Appdatas\zulu21'
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk

$sdkmanager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $sdkmanager)) {
    throw "sdkmanager 不存在: $sdkmanager"
}

# 先接受许可（全部 yes）
Write-Output "=== 接受 SDK 许可 ==="
$yes = ("y`n" * 60)
$yes | & $sdkmanager --sdk_root=$sdk --licenses 2>&1 | Select-Object -Last 5

Write-Output "=== 安装组件 ==="
& $sdkmanager --sdk_root=$sdk "platform-tools" "platforms;android-34" "build-tools;34.0.0" 2>&1 | Select-Object -Last 15

Write-Output "=== 安装结果 ==="
Get-ChildItem $sdk -Directory | Select-Object -ExpandProperty Name
Write-Output "--- platforms ---"
Get-ChildItem (Join-Path $sdk 'platforms') -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name
Write-Output "--- build-tools ---"
Get-ChildItem (Join-Path $sdk 'build-tools') -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name
