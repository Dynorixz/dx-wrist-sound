param([switch]$MetadataOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($MetadataOnly) {
    $artifacts = @{
        agp='com/android/tools/build/gradle'; bom='androidx/compose/compose-bom'
        wear='androidx/wear/compose/compose-material3'; lifecycle='androidx/lifecycle/lifecycle-runtime-compose'
        activity='androidx/activity/activity-compose'; core='androidx/core/core-ktx'
        datastore='androidx/datastore/datastore-preferences'; wearable='com/google/android/gms/play-services-wearable'
    }
    foreach ($entry in $artifacts.GetEnumerator()) {
        [xml]$meta = (Invoke-WebRequest "https://dl.google.com/dl/android/maven2/$($entry.Value)/maven-metadata.xml").Content
        $stable = @($meta.metadata.versioning.versions.version | Where-Object { $_ -match '^\d+(\.\d+)+$' })
        Write-Output "$($entry.Key): $($stable[-1])"
    }
    $pomResponse = (Invoke-WebRequest 'https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.4.1/gradle-9.4.1.pom').Content
    if ($pomResponse -is [byte[]]) { $pomResponse = [Text.Encoding]::UTF8.GetString($pomResponse) }
    [xml]$pom = $pomResponse
    $pom.project.dependencies.dependency | Where-Object groupId -EQ 'org.jetbrains.kotlin' | ForEach-Object { Write-Output "$($_.artifactId): $($_.version)" }
    return
}
# Keep native tooling in an ASCII path; original source stays in the workspace.
$tooling = Join-Path $env:LOCALAPPDATA 'DXWristSound-build\tooling'
New-Item -ItemType Directory -Force -Path $tooling | Out-Null
$distribution = Join-Path $tooling 'gradle-9.6.0-bin.zip'
if (!(Test-Path -LiteralPath $distribution) -and (Test-Path -LiteralPath (Join-Path $projectRoot '.tooling\gradle-9.6.0-bin.zip'))) {
    Copy-Item -LiteralPath (Join-Path $projectRoot '.tooling\gradle-9.6.0-bin.zip') -Destination $distribution
}
if (!(Test-Path -LiteralPath $distribution)) {
    Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-9.6.0-bin.zip' -OutFile $distribution
}
$checksumResponse = (Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-9.6.0-bin.zip.sha256').Content
if ($checksumResponse -is [byte[]]) { $checksumResponse = [Text.Encoding]::UTF8.GetString($checksumResponse) }
$expected = $checksumResponse.Trim()
$actual = (Get-FileHash -LiteralPath $distribution -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw 'Gradle checksum mismatch' }
if (!(Test-Path -LiteralPath (Join-Path $tooling 'gradle-9.6.0'))) {
    Expand-Archive -LiteralPath $distribution -DestinationPath $tooling
}
Write-Output "Gradle ready: $tooling\gradle-9.6.0"
. (Join-Path $PSScriptRoot 'resolve-java.ps1')
$env:JAVA_HOME = Resolve-DXJavaHome
$env:GRADLE_USER_HOME = Join-Path $tooling 'gradle-home'
$env:ANDROID_USER_HOME = Join-Path $tooling 'android-home'
$sdkRoot = Join-Path $tooling 'sdk'
New-Item -ItemType Directory -Force -Path $sdkRoot | Out-Null
$sourceSdk = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, 'D:\Editors\AndroidToolchain\SDK', 'E:\Editors\AndroidToolchain\SDK') |
    Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'platforms')) } | Select-Object -First 1
foreach ($package in @('licenses','build-tools','platform-tools','platforms')) {
    $targetPath = Join-Path $sdkRoot $package
    if (!(Test-Path -LiteralPath $targetPath)) {
        if (!$sourceSdk) { throw 'Install the Android SDK and set ANDROID_SDK_ROOT, or use Android Studio.' }
        Copy-Item -LiteralPath (Join-Path $sourceSdk $package) -Destination $targetPath -Recurse
    }
}
$managerRoots = @($sdkRoot, $sourceSdk) | Where-Object { $_ }
$sdkManager = $null
foreach ($managerRoot in $managerRoots) {
    $commandsRoot = Join-Path $managerRoot 'cmdline-tools'
    if (Test-Path -LiteralPath $commandsRoot) {
        $sdkManager = Get-ChildItem -LiteralPath $commandsRoot -Filter sdkmanager.bat -File -Recurse | Select-Object -First 1 -ExpandProperty FullName
        if ($sdkManager) { break }
    }
}
if (!$sdkManager) { throw 'Install Android SDK Command-line Tools through SDK Manager.' }
& $sdkManager "--sdk_root=$sdkRoot" 'platforms;android-37.2'
if ($LASTEXITCODE -ne 0) { throw 'SDK platform installation failed' }
$sdkProperty = $sdkRoot.Replace('\','/').Replace(':','\:')
Set-Content -LiteralPath (Join-Path $projectRoot 'local.properties') -Value "sdk.dir=$sdkProperty" -Encoding utf8
$wrapperProject = Join-Path $tooling 'wrapper-project'
New-Item -ItemType Directory -Force -Path $wrapperProject | Out-Null
Set-Content -LiteralPath (Join-Path $wrapperProject 'settings.gradle.kts') -Value 'rootProject.name = "wrapper-bootstrap"'
& "$env:JAVA_HOME\bin\java.exe" -classpath "$tooling\gradle-9.6.0\lib\gradle-gradle-cli-main-9.6.0.jar" org.gradle.launcher.GradleMain -p $wrapperProject wrapper --gradle-version 9.6.0 --distribution-type bin "--gradle-distribution-sha256-sum=$expected" --no-daemon
if ($LASTEXITCODE -ne 0) { throw 'Wrapper generation failed' }
Copy-Item -LiteralPath (Join-Path $wrapperProject 'gradlew') -Destination $projectRoot
Copy-Item -LiteralPath (Join-Path $wrapperProject 'gradlew.bat') -Destination $projectRoot
New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'gradle\wrapper') | Out-Null
Copy-Item -Path "$wrapperProject\gradle\wrapper\*" -Destination (Join-Path $projectRoot 'gradle\wrapper')
