$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$AdbExecutable = "$ProjectRoot/.tools/android-sdk/platform-tools/adb.exe"
if (-not (Test-Path -LiteralPath $AdbExecutable)) {
    $AdbExecutable = (Get-Command adb -ErrorAction Stop).Source
}
$ApkPath = "$ProjectRoot/app/build/outputs/apk/debug/app-debug.apk"
if (-not (Test-Path -LiteralPath $ApkPath)) { throw 'Build the app first with scripts/build.ps1.' }
$DeviceLines = & $AdbExecutable devices
if ($LASTEXITCODE -ne 0) { throw 'adb could not list devices.' }
$AuthorizedDevices = @($DeviceLines | Where-Object { $_ -match '^\S+\s+device$' })
if ($AuthorizedDevices.Count -ne 1) {
    $DeviceLines | Write-Output
    throw 'Connect exactly one phone and approve its USB debugging prompt.'
}
& $AdbExecutable install -r $ApkPath
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed.' }
Write-Output 'Installed. Open Camera Compositor on your phone.'
