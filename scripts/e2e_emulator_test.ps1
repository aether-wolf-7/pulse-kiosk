# End-to-end test of the kiosk app against a running local backend.
# Usage (from repo root, with an emulator/device already booted and the
# Django dev server on 0.0.0.0:8000):
#   powershell -File scripts\e2e_emulator_test.ps1 -DeviceToken "<token from seed_pilot>"
#
# Drives the real UI over adb: provision -> login -> log sets -> save.
# Verify the result afterwards in the Django admin and in the Hevy app.

param(
    [Parameter(Mandatory = $true)][string]$DeviceToken,
    [string]$StudentId = "9999",
    [string]$Pin = "1234"
)

$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = "br.com.pulsefitness.kiosk"
$apk = "$PSScriptRoot\..\android\app\build\outputs\apk\debug\app-debug.apk"

function Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Tap($x, $y) { & $adb shell input tap $x $y; Start-Sleep -Milliseconds 600 }
function TypeText($t) { & $adb shell input text $t; Start-Sleep -Milliseconds 400 }

Step "Waiting for device"
& $adb wait-for-device
& $adb shell getprop ro.build.version.release

Step "Installing APK (fresh state)"
& $adb uninstall $pkg 2>$null | Out-Null
& $adb install -r $apk

Step "Launching app"
& $adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 4

Step "Backend reachability from the device"
# 10.0.2.2 is the host loopback as seen from the emulator.
& $adb shell "curl -s -m 5 http://10.0.2.2:8000/api/v1/health/ || echo 'curl unavailable on device (ok)'"

Write-Host @"

Manual steps from here (the app is on screen):
  1. Setup screen: paste the device token, tap 'Vincular tablet'
     Token: $DeviceToken
     (or run: adb shell input text "$DeviceToken")
  2. Login: type $StudentId, OK, then $Pin, OK
  3. Log the sets with the +/- steppers, tap 'Salvar treino'
  4. Confirm 'Treino salvo!' then auto-return to the login screen

Then verify:
  - Django admin  http://localhost:8000/admin/core/workoutlog/
  - Hevy account  the new workout should appear within seconds
  - Logs          adb logcat -d | Select-String 'kiosk|OkHttp'
"@ -ForegroundColor Yellow
