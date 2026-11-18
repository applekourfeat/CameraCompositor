param([string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug'))
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
if (Test-Path -LiteralPath "$ProjectRoot/.tools/jdk") {
    $JavaExecutable = Get-ChildItem -Path "$ProjectRoot/.tools/jdk/*/bin/java.exe" | Select-Object -First 1
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $JavaExecutable.FullName)
}
$env:GRADLE_USER_HOME = "$ProjectRoot/.tools/gradle-home"
Push-Location -LiteralPath $ProjectRoot
try {
    & "$ProjectRoot/gradlew.bat" @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle exited with code $LASTEXITCODE" }
} finally { Pop-Location }
