#requires -Version 7.0
param([string]$JavaHome)

$ErrorActionPreference = 'Stop'
$projectDirectory = $PSScriptRoot

function Test-CompleteJdk17([string]$Candidate) {
    if (-not $Candidate) { return $false }
    $releaseFile = Join-Path $Candidate 'release'
    return (Test-Path -LiteralPath (Join-Path $Candidate 'bin\jlink.exe')) -and
        (Test-Path -LiteralPath (Join-Path $Candidate 'bin\javac.exe')) -and
        (Test-Path -LiteralPath $releaseFile) -and
        ((Get-Content -LiteralPath $releaseFile -Raw) -match 'JAVA_VERSION="17[.\-\"]')
}

# 优先使用显式参数，其次使用环境或本次工作区下载的完整 JDK。
if (-not $PSBoundParameters.ContainsKey('JavaHome')) {
    $JavaHome = $env:JAVA_HOME
    if (-not (Test-CompleteJdk17 $JavaHome)) {
        $toolsDirectory = Join-Path $projectDirectory '.local\tools'
        $JavaHome = Get-ChildItem -LiteralPath $toolsDirectory -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
            Where-Object { Test-CompleteJdk17 $_.FullName } | Select-Object -First 1 -ExpandProperty FullName
    }
}
if (-not (Test-CompleteJdk17 $JavaHome)) {
    throw 'A complete JDK 17 is required. Run ./build.ps1 -JavaHome <JDK path>.'
}

Push-Location -LiteralPath $projectDirectory
try {
    Write-Host 'Running protocol tests, Android lint and APK build...'
    & (Join-Path $JavaHome 'bin\java.exe') -classpath 'gradle\wrapper\gradle-wrapper.jar' `
        org.gradle.wrapper.GradleWrapperMain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE." }

    $metadata = Get-Content -LiteralPath 'app\build\outputs\apk\debug\output-metadata.json' -Raw | ConvertFrom-Json
    $distributionDirectory = Join-Path $projectDirectory 'dist'
    New-Item -ItemType Directory -Force -Path $distributionDirectory | Out-Null
    $apkFile = Join-Path $distributionDirectory "Pods-$($metadata.elements[0].versionName).apk"
    Copy-Item -LiteralPath 'app\build\outputs\apk\debug\app-debug.apk' -Destination $apkFile -Force
    $checksum = (Get-FileHash -LiteralPath $apkFile -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath (Join-Path $distributionDirectory 'SHA256SUMS') `
        -Value "$checksum  $([IO.Path]::GetFileName($apkFile))" -Encoding utf8
    Write-Host "APK ready: $apkFile"
} finally {
    Pop-Location
}
