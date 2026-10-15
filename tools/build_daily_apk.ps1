param(
    [string]$Repository = (Split-Path -Parent $PSScriptRoot),
    [switch]$SkipBuild
)

# Local daily-use build, not a public production signing configuration.
# Retains this device's existing development signer without shipping debug tooling.
# No installation, device mutation, credential output or source APK overwrite.
$ErrorActionPreference = 'Stop'
$taskRepo = (Resolve-Path -LiteralPath $Repository).Path
$taskSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
if (-not $taskSdk) { throw 'Set ANDROID_HOME or ANDROID_SDK_ROOT.' }
if (-not $env:GUARDIAN_DEBUG_KEYSTORE) { throw 'Set GUARDIAN_DEBUG_KEYSTORE to the existing private device key.' }
$taskKey = (Resolve-Path -LiteralPath $env:GUARDIAN_DEBUG_KEYSTORE).Path
$taskSigner = Join-Path $taskSdk 'build-tools/36.0.0/apksigner.bat'
if (-not (Test-Path -LiteralPath $taskSigner -PathType Leaf)) { throw 'Android build-tools 36.0.0 apksigner is required.' }
if (-not $SkipBuild) {
    Push-Location $taskRepo
    try {
        & (Join-Path $taskRepo 'gradlew.bat') :app:assembleDebug :app:assembleRelease
        if ($LASTEXITCODE -ne 0) { throw 'Daily build compilation failed.' }
    } finally { Pop-Location }
}
$taskInput = Join-Path $taskRepo 'app/build/outputs/apk/release/app-release-unsigned.apk'
$taskDebug = Join-Path $taskRepo 'app/build/outputs/apk/debug/app-debug.apk'
$taskOutput = Join-Path $taskRepo 'app/build/outputs/apk/release/app-release-daily.apk'
foreach ($taskApk in @($taskInput, $taskDebug)) {
    if (-not (Test-Path -LiteralPath $taskApk -PathType Leaf)) { throw 'Build both debug and release artifacts first.' }
}
# Passwords below are the standard Android development-keystore defaults, not user account secrets.
& $taskSigner sign --ks $taskKey --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $taskOutput $taskInput
if ($LASTEXITCODE -ne 0) { throw 'Daily APK signing failed.' }
function Get-ApkSignerDigest([string]$Path) {
    $taskVerification = & $taskSigner verify --print-certs $Path 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $taskDigestLines = @($taskVerification | Where-Object { $_ -match '^Signer #\d+ certificate SHA-256 digest:' })
    if ($taskDigestLines.Count -ne 1 -or $taskDigestLines[0] -notmatch '^Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})$') {
        throw 'Expected one unambiguous APK signer.'
    }
    return $Matches[1].ToLowerInvariant()
}
$taskDailyDigest = Get-ApkSignerDigest $taskOutput
if ($taskDailyDigest -ne (Get-ApkSignerDigest $taskDebug)) {
    throw 'Daily signer differs from the configured device debug signer. Do not install or uninstall to bypass this.'
}
[ordered]@{
    apk = $taskOutput
    bytes = (Get-Item -LiteralPath $taskOutput).Length
    sha256 = (Get-FileHash -LiteralPath $taskOutput -Algorithm SHA256).Hash.ToLowerInvariant()
    signerSha256 = $taskDailyDigest
    build = 'non-debug, R8 minified; original local development signer (v69-v72 daily comparison uses Debug instead)'
} | ConvertTo-Json
