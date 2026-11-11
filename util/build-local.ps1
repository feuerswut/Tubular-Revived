# Local debug build using Android Studio's bundled JDK (Gradle needs JDK 17+).
# Reads: Android Studio JDK ("jbr"), Android SDK (local.properties or %LOCALAPPDATA%\Android\Sdk).
# Writes: only inside the repo (app/build, out/build-local.log).
# Usage: powershell -ExecutionPolicy Bypass -File util\build-local.ps1 [gradle task, default :app:assembleDebug]
param([string]$Task = ':app:assembleDebug')

$repo = Split-Path -Parent $PSScriptRoot
$jbrCandidates = @(
    'D:\.tools\jdk',
    "$env:ProgramFiles\Android\Android Studio\jbr",
    "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
    "$env:ProgramFiles\Android\Android Studio Preview\jbr"
)
$jbr = $jbrCandidates | Where-Object { Test-Path "$_\bin\java.exe" } | Select-Object -First 1
if (-not $jbr) { Write-Error 'Android Studio JDK (jbr) not found; set JAVA_HOME to a JDK 17+ and re-run.'; exit 1 }
$env:JAVA_HOME = $jbr
$env:Path = "$jbr\bin;$env:Path"

if (-not $env:ANDROID_HOME -and (Test-Path 'D:\.tools\android-sdk')) { $env:ANDROID_HOME = 'D:\.tools\android-sdk' }
if (-not $env:ANDROID_HOME) {
    $lp = Join-Path $repo 'local.properties'
    if (-not (Test-Path $lp)) {
        $sdk = "$env:LOCALAPPDATA\Android\Sdk"
        if (Test-Path $sdk) { $env:ANDROID_HOME = $sdk }
    }
}

New-Item -ItemType Directory -Force (Join-Path $repo 'out') | Out-Null
Set-Location $repo
$toolchains = @('D:\.tools\jdk17', 'D:\.tools\jdk') | Where-Object { Test-Path $_ }
$extra = @()
if ($toolchains) { $extra += "-Porg.gradle.java.installations.paths=$($toolchains -join ',')" }
& .\gradlew.bat ($Task -split ' ') $extra --console=plain 2>&1 | Tee-Object -FilePath (Join-Path $repo 'out\build-local.log')
exit $LASTEXITCODE
