param(
    [string]$AndroidSdk = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$JavaHome = 'C:/Program Files/Microsoft/jdk-17.0.20.101-hotspot'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/..").Path
$privateRoot = Join-Path $repo 'diagnostics-private'
New-Item -ItemType Directory -Path $privateRoot -Force | Out-Null
$output = New-Item -ItemType Directory -Path (Join-Path $privateRoot ("signer-rotation-" + [guid]::NewGuid().ToString('N')))
$buildTools = Join-Path $AndroidSdk 'build-tools/36.0.0'
$aapt = Join-Path $buildTools 'aapt2.exe'
$align = Join-Path $buildTools 'zipalign.exe'
$signer = Join-Path $buildTools 'apksigner.bat'
$androidJar = Join-Path $AndroidSdk 'platforms/android-37.0/android.jar'
$keytool = Join-Path $JavaHome 'bin/keytool.exe'
$manifest = Join-Path $PSScriptRoot 'fixtures/signer-rotation/AndroidManifest.xml'
foreach ($required in @($aapt, $align, $signer, $androidJar, $keytool, $manifest)) {
    if (!(Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing prerequisite: $required" }
}
function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Fixture build failed: $Executable ($LASTEXITCODE)" }
}
$priorJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $JavaHome
$env:GUARDIAN_FIXTURE_PASSWORD = [guid]::NewGuid().ToString('N')
try {
    foreach ($name in @('a', 'b')) {
        # Disposable Android-only signing material. Never an SSH credential or production signer.
        Invoke-Checked $keytool @('-genkeypair', '-keystore', "$($output.FullName)/$name.jks",
            '-alias', 'fixture', '-storepass:env', 'GUARDIAN_FIXTURE_PASSWORD',
            '-keypass:env', 'GUARDIAN_FIXTURE_PASSWORD', '-keyalg', 'RSA', '-keysize', '2048',
            '-validity', '30', '-dname', "CN=Guardian disposable fixture $name", '-noprompt')
    }
    foreach ($version in @(1, 2)) {
        Invoke-Checked $aapt @('link', '-I', $androidJar, '--manifest', $manifest,
            '--version-code', "$version", '--version-name', "$version", '-o', "$($output.FullName)/unsigned-$version.apk")
        Invoke-Checked $align @('-f', '4', "$($output.FullName)/unsigned-$version.apk", "$($output.FullName)/aligned-$version.apk")
    }
    Invoke-Checked $signer @('rotate', '--out', "$($output.FullName)/lineage.bin",
        '--old-signer', '--ks', "$($output.FullName)/a.jks", '--ks-pass', 'env:GUARDIAN_FIXTURE_PASSWORD',
        '--new-signer', '--ks', "$($output.FullName)/b.jks", '--ks-pass', 'env:GUARDIAN_FIXTURE_PASSWORD')
    Invoke-Checked $signer @('sign', '--ks', "$($output.FullName)/a.jks", '--ks-pass', 'env:GUARDIAN_FIXTURE_PASSWORD',
        '--v4-signing-enabled', 'false', '--out', "$($output.FullName)/fixture-a.apk", "$($output.FullName)/aligned-1.apk")
    Invoke-Checked $signer @('sign', '--ks', "$($output.FullName)/a.jks", '--ks-pass', 'env:GUARDIAN_FIXTURE_PASSWORD',
        '--next-signer', '--ks', "$($output.FullName)/b.jks", '--ks-pass', 'env:GUARDIAN_FIXTURE_PASSWORD',
        '--lineage', "$($output.FullName)/lineage.bin", '--rotation-min-sdk-version', '28',
        '--v4-signing-enabled', 'false', '--out', "$($output.FullName)/fixture-b.apk", "$($output.FullName)/aligned-2.apk")
    foreach ($name in @('a', 'b')) {
        Invoke-Checked $signer @('verify', '--verbose', '--print-certs', "$($output.FullName)/fixture-$name.apk")
        Invoke-Checked $aapt @('dump', 'badging', "$($output.FullName)/fixture-$name.apk")
    }
    Write-Output "FIXTURE_OUTPUT=$($output.FullName)"
    Write-Output 'BUILD_ONLY: no device install, authority change, or network operation performed.'
} finally {
    $env:GUARDIAN_FIXTURE_PASSWORD = $null
    $env:JAVA_HOME = $priorJavaHome
}
