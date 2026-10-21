param([string]$Repository = (Split-Path -Parent $PSScriptRoot))

# v18 external-only host artifact gate. Does not touch the device or private data.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$androidNamespace = 'http://schemas.android.com/apk/res/android'
$results = @()
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
if (-not $sdkRoot) { throw 'Set ANDROID_HOME or ANDROID_SDK_ROOT.' }
$apkAnalyzer = Join-Path $sdkRoot 'cmdline-tools/latest/bin/apkanalyzer.bat'
foreach ($variant in @('debug', 'release')) {
    $capitalized = (Get-Culture).TextInfo.ToTitleCase($variant)
    $manifestPath = Join-Path $Repository "app/build/intermediates/merged_manifest/$variant/process${capitalized}MainManifest/AndroidManifest.xml"
    [xml]$manifest = Get-Content -LiteralPath $manifestPath
    $namespaces = [System.Xml.XmlNamespaceManager]::new($manifest.NameTable)
    $namespaces.AddNamespace('android', $androidNamespace)
    if ($manifest.SelectNodes('/manifest/uses-permission[@android:name="android.permission.WRITE_SECURE_SETTINGS"]', $namespaces).Count -ne 0) {
        throw "$variant must not declare internal-startup WRITE_SECURE_SETTINGS."
    }
    $provider = $manifest.SelectSingleNode('/manifest/application/provider[@android:name="rikka.shizuku.ShizukuProvider"]', $namespaces)
    if ($null -eq $provider -or $provider.GetAttribute('permission', $androidNamespace) -ne 'android.permission.INTERACT_ACROSS_USERS_FULL' -or
        $provider.GetAttribute('exported', $androidNamespace) -ne 'true' -or
        $provider.GetAttribute('authorities', $androidNamespace) -ne 'io.github.originosfcmguardian.shizuku') {
        throw "$variant external Shizuku provider boundary is missing or changed."
    }
    foreach ($node in $manifest.SelectNodes('/manifest/application/*')) {
        $component = $node.GetAttribute('name', $androidNamespace)
        if ($component -match 'priv\.kit|InternalActivation|PairingNotification|AppOwnedRecovery') {
            throw "$variant contains cancelled internal component $component."
        }
        if ($component -match '\.(monitoring|notification)\.|GuardianFirebaseMessagingService|com\.google\.firebase\.') {
            throw "$variant contains archived advanced diagnostic component $component."
        }
        if ($component -match 'PackageStoppedStateMain|ReadOnlyMcsQualificationMain|ReadOnlyStoppedFixtureMain') {
            throw "$variant fixed shell helpers must not be manifest components."
        }
    }
    if ($manifest.manifest.application.GetAttribute('allowBackup', $androidNamespace) -ne 'false') {
        throw "$variant must keep backup disabled."
    }
    $tile = $manifest.SelectSingleNode('/manifest/application/service[@android:name="io.github.originosfcmguardian.autoprotection.GuardianTileService"]', $namespaces)
    if ($null -eq $tile -or $tile.GetAttribute('permission', $androidNamespace) -ne 'android.permission.BIND_QUICK_SETTINGS_TILE' -or
        $tile.GetAttribute('exported', $androidNamespace) -ne 'true' -or
        $tile.SelectNodes('meta-data[@android:name="android.service.quicksettings.ACTIVE_TILE"]', $namespaces).Count -ne 0) {
        throw "$variant must retain protected non-active Guardian tile."
    }
    $mainActivity = $manifest.SelectSingleNode('/manifest/application/activity[@android:name="io.github.originosfcmguardian.MainActivity"]', $namespaces)
    if ($null -eq $mainActivity -or $mainActivity.GetAttribute('exported', $androidNamespace) -ne 'true') {
        throw "$variant must retain exported MainActivity for Guardian tile long press."
    }
    $preferencesFilters = $mainActivity.SelectNodes('intent-filter[action[@android:name="android.service.quicksettings.action.QS_TILE_PREFERENCES"]]', $namespaces)
    if ($preferencesFilters.Count -ne 1 -or
        $preferencesFilters[0].SelectNodes('action').Count -ne 1 -or
        $preferencesFilters[0].SelectNodes('category').Count -ne 1 -or
        $preferencesFilters[0].SelectNodes('category[@android:name="android.intent.category.DEFAULT"]', $namespaces).Count -ne 1 -or
        $preferencesFilters[0].SelectNodes('data').Count -ne 0) {
        throw "$variant must route Guardian tile long press through a dedicated MainActivity preferences filter."
    }
    $launcherFilters = $mainActivity.SelectNodes('intent-filter[action[@android:name="android.intent.action.MAIN"]]', $namespaces)
    if ($launcherFilters.Count -ne 1 -or
        $launcherFilters[0].SelectNodes('action').Count -ne 1 -or
        $launcherFilters[0].SelectNodes('category').Count -ne 1 -or
        $launcherFilters[0].SelectNodes('category[@android:name="android.intent.category.LAUNCHER"]', $namespaces).Count -ne 1) {
        throw "$variant must retain a separate MainActivity launcher filter."
    }
    $protection = $manifest.SelectSingleNode('/manifest/application/service[@android:name="io.github.originosfcmguardian.autoprotection.AutomaticProtectionService"]', $namespaces)
    if ($null -eq $protection -or $protection.GetAttribute('exported', $androidNamespace) -ne 'false') {
        throw "$variant must retain private protection service."
    }
    $heartbeatDriver = $manifest.SelectSingleNode('/manifest/application/receiver[@android:name="io.github.originosfcmguardian.gmsheartbeat.DebugHeartbeatContinuityReceiver"]', $namespaces)
    if ($variant -eq 'debug') {
        if ($null -eq $heartbeatDriver -or $heartbeatDriver.GetAttribute('permission', $androidNamespace) -ne 'android.permission.DUMP' -or
            $heartbeatDriver.GetAttribute('exported', $androidNamespace) -ne 'true' -or
            $heartbeatDriver.SelectNodes('intent-filter/action[@android:name="io.github.originosfcmguardian.DEBUG_HEARTBEAT_REATTACH"]', $namespaces).Count -ne 1) {
            throw 'Debug heartbeat continuity driver must retain its DUMP boundary and fixed action.'
        }
    } elseif ($null -ne $heartbeatDriver) { throw 'Release must not contain the heartbeat acceptance driver.' }
    $lifecycleDriver = $manifest.SelectSingleNode('/manifest/application/receiver[@android:name="io.github.originosfcmguardian.autoprotection.DebugProtectionLifecycleReceiver"]', $namespaces)
    if ($variant -eq 'debug') {
        if ($null -eq $lifecycleDriver -or $lifecycleDriver.GetAttribute('permission', $androidNamespace) -ne 'android.permission.DUMP' -or
            $lifecycleDriver.GetAttribute('exported', $androidNamespace) -ne 'true' -or
            $lifecycleDriver.SelectNodes('intent-filter/action[@android:name="io.github.originosfcmguardian.DEBUG_STOP_PROTECTION_SERVICE"]', $namespaces).Count -ne 1) {
            throw 'Debug lifecycle driver must retain DUMP and one fixed action.'
        }
    } elseif ($null -ne $lifecycleDriver) { throw 'Release must not contain the lifecycle acceptance driver.' }
    $apkName = if ($variant -eq 'debug') { 'app-debug.apk' } else { 'app-release-unsigned.apk' }
    $apkPath = Join-Path $Repository "app/build/outputs/apk/$variant/$apkName"
    $archive = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
    try {
        if (@($archive.Entries | Where-Object { $_.FullName -match 'libprivkitstarter|priv-kit-attribution' }).Count -ne 0) {
            throw "$variant must not package cancelled PrivKit runtime/assets."
        }
        if (@($archive.Entries | Where-Object { $_.FullName -match 'libguardian_ofd\.so$' }).Count -ne 0) {
            throw "$variant must not package the removed GMS Doze native primitive."
        }
    } finally { $archive.Dispose() }
    $packages = & $apkAnalyzer dex packages --defined-only $apkPath 2>&1
    if ($LASTEXITCODE -ne 0) { throw "$variant DEX inspection failed." }
    $defined = $packages -join "`n"
    if ($defined -match 'priv\.kit|io\.github\.originosfcmguardian\.(activation|privilegebridge)|AppOwnedRecovery|PrivKitRecovery') {
        throw "$variant contains cancelled internal DEX classes."
    }
    if ($variant -eq 'release' -and $defined -match 'DebugHeartbeatContinuityReceiver|DebugProtectionLifecycleReceiver|ReadOnlyMcsQualificationMain|ReadOnlyStoppedFixtureMain') {
        throw 'Release DEX must not contain the heartbeat acceptance driver.'
    }
    if ($defined -match 'io\.github\.originosfcmguardian\.(doze|gmsconfig|data\.journal)|io\.github\.originosfcmguardian\.core\.(doze|journal)|ReadOnlyDetector|RepairPlanner') {
        throw "$variant must not contain removed standalone GMS protection/configuration classes."
    }
    if ($defined -match 'io\.github\.originosfcmguardian\.(monitoring|notification)|io\.github\.originosfcmguardian\.core\.trace|com\.google\.firebase|FcmRegistrationClient|GuardianFirebaseMessagingService|DeepMonitoringCard|RetainedTimelineCard') {
        throw "$variant must not package archived advanced tools or Firebase runtime."
    }
    $results += [pscustomobject]@{
        variant = $variant
        externalProvider = 'PASS'
        noInternalComponentsWssNativeDex = 'PASS'
        preservedPrivateProtectionAndTile = 'PASS'
        tileLongPressMainActivity = 'PASS'
        heartbeatDriverDebugOnlyDumpBoundary = 'PASS'
        lifecycleDriverDebugOnlyDumpBoundary = 'PASS'
        noStandaloneGmsProtectionOrNative = 'PASS'
        noArchivedAdvancedCollectorsOrFirebase = 'PASS'
        apkSha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
$mapping = Get-Content -LiteralPath (Join-Path $Repository 'app/build/outputs/mapping/release/mapping.txt') -Raw
if ($mapping -match '(?m)^(priv\.kit\.|io\.github\.originosfcmguardian\.(activation|privilegebridge)\.|io\.github\.originosfcmguardian\.recovery\.(AppOwnedRecovery|PrivKitRecovery))') {
    throw 'Release mapping still originates from cancelled internal code.'
}
if ($mapping -match '(?m)^io\.github\.originosfcmguardian\.(doze|gmsconfig|data\.journal|core\.(doze|journal))\.') {
    throw 'Release mapping must not originate from removed standalone GMS protection code.'
}
if ($mapping -match '(?m)^(io\.github\.originosfcmguardian\.(monitoring|notification|core\.trace)\.|com\.google\.firebase\.)') {
    throw 'Release mapping must not originate from archived advanced tools or Firebase runtime.'
}
$seeds = Get-Content -LiteralPath (Join-Path $Repository 'app/build/outputs/mapping/release/seeds.txt') -Raw
$entryPoints = @(
    'io.github.originosfcmguardian.privileged.GuardianUserService',
    'io.github.originosfcmguardian.recovery.StoppedRecoveryUserService'
)
foreach ($entryPoint in $entryPoints) {
    $escaped = [regex]::Escape($entryPoint)
    if ($mapping -notmatch "(?m)^$escaped -> ${escaped}:`r?$") {
        throw "Release must keep external UserService class $entryPoint."
    }
    $shortName = ($entryPoint -split '\.')[-1]
    if ($seeds -notmatch ([regex]::Escape("$entryPoint`: ${shortName}(android.content.Context)"))) {
        throw "Release must keep external UserService Context constructor $entryPoint."
    }
}
$unstopHelper = 'io.github.originosfcmguardian.recovery.PackageStoppedStateMain'
$escapedUnstopHelper = [regex]::Escape($unstopHelper)
if ($mapping -notmatch "(?m)^$escapedUnstopHelper -> ${escapedUnstopHelper}:`r?$" -or
    $seeds -notmatch ([regex]::Escape("${unstopHelper}: void main(java.lang.String[])"))) {
    throw 'Release must retain the fixed PackageStoppedStateMain class and static main entry point.'
}
[pscustomobject]@{ artifacts = $results; externalUserServices = $entryPoints; result = 'PASS (host only)' } | ConvertTo-Json -Depth 5
