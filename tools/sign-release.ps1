param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$buildRoot = Join-Path $env:LOCALAPPDATA 'DXWristSound-build\project'
$toolingRoot = Join-Path $env:LOCALAPPDATA 'DXWristSound-build\tooling\sdk'
$privateRoot = Join-Path $env:USERPROFILE '.codex\signing\DX-Wrist-Sound'
$keyStore = Join-Path $privateRoot 'dx-wrist-release.keystore'
$passwordFile = Join-Path $privateRoot 'release-password.dpapi'
$certificateFile = Join-Path $privateRoot 'dx-wrist-release-certificate.pem'
$alias = 'dx-wrist-release'
$signer = Join-Path $toolingRoot 'build-tools\36.0.0\apksigner.bat'
$aligner = Join-Path $toolingRoot 'build-tools\36.0.0\zipalign.exe'
$aapt = Join-Path $toolingRoot 'build-tools\36.0.0\aapt2.exe'
$analyzer = Join-Path $toolingRoot 'cmdline-tools\16.0\bin\apkanalyzer.bat'
if (!$SkipBuild) { & (Join-Path $PSScriptRoot 'build-local.ps1') }

function Get-ApkCertificate([string]$Apk) {
    # Include API 24-27 in signature verification so apksigner also checks v2.
    # This does not change the APK's actual minSdk=30 or its installation requirements.
    $verification = (& $signer verify --verbose --print-certs --min-sdk-version 24 $Apk) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $Apk" }
    $fingerprint = [regex]::Match($verification, 'Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})').Groups[1].Value.ToLowerInvariant()
    if (!$fingerprint) { throw "Signer certificate was not found: $Apk" }
    [pscustomobject]@{Fingerprint=$fingerprint;Report=$verification}
}
function Assert-ReleaseApk([string]$Apk, [string]$Capability, [string]$Version, [int]$VersionCode) {
    $debuggable = (& $analyzer manifest debuggable $Apk) -join ''
    if ($LASTEXITCODE -ne 0 -or $debuggable.Trim() -ne 'false') { throw "APK is debuggable or cannot be inspected: $Apk" }
    $badging = (& $aapt dump badging $Apk) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $badging -notmatch "name='dev.dx.wristsound'" -or
        $badging -notmatch "versionName='$([regex]::Escape($Version))'" -or $badging -notmatch "versionCode='$VersionCode'") {
        throw "APK identity/version mismatch: $Apk"
    }
    $resources = (& $aapt dump resources $Apk) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $resources -notmatch 'array/android_wear_capabilities' -or $resources -notmatch $Capability) {
        throw "Wear discovery resource is missing: $Apk"
    }
    & $aligner -c -P 16 4 $Apk
    if ($LASTEXITCODE -ne 0) { throw "APK alignment check failed: $Apk" }
}

$previousJava = $env:JAVA_HOME
. (Join-Path $PSScriptRoot 'resolve-java.ps1')
$previousPassword = [Environment]::GetEnvironmentVariable('DX_WRIST_SIGNING_PASSWORD', 'Process')
try {
    $env:JAVA_HOME = Resolve-DXJavaHome
    $keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    foreach ($tool in @($keytool, $signer, $aligner, $aapt, $analyzer)) {
        if (!(Test-Path -LiteralPath $tool)) { throw "Required signing tool is missing: $tool" }
    }
    if (!(Test-Path -LiteralPath $privateRoot)) {
        New-Item -ItemType Directory -Path $privateRoot -Force | Out-Null
        # Keep the private key and DPAPI credential outside the project and source ZIP.
        $rights = [System.Security.AccessControl.DirectorySecurity]::new()
        $rights.SetAccessRuleProtection($true, $false)
        $inheritance = [System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [System.Security.AccessControl.InheritanceFlags]::ObjectInherit
        $identities = @([System.Security.Principal.WindowsIdentity]::GetCurrent().User,
            [System.Security.Principal.SecurityIdentifier]::new('S-1-5-18'),
            [System.Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))
        foreach ($identity in $identities) {
            $rights.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($identity,
                [System.Security.AccessControl.FileSystemRights]::FullControl, $inheritance,
                [System.Security.AccessControl.PropagationFlags]::None, [System.Security.AccessControl.AccessControlType]::Allow))
        }
        Set-Acl -LiteralPath $privateRoot -AclObject $rights
    }
    if ((Test-Path -LiteralPath $keyStore) -and !(Test-Path -LiteralPath $passwordFile)) {
        throw 'The release key exists, but its protected password is missing. Restore the credential; do not replace the key.'
    }
    if (!(Test-Path -LiteralPath $passwordFile)) {
        $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try {
            $bytes = [byte[]]::new(32)
            $random.GetBytes($bytes)
            $generatedPassword = [BitConverter]::ToString($bytes).Replace('-', '')
            $securePassword = ConvertTo-SecureString -String $generatedPassword -AsPlainText -Force
            $generatedPassword = $null
        } finally { $random.Dispose() }
        [System.IO.File]::WriteAllText($passwordFile, (ConvertFrom-SecureString $securePassword), [System.Text.UTF8Encoding]::new($false))
    }
    $protectedPassword = [System.IO.File]::ReadAllText($passwordFile).Trim() | ConvertTo-SecureString
    $credential = [System.Management.Automation.PSCredential]::new('dx-wrist-release', $protectedPassword)
    $env:DX_WRIST_SIGNING_PASSWORD = $credential.GetNetworkCredential().Password
    if (!(Test-Path -LiteralPath $keyStore)) {
        & $keytool -genkeypair -keystore $keyStore -storetype PKCS12 -alias $alias -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 -dname 'CN=DX Wrist Sound, O=dynorixz' -storepass:env DX_WRIST_SIGNING_PASSWORD -keypass:env DX_WRIST_SIGNING_PASSWORD -noprompt
        if ($LASTEXITCODE -ne 0) { throw 'Release key generation failed' }
    }
    & $keytool -exportcert -rfc -keystore $keyStore -alias $alias -storepass:env DX_WRIST_SIGNING_PASSWORD -file $certificateFile
    if ($LASTEXITCODE -ne 0) { throw 'Public certificate export failed' }

    $phoneMetadata = Get-Content -LiteralPath (Join-Path $buildRoot 'mobile\build\outputs\apk\release\output-metadata.json') -Raw | ConvertFrom-Json
    $wearMetadata = Get-Content -LiteralPath (Join-Path $buildRoot 'wear\build\outputs\apk\release\output-metadata.json') -Raw | ConvertFrom-Json
    $version = [string]$phoneMetadata.elements[0].versionName
    $versionCode = [int]$phoneMetadata.elements[0].versionCode
    if ($version -notmatch '^\d+(\.\d+){1,3}$' -or $version -ne $wearMetadata.elements[0].versionName -or $versionCode -ne $wearMetadata.elements[0].versionCode) {
        throw 'Phone and Wear APK versions do not match'
    }
    # Java tools use the ASCII staging path; final files can be copied to the Unicode workspace.
    $stage = Join-Path $env:LOCALAPPDATA "DXWristSound-build\release\$version"
    New-Item -ItemType Directory -Path $stage -Force | Out-Null
    $rows = @()
    foreach ($module in @('mobile', 'wear')) {
        $label = if ($module -eq 'mobile') { 'Phone' } else { 'Wear' }
        $capability = if ($module -eq 'mobile') { 'dx_wrist_phone' } else { 'dx_wrist_watch' }
        $inputApk = Join-Path $buildRoot "$module\build\outputs\apk\release\$module-release.apk"
        $fileName = "DX-Wrist-Sound-$label-$version-release.apk"
        $outputApk = Join-Path $stage $fileName
        Assert-ReleaseApk $inputApk $capability $version $versionCode
        $legacyCertificate = Get-ApkCertificate $inputApk
        & $signer sign --ks $keyStore --ks-key-alias $alias --ks-pass env:DX_WRIST_SIGNING_PASSWORD --key-pass env:DX_WRIST_SIGNING_PASSWORD --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out $outputApk $inputApk
        if ($LASTEXITCODE -ne 0) { throw "Signing failed: $module" }
        Assert-ReleaseApk $outputApk $capability $version $versionCode
        $releaseCertificate = Get-ApkCertificate $outputApk
        if ($releaseCertificate.Fingerprint -eq $legacyCertificate.Fingerprint -or
            $releaseCertificate.Report -match 'CN=Android Debug' -or
            $releaseCertificate.Report -notmatch 'Verified using v2 scheme.*true' -or
            $releaseCertificate.Report -notmatch 'Verified using v3 scheme.*true') {
            throw "Expected release signing certificate and v2/v3 signatures: $module"
        }
        $rows += [pscustomobject]@{Module=$module;Name=$fileName;Bytes=(Get-Item -LiteralPath $outputApk).Length;
            SHA256=(Get-FileHash -LiteralPath $outputApk).Hash;CertificateSHA256=$releaseCertificate.Fingerprint;
            LegacyCertificateSHA256=$legacyCertificate.Fingerprint;Input=$inputApk;Output=$outputApk;Verification=$releaseCertificate.Report}
    }
    if ($rows[0].CertificateSHA256 -ne $rows[1].CertificateSHA256 -or $rows[0].LegacyCertificateSHA256 -ne $rows[1].LegacyCertificateSHA256) {
        throw 'Phone and Wear signing certificates do not match'
    }
    $outputRoot = Split-Path -Parent $projectRoot
    $releaseRoot = Join-Path $outputRoot "APK-$version-Release"
    $compatibleRoot = Join-Path $outputRoot "APK-$version-Compatible"
    New-Item -ItemType Directory -Path $releaseRoot, $compatibleRoot -Force | Out-Null
    foreach ($row in $rows) {
        Copy-Item -LiteralPath $row.Output -Destination (Join-Path $releaseRoot $row.Name)
        $compatibleName = $row.Name.Replace('-release.apk', '-compatible.apk')
        Copy-Item -LiteralPath $row.Input -Destination (Join-Path $compatibleRoot $compatibleName)
        [System.IO.File]::WriteAllText((Join-Path $releaseRoot "signature-$($row.Module).txt"), $row.Verification, [System.Text.UTF8Encoding]::new($false))
    }
    Copy-Item -LiteralPath $certificateFile -Destination (Join-Path $releaseRoot 'RELEASE-CERTIFICATE.pem')
    $summary = [pscustomobject]@{Version=$version;VersionCode=$versionCode;KeyStore=$keyStore;ProtectedPassword=$passwordFile;
        Alias=$alias;CertificateSHA256=$rows[0].CertificateSHA256;LegacyCertificateSHA256=$rows[0].LegacyCertificateSHA256;
        Apks=@($rows | Select-Object Name,Bytes,SHA256);ReleaseFolder=$releaseRoot;CompatibleFolder=$compatibleRoot}
    $summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $releaseRoot 'release-info.json') -Encoding utf8
    $summary | ConvertTo-Json -Depth 4
} finally {
    [Environment]::SetEnvironmentVariable('DX_WRIST_SIGNING_PASSWORD', $previousPassword, 'Process')
    $env:JAVA_HOME = $previousJava
    $credential = $null
    $protectedPassword = $null
    $securePassword = $null
}
