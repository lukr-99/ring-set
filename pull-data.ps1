# Pull RingSet's synced CSV data from a USB-connected phone to a folder on this PC.
#
#   .\pull-data.ps1                       # -> %USERPROFILE%\Desktop\ring-data
#   .\pull-data.ps1 -Dest D:\health\ring  # custom folder
#
# How: the debug build is debuggable, so `adb run-as` can read the app's private
# files dir (/data/data/com.krejci.qringset/files) without root or storage permissions.
# First tap "Sync heart rate" in the app so there's a fresh CSV to pull.
param([string]$Dest = "$env:USERPROFILE\Desktop\ring-data")
$ErrorActionPreference = "Stop"

$pkg = "com.krejci.qringset"
$adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" } else { "adb" }

# Is a device connected?
$devices = & $adb devices | Select-String "\tdevice$"
if (-not $devices) { Write-Host "No authorized device. Connect the phone (USB debugging)." -ForegroundColor Red; exit 1 }

New-Item -ItemType Directory -Force $Dest | Out-Null

# -1 forces one filename per line (plain `ls` prints columns under exec-out)
$list = & $adb exec-out run-as $pkg sh -c "ls -1 files" 2>$null
if ($LASTEXITCODE -ne 0 -or -not $list) {
    Write-Host "Could not read app data (is the app installed as a debug build, and have you synced?)." -ForegroundColor Yellow
    exit 1
}
$csvs = $list -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '\.csv$' }
if (-not $csvs) { Write-Host "No CSV files yet. Tap 'Sync heart rate' in the app first." -ForegroundColor Yellow; exit 0 }

foreach ($f in $csvs) {
    $f = $f.Trim()
    $out = Join-Path $Dest $f
    $text = & $adb exec-out run-as $pkg cat "files/$f"
    [System.IO.File]::WriteAllText($out, ($text -join "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
    $rows = [Math]::Max(0, (Get-Content $out | Measure-Object -Line).Lines - 1)
    Write-Host ("Pulled {0}  ({1} rows)  ->  {2}" -f $f, $rows, $out) -ForegroundColor Green
}
Write-Host "Done. Data in $Dest"
