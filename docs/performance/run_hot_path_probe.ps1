param([string]$KotlinStdlib, [switch]$SkipBuild, [string]$OutputName = 'hot_path_round2_fixed.json')
$ErrorActionPreference = 'Stop'
$probeRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17 before running this probe.' }
$probeJavac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
$probeJava = Join-Path $env:JAVA_HOME 'bin/java.exe'
if (-not $KotlinStdlib) {
    $probeGradleCache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
    $probeStdlibDir = Join-Path $probeGradleCache 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.20'
    $KotlinStdlib = (Get-ChildItem -LiteralPath $probeStdlibDir -Filter '*.jar' -Recurse | Select-Object -First 1).FullName
}
if (-not $KotlinStdlib) { throw 'Pass -KotlinStdlib with the Kotlin 2.4.20 runtime jar path.' }
$probeClasses = Join-Path $probeRoot 'build/performance-round2-classes'
New-Item -ItemType Directory -Force -Path $probeClasses | Out-Null
Push-Location $probeRoot
try {
    if (-not $SkipBuild) {
        & './gradlew.bat' ':core:fingerprint:bundleAndroidMainClassesToCompileJar' ':feature:notification:bundleLibCompileToJarDebug' '--console=plain' *> 'build/performance-round2-build.log'
        if ($LASTEXITCODE -ne 0) { throw 'Gradle failed; see build/performance-round2-build.log.' }
    }
    $probeClasspath = @(
        $KotlinStdlib,
        (Join-Path $probeRoot 'core/common/build/intermediates/compile_library_classes_jar/androidMain/bundleAndroidMainClassesToCompileJar/classes.jar'),
        (Join-Path $probeRoot 'core/fingerprint/build/intermediates/compile_library_classes_jar/androidMain/bundleAndroidMainClassesToCompileJar/classes.jar'),
        (Join-Path $probeRoot 'feature/notification/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar')
    ) -join [IO.Path]::PathSeparator
    & $probeJavac '-encoding' 'UTF-8' '-cp' $probeClasspath '-d' $probeClasses (Join-Path $PSScriptRoot 'HotPathProbe.java')
    if ($LASTEXITCODE -ne 0) { throw 'javac failed.' }
    $probeOutput = & $probeJava '-cp' ($probeClasses + [IO.Path]::PathSeparator + $probeClasspath) 'HotPathProbe'
    if ($LASTEXITCODE -ne 0) { throw 'HotPathProbe failed.' }
    $probeResult = ($probeOutput -join "`n") | ConvertFrom-Json
    $probeSources = @(
        'core/fingerprint/src/commonMain/kotlin/me/ethanxu/jevnoisegate/core/fingerprint/TextNormalizer.kt',
        'core/fingerprint/src/commonMain/kotlin/me/ethanxu/jevnoisegate/core/fingerprint/UrlNormalizer.kt',
        'feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/SmsBatchMatcher.kt',
        'feature/notification/src/main/kotlin/me/ethanxu/jevnoisegate/feature/notification/SmsNotificationMatcher.kt'
    )
    $probeHashes = @{}
    foreach ($probeSource in $probeSources) { $probeHashes[$probeSource] = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $probeRoot $probeSource)).Hash }
    $probeResult | Add-Member -NotePropertyName source_sha256 -NotePropertyValue $probeHashes
    $probeResult | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 (Join-Path $PSScriptRoot $OutputName)
    $probeResult | ConvertTo-Json -Depth 10
} finally { Pop-Location }
