param([string[]]$Tasks = @(':mobile:assembleRelease', ':wear:assembleRelease', ':shared:testDebugUnitTest', ':wear:testDebugUnitTest', 'lintRelease'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$buildRoot = Join-Path $env:LOCALAPPDATA 'DXWristSound-build\project'
$tooling = Join-Path $env:LOCALAPPDATA 'DXWristSound-build\tooling'
New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null
& robocopy.exe $projectRoot $buildRoot /E /XD .tooling .gradle .idea .git build artifacts /XF '*.iml' /NFL /NDL /NJH /NJS /NP
if ($LASTEXITCODE -gt 7) { throw 'Source copy failed' }
. (Join-Path $PSScriptRoot 'resolve-java.ps1')
$env:JAVA_HOME = Resolve-DXJavaHome
$env:GRADLE_USER_HOME = Join-Path $tooling 'gradle-home'
$env:ANDROID_USER_HOME = Join-Path $tooling 'android-home'
Push-Location $buildRoot
try {
    & "$env:JAVA_HOME\bin\java.exe" -classpath 'gradle\wrapper\gradle-wrapper.jar' org.gradle.wrapper.GradleWrapperMain @Tasks --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Build failed ($LASTEXITCODE)" }
} finally { Pop-Location }
# Play services reads the capability array by name, outside the app's reference graph.
# Check the packaged release resource after shrinking, not merely the source XML.
foreach ($module in @('mobile','wear')) {
    if (($Tasks -contains 'assembleRelease') -or ($Tasks -contains ":${module}:assembleRelease")) {
        $apkPath = Join-Path $buildRoot "$module\build\outputs\apk\release\$module-release.apk"
        $resourceDump = (& (Join-Path $tooling 'sdk\build-tools\36.0.0\aapt2.exe') dump resources $apkPath) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw "Cannot inspect release APK: $module" }
        $capability = if ($module -eq 'mobile') { 'dx_wrist_phone' } else { 'dx_wrist_watch' }
        if ($resourceDump -notmatch 'array/android_wear_capabilities' -or $resourceDump -notmatch $capability) {
            throw "Release discovery contract removed by shrinking: $module / $capability"
        }
        Write-Output "Verified packaged capability: $module / $capability"
    }
}
foreach ($module in @('mobile','wear','shared')) {
    $output = Join-Path $buildRoot "$module\build"
    if (Test-Path -LiteralPath $output) {
        & robocopy.exe $output (Join-Path $projectRoot "$module\build") /E /NFL /NDL /NJH /NJS /NP
        if ($LASTEXITCODE -gt 7) { throw 'Output copy failed' }
    }
}
Write-Output 'Build and verification completed. APKs are in mobile/build and wear/build.'
