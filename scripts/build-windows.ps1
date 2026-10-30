param([string[]]$Tasks = @(':tsproto:test', ':app:assembleDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location -LiteralPath $projectRoot
try {
    $localJdk = Get-ChildItem -LiteralPath '.tooling/java' -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
    if (-not $env:JAVA_HOME) { throw 'Install JDK 21+ and set JAVA_HOME, or place it in .tooling/java/.' }
    if (Test-Path -LiteralPath '.tooling/android-sdk') { $env:ANDROID_HOME = Join-Path $projectRoot '.tooling/android-sdk' }
    if (-not $env:ANDROID_HOME) { throw 'Install Android SDK platform 37.0 and build-tools 37.0.0; set ANDROID_HOME.' }
    $env:ANDROID_USER_HOME = Join-Path $projectRoot '.tooling/android-user'
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.tooling/gradle-cache'
    $buildTemp = Join-Path $projectRoot '.tooling/tmp'
    New-Item -ItemType Directory -Force -Path $buildTemp | Out-Null
    $env:TEMP = $buildTemp
    $env:TMP = $buildTemp
    & ./gradlew.bat --no-daemon --console=plain @Tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
    $version = (Select-String -LiteralPath 'app/build.gradle.kts' -Pattern 'versionName = "([^"]+)"' | Select-Object -First 1).Matches.Groups[1].Value
    $artifactPrefix = 'HuiXiang'
    $requestedDebug = @($Tasks | Where-Object { $_ -match '(^|:)(assembleDebug|packageDebug|assemble)$' }).Count -gt 0
    $requestedCompact = @($Tasks | Where-Object { $_ -match '(^|:)(assembleCompact|packageCompact|assemble)$' }).Count -gt 0
    $debugApk = 'app/build/outputs/apk/debug/app-universal-debug.apk'
    if (-not (Test-Path -LiteralPath $debugApk)) {
        $debugApk = 'app/build/outputs/apk/debug/app-debug.apk'
    }
    if ($requestedDebug -and (Test-Path -LiteralPath $debugApk)) {
        New-Item -ItemType Directory -Force 'dist' | Out-Null
        Copy-Item -LiteralPath $debugApk -Destination "dist/$artifactPrefix-$version-debug.apk"
        $apkHash = Get-FileHash -LiteralPath "dist/$artifactPrefix-$version-debug.apk" -Algorithm SHA256
        "$($apkHash.Hash.ToLower())  $artifactPrefix-$version-debug.apk" | Set-Content -LiteralPath 'dist/SHA256SUMS.txt' -Encoding utf8
    }
    $compactApk = 'app/build/outputs/apk/compact/app-universal-compact.apk'
    if (-not (Test-Path -LiteralPath $compactApk)) {
        $compactApk = 'app/build/outputs/apk/compact/app-compact.apk'
    }
    if ($requestedCompact -and (Test-Path -LiteralPath $compactApk)) {
        New-Item -ItemType Directory -Force 'dist' | Out-Null
        Copy-Item -LiteralPath $compactApk -Destination "dist/$artifactPrefix-$version-preview.apk" -Force
        $rows = @()
        foreach ($abi in @('arm64-v8a', 'armeabi-v7a')) {
            $source = "app/build/outputs/apk/compact/app-$abi-compact.apk"
            if (Test-Path -LiteralPath $source) {
                $name = "$artifactPrefix-$version-preview-$abi.apk"
                Copy-Item -LiteralPath $source -Destination "dist/$name" -Force
                $rows += "$((Get-FileHash -LiteralPath "dist/$name" -Algorithm SHA256).Hash.ToLower())  $name"
            }
        }
        $rows += "$((Get-FileHash -LiteralPath "dist/$artifactPrefix-$version-preview.apk" -Algorithm SHA256).Hash.ToLower())  $artifactPrefix-$version-preview.apk"
        $rows | Set-Content -LiteralPath 'dist/SHA256SUMS.txt' -Encoding utf8
    }
} finally { Pop-Location }
