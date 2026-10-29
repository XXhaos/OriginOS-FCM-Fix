param(
    [string]$Repository = (Split-Path -Parent $PSScriptRoot),
    [string]$BuildToolsVersion = '36.0.0',
    [switch]$SkipBuild
)

# Official local release signing. Source/debug/unsigned builds never require these variables.
# Never generates keys, installs an APK, connects to a phone, or publishes artifacts.
$ErrorActionPreference = 'Stop'
$taskRepo = (Resolve-Path -LiteralPath $Repository).Path
$taskSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
if (-not $taskSdk) { throw 'Set ANDROID_HOME or ANDROID_SDK_ROOT.' }
foreach ($taskVariable in @('GUARDIAN_RELEASE_KEYSTORE', 'GUARDIAN_RELEASE_KEY_ALIAS', 'GUARDIAN_RELEASE_STORE_PASSWORD', 'GUARDIAN_RELEASE_KEY_PASSWORD')) {
    if (-not [Environment]::GetEnvironmentVariable($taskVariable)) { throw "Set $taskVariable for local release signing." }
}
$taskKey = (Resolve-Path -LiteralPath $env:GUARDIAN_RELEASE_KEYSTORE).Path
$taskSigner = Join-Path $taskSdk "build-tools/$BuildToolsVersion/apksigner.bat"
$taskAnalyzer = Join-Path $taskSdk 'cmdline-tools/latest/bin/apkanalyzer.bat'
foreach ($taskTool in @($taskSigner, $taskAnalyzer)) {
    if (-not (Test-Path -LiteralPath $taskTool -PathType Leaf)) { throw "Required Android SDK tool missing: $taskTool" }
}
if (-not $SkipBuild) {
    Push-Location $taskRepo
    try {
        & (Join-Path $taskRepo 'gradlew.bat') :app:assembleRelease
        if ($LASTEXITCODE -ne 0) { throw 'Release compilation failed.' }
    } finally { Pop-Location }
}
$taskInput = Join-Path $taskRepo 'app/build/outputs/apk/release/app-release-unsigned.apk'
if (-not (Test-Path -LiteralPath $taskInput -PathType Leaf)) { throw 'Unsigned release APK missing.' }
$taskMapping = Join-Path $taskRepo 'app/build/outputs/mapping/release/mapping.txt'
if (-not (Test-Path -LiteralPath $taskMapping -PathType Leaf) -or (Get-Item -LiteralPath $taskMapping).Length -eq 0) {
    throw 'R8 release mapping missing. Build the minified release variant first.'
}
function Read-ManifestValue([string]$Field, [string]$Apk) {
    $taskValue = & $taskAnalyzer manifest $Field $Apk 2>&1
    if ($LASTEXITCODE -ne 0) { throw "APK manifest inspection failed: $Field" }
    return ($taskValue -join "`n").Trim()
}
$taskExpected = [ordered]@{ 'application-id' = 'io.github.originosfcmguardian'; 'version-code' = '99';
    'version-name' = '1.0.0'; 'min-sdk' = '30'; 'target-sdk' = '37'; 'debuggable' = 'false' }
foreach ($taskField in $taskExpected.Keys) {
    if ((Read-ManifestValue $taskField $taskInput) -cne $taskExpected[$taskField]) {
        throw "Unexpected unsigned release manifest $taskField. Rebuild the 1.0.0/code99 release." }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskArchive = [IO.Compression.ZipFile]::OpenRead($taskInput)
try {
    foreach ($taskAsset in @('assets/Apache-2.0.txt', 'assets/Shizuku-API-MIT.txt', 'assets/dependencies/runtime-dependencies.json', 'assets/dependencies/THIRD-PARTY-NOTICES.txt')) {
        if (-not $taskArchive.GetEntry($taskAsset)) { throw "Release attribution missing: $taskAsset" }
    }
} finally { $taskArchive.Dispose() }
$taskDirectory = Join-Path $taskRepo 'build/releases/v1.0.0'
New-Item -ItemType Directory -Force -Path $taskDirectory | Out-Null
$taskOutput = Join-Path $taskDirectory 'OriginOS-FCM-Guardian-1.0.0.apk'
$taskCandidate = Join-Path $taskDirectory 'OriginOS-FCM-Guardian-1.0.0.candidate.apk'
# Passwords are passed by environment reference, never interpolated into process arguments/output.
& $taskSigner sign --ks $taskKey --ks-key-alias $env:GUARDIAN_RELEASE_KEY_ALIAS `
    --ks-pass env:GUARDIAN_RELEASE_STORE_PASSWORD --key-pass env:GUARDIAN_RELEASE_KEY_PASSWORD `
    --v4-signing-enabled false --out $taskCandidate $taskInput
if ($LASTEXITCODE -ne 0) { throw 'Release APK signing failed.' }
$taskVerification = & $taskSigner verify --verbose --print-certs $taskCandidate 2>&1
if ($LASTEXITCODE -ne 0) { throw 'Release APK signature verification failed.' }
$taskDigestLines = @($taskVerification | Where-Object { $_ -match '^Signer #\d+ certificate SHA-256 digest:' })
if ($taskDigestLines.Count -ne 1 -or $taskDigestLines[0] -notmatch '^Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})$') {
    throw 'Expected exactly one release APK signer.'
}
$taskDigest = $Matches[1].ToLowerInvariant()
if ($taskDigest -ne 'd662d39e97573ed1759994033584109eb26d06b57c7cae81a7cf3d4d840dfa9d') {
    Remove-Item -LiteralPath $taskCandidate
    throw 'Release signer differs from the original Guardian certificate. Artifact removed; preserve the original key for upgrades.'
}
foreach ($taskField in $taskExpected.Keys) {
    if ((Read-ManifestValue $taskField $taskCandidate) -cne $taskExpected[$taskField]) { throw "Signed APK manifest changed: $taskField" }
}
Move-Item -LiteralPath $taskCandidate -Destination $taskOutput -Force
$taskHash = (Get-FileHash -LiteralPath $taskOutput -Algorithm SHA256).Hash.ToLowerInvariant()
$taskReport = [ordered]@{ file = [IO.Path]::GetFileName($taskOutput); versionName = '1.0.0'; versionCode = 99;
    applicationId = 'io.github.originosfcmguardian'; minSdk = 30; targetSdk = 37; debuggable = $false;
    variant = 'release'; minified = $true; bytes = (Get-Item -LiteralPath $taskOutput).Length;
    sha256 = $taskHash; signerCertificateSha256 = $taskDigest }
$taskReport | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskDirectory 'artifact-manifest.json') -Encoding utf8
"$taskHash  $([IO.Path]::GetFileName($taskOutput))" | Set-Content -LiteralPath (Join-Path $taskDirectory 'SHA256SUMS.txt') -Encoding ascii
$taskReport | ConvertTo-Json
