# Local compile + lint check, so CI (which costs usage) is only needed to confirm.
#
#   pwsh util\build-check.ps1                      # compile Kotlin + Java, ktlint, checkstyle
#   pwsh util\build-check.ps1 -Tasks ':app:testDebugUnitTest'
#
# Output goes to out\build-check.log (git-ignored) so it can be read afterwards.
#
# Reads/writes OUTSIDE the repo (why an agent cannot run it itself):
#   - Android Studio's bundled JDK (jbr) is only read, to run Gradle on JDK 17+
#   - ~/.gradle (Gradle distribution, dependency and build caches)
#
# The extractor is picked up automatically from ..\Tubular-Extractor-Revived (settings.gradle.kts
# includes it as a composite build), so check that folder out on the branch you want to test.

param(
    [string[]] $Tasks = @(
        ':app:compileDebugKotlin',
        ':app:compileDebugJavaWithJavac',
        ':app:runKtlint',
        ':app:runCheckstyle'
    )
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Find-Jdk {
    $candidates = @(
        $env:STUDIO_JDK,
        $env:JAVA_HOME,
        (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr')
    )
    # JetBrains Toolbox installs
    $toolbox = Join-Path $env:LOCALAPPDATA 'JetBrains\Toolbox\apps'
    if (Test-Path $toolbox) {
        $candidates += Get-ChildItem $toolbox -Directory -Filter 'AndroidStudio*' -ErrorAction SilentlyContinue |
            ForEach-Object { Get-ChildItem $_.FullName -Recurse -Directory -Filter jbr -Depth 4 -ErrorAction SilentlyContinue } |
            ForEach-Object { $_.FullName }
    }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c 'bin\java.exe'))) {
            $v = (& (Join-Path $c 'bin\java.exe') -version 2>&1 | Select-Object -First 1) -join ''
            if ($v -match '"(\d+)') {
                if ([int]$Matches[1] -ge 17) { return $c }
            }
        }
    }
    throw 'No JDK 17+ found. Set STUDIO_JDK to the "jbr" folder of Android Studio.'
}

$jdk = Find-Jdk
$env:JAVA_HOME = $jdk
Write-Host "JDK: $jdk"

New-Item -ItemType Directory -Force (Join-Path $root 'out') | Out-Null
$log = Join-Path $root 'out\build-check.log'
Write-Host "Running: gradlew $($Tasks -join ' ')  (log: $log)"

Push-Location $root
try {
    & (Join-Path $root 'gradlew.bat') @Tasks --console=plain --stacktrace '-DskipFormatKtlint' *> $log
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

Write-Host "`n--- last lines ---"
Get-Content $log -Tail 25
Write-Host "`nExit code: $code (full log: $log)"
exit $code
