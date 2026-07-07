# Build the app and install it on a USB-connected phone (USB debugging authorized).
# Requires a JDK 17+ (JAVA_HOME or on PATH) and the Android SDK (ANDROID_HOME set,
# or a local.properties with sdk.dir=...). The Gradle wrapper fetches Gradle itself.
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

Write-Host "Building..." -ForegroundColor Cyan
& ".\gradlew.bat" assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }

$adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" } else { "adb" }
$apk = Join-Path $here "app\build\outputs\apk\debug\app-debug.apk"
Write-Host "Installing $apk ..." -ForegroundColor Cyan
& $adb install -r $apk
Write-Host "Done. Launch 'Ring Set' on the phone." -ForegroundColor Green
