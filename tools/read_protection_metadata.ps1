param([string]$Adb = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'))

# Read-only diagnostic metadata. Never exports raw receipts, target identities or consent IDs.
# This is not an authorization check or a replacement for the app's full codec validation.
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Adb)) { throw 'ADB not found' }

function Get-RecordBytes([string]$Name) {
    if ($Name -notin @('automatic-protection-authority-v1.bin', 'stopped-recovery-v1.bin')) {
        throw 'Unsupported record'
    }
    $taskInfo = [Diagnostics.ProcessStartInfo]::new()
    $taskInfo.FileName = $Adb
    $taskInfo.Arguments = "exec-out run-as io.github.originosfcmguardian cat no_backup/$Name"
    $taskInfo.UseShellExecute = $false
    $taskInfo.CreateNoWindow = $true
    $taskInfo.RedirectStandardOutput = $true
    $taskInfo.RedirectStandardError = $true
    $taskProcess = [Diagnostics.Process]::Start($taskInfo)
    $taskBuffer = [IO.MemoryStream]::new()
    try {
        $taskProcess.StandardOutput.BaseStream.CopyTo($taskBuffer)
        $null = $taskProcess.StandardError.ReadToEnd()
        $taskProcess.WaitForExit()
        if ($taskProcess.ExitCode -ne 0) { throw "Record unavailable: $Name" }
        $taskMaximum = if ($Name -eq 'automatic-protection-authority-v1.bin') { 262144 } else { 8192 }
        if ($taskBuffer.Length -lt 1 -or $taskBuffer.Length -gt $taskMaximum) { throw 'Invalid record length' }
        return ,$taskBuffer.ToArray()
    } finally { $taskBuffer.Dispose(); $taskProcess.Dispose() }
}

function Get-Digest([byte[]]$Bytes) {
    $taskHash = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($taskHash.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $taskHash.Dispose() }
}

function Read-Number([IO.BinaryReader]$Reader, [int]$Size) {
    [long]$taskValue = 0
    for ($taskIndex = 0; $taskIndex -lt $Size; $taskIndex++) { $taskValue = $taskValue * 256 + $Reader.ReadByte() }
    return $taskValue
}

function Read-Text([IO.BinaryReader]$Reader, [int]$LengthBytes) {
    $taskLength = Read-Number $Reader $LengthBytes
    if ($taskLength -lt 0 -or $taskLength -gt 8192 -or
        $taskLength -gt $Reader.BaseStream.Length - $Reader.BaseStream.Position) { throw 'Invalid field length' }
    $taskUtf8 = [Text.UTF8Encoding]::new($false, $true)
    return $taskUtf8.GetString($Reader.ReadBytes($taskLength))
}

function Read-Flag([IO.BinaryReader]$Reader) {
    $taskValue = $Reader.ReadByte()
    if ($taskValue -notin @(0, 1)) { throw 'Invalid boolean' }
    return $taskValue -eq 1
}

function Assert-End([IO.BinaryReader]$Reader) {
    if ($Reader.BaseStream.Position -ne $Reader.BaseStream.Length) { throw 'Unexpected trailing data' }
}

$taskAuthorityBytes = Get-RecordBytes 'automatic-protection-authority-v1.bin'
if ($taskAuthorityBytes.Length -lt 32) { throw 'Authority checksum missing' }
$taskPayload = $taskAuthorityBytes[0..($taskAuthorityBytes.Length - 33)]
$taskStoredDigest = ([BitConverter]::ToString($taskAuthorityBytes[($taskAuthorityBytes.Length - 32)..($taskAuthorityBytes.Length - 1)])).Replace('-', '').ToLowerInvariant()
if ((Get-Digest $taskPayload) -ne $taskStoredDigest) { throw 'Authority checksum mismatch' }
$taskReader = [IO.BinaryReader]::new([IO.MemoryStream]::new([byte[]]$taskPayload))
try {
    if ((Read-Number $taskReader 4) -ne 0x46434D41) { throw 'Unsupported authority magic' }
    $taskAuthoritySchema = Read-Number $taskReader 4
    if ($taskAuthoritySchema -notin @(1, 2, 3)) { throw 'Unsupported authority schema' }
    $taskGeneration = Read-Number $taskReader 8
    $taskMode = Read-Number $taskReader 1
    if ($taskMode -notin @(0, 1, 2, 3) -or ($taskAuthoritySchema -lt 3 -and $taskMode -eq 3)) { throw 'Unknown authority mode' }
    $taskReason = Read-Number $taskReader 4
    $taskPolicy = $null; $taskConsentedAt = $null; $taskTokenDigest = $null
    $taskTokenCount = if ($taskAuthoritySchema -eq 1) { if (Read-Flag $taskReader) { 1 } else { 0 } } else { Read-Number $taskReader 4 }
    if ($taskTokenCount -lt 0 -or $taskTokenCount -gt ($taskPayload.Length / 100)) { throw 'Invalid token count' }
    $taskTokenMetadata = @(for ($taskTokenIndex = 0; $taskTokenIndex -lt $taskTokenCount; $taskTokenIndex++) {
        $taskKind = if ($taskAuthoritySchema -eq 1) { 0 } else { Read-Number $taskReader 1 }
        if ($taskKind -notin @(0, 1)) { throw 'Invalid token kind' }
        if ((Read-Number $taskReader 4) -ne 1) { throw 'Unsupported token schema' }
        $taskPolicy = Read-Number $taskReader 4
        if (($taskKind -eq 0 -and $taskPolicy -notin @(1, 2)) -or ($taskKind -eq 1 -and $taskPolicy -ne 4)) { throw 'Invalid token policy' }
        $null = Read-Number $taskReader 4
        $taskTokenDigest = Get-Digest ([Text.Encoding]::UTF8.GetBytes((Read-Text $taskReader 4)))
        $taskTokenGeneration = Read-Number $taskReader 8
        if ($taskTokenGeneration -lt 1 -or $taskTokenGeneration -gt $taskGeneration -or
            ($taskAuthoritySchema -eq 1 -and $taskTokenGeneration -ne $taskGeneration)) { throw 'Token generation mismatch' }
        $taskConsentedAt = Read-Number $taskReader 8
        $taskTargetPackage = Read-Text $taskReader 4
        $taskTargetUid = Read-Number $taskReader 4
        $taskTargetInstalledAt = Read-Number $taskReader 8
        $taskSigners = Read-Number $taskReader 4
        if ($taskSigners -lt 1 -or $taskSigners -gt 16) { throw 'Invalid signer count' }
        $taskTargetSigners = @(for ($taskIndex = 0; $taskIndex -lt $taskSigners; $taskIndex++) { Read-Text $taskReader 4 })
        $taskIdentityDigest = Get-Digest ([Text.Encoding]::UTF8.GetBytes((@($taskTargetPackage,$taskTargetUid,$taskTargetInstalledAt,($taskTargetSigners -join ',')) -join '|')))
        $null = Read-Text $taskReader 4
        [ordered]@{kind=$taskKind; policyVersion=$taskPolicy; mintedGeneration=$taskTokenGeneration;
            consentedAtMillis=$taskConsentedAt; tokenDigest=$taskTokenDigest; identityDigest=$taskIdentityDigest;
            isToolbox=($taskTargetPackage -eq 'fr.smarquis.fcm')}
    })
    if ($taskTokenCount -ne 1) { $taskPolicy = $null; $taskConsentedAt = $null; $taskTokenDigest = $null }
    $taskMasterEnabledAt = $null
    $taskInvalidatedDigests = @()
    if ($taskAuthoritySchema -eq 3) {
        $taskMasterTime = Read-Number $taskReader 8
        if ($taskMasterTime -gt 0) { $taskMasterEnabledAt = $taskMasterTime }
        $taskInvalidatedCount = Read-Number $taskReader 4
        if ($taskInvalidatedCount -lt 0 -or $taskInvalidatedCount -gt $taskTokenCount) { throw 'Invalid invalidated count' }
        $taskInvalidatedDigests = @(for ($taskIndex = 0; $taskIndex -lt $taskInvalidatedCount; $taskIndex++) {
            Get-Digest ([Text.Encoding]::UTF8.GetBytes((Read-Text $taskReader 4)))
        })
        if (@($taskInvalidatedDigests | Select-Object -Unique).Count -ne $taskInvalidatedDigests.Count -or
            @($taskInvalidatedDigests | Where-Object { $_ -notin @($taskTokenMetadata.tokenDigest) }).Count -ne 0) {
            throw 'Invalid invalidated identity reference'
        }
        foreach ($taskTarget in $taskTokenMetadata) { $taskTarget['invalidated'] = $taskTarget.tokenDigest -in $taskInvalidatedDigests }
    }
    $taskBudgetLength = Read-Number $taskReader 4
    if ($taskBudgetLength -lt 1 -or $taskBudgetLength -gt 256) { throw 'Invalid budget length' }
    $taskBudgetReader = [IO.BinaryReader]::new([IO.MemoryStream]::new($taskReader.ReadBytes($taskBudgetLength)))
    try {
        if ((Read-Number $taskBudgetReader 4) -ne 0x46434D42 -or (Read-Number $taskBudgetReader 4) -ne 1) { throw 'Unsupported budget schema' }
        $taskBoot = Read-Number $taskBudgetReader 8
        $taskElapsed = Read-Number $taskBudgetReader 8
        $taskEpoch = Read-Number $taskBudgetReader 8
        $taskCount = Read-Number $taskBudgetReader 4
        if ($taskCount -lt 0 -or $taskCount -gt 3) { throw 'Invalid attempt count' }
        $taskAttempts = @(for ($taskIndex = 0; $taskIndex -lt $taskCount; $taskIndex++) { Read-Number $taskBudgetReader 8 })
        $taskQuarantine = if (Read-Flag $taskBudgetReader) { Read-Number $taskBudgetReader 8 } else { $null }
        Assert-End $taskBudgetReader
    } finally { $taskBudgetReader.Dispose() }
    Assert-End $taskReader
} finally { $taskReader.Dispose() }

$taskJournalBytes = Get-RecordBytes 'stopped-recovery-v1.bin'
$taskReader = [IO.BinaryReader]::new([IO.MemoryStream]::new($taskJournalBytes))
try {
    $taskSchema = Read-Number $taskReader 4
    if ($taskSchema -notin @(1, 2, 3)) { throw 'Unsupported journal schema' }
    $taskHigh = Read-Number $taskReader 8
    $taskReceipt = $null
    if (Read-Flag $taskReader) {
        $taskRequest = Read-Number $taskReader 8
        $taskReceiptIdentityDigest = Get-Digest ([Text.Encoding]::UTF8.GetBytes((Read-Text $taskReader 2)))
        $taskCreated = Read-Number $taskReader 8
        $null = Read-Number $taskReader 8
        $null = Read-Text $taskReader 2
        $null = Read-Number $taskReader 4
        $taskBaseline = Read-Flag $taskReader
        $null = Read-Flag $taskReader
        $null = Read-Flag $taskReader
        $taskStage = Read-Text $taskReader 2
        $taskOutcome = Read-Text $taskReader 2
        $null = Read-Text $taskReader 2
        $taskSource = 'MANUAL'; $taskReceiptPolicy = $null; $taskReceiptConsentDigest = $null; $taskReceiptConsentedAt = $null
        if ($taskSchema -ge 2) {
            $taskSource = Read-Text $taskReader 2
            $taskReceiptConsentDigest = Get-Digest ([Text.Encoding]::UTF8.GetBytes((Read-Text $taskReader 2)))
            $taskReceiptConsentedAt = Read-Number $taskReader 8
            $taskReceiptPolicy = Read-Number $taskReader 4
        }
        $taskReceipt = [ordered]@{requestId=$taskRequest; source=$taskSource; consentVersion=$taskReceiptPolicy;
            createdAtMillis=$taskCreated; baselineStopped=$taskBaseline; stage=$taskStage; outcome=$taskOutcome;
            requiresReconcile=($taskStage -in @('PREPARED', 'CLAIMED'));
            identityDigest=$taskReceiptIdentityDigest; consentDigest=$taskReceiptConsentDigest; consentedAtMillis=$taskReceiptConsentedAt}
    }
    Assert-End $taskReader
} finally { $taskReader.Dispose() }

[ordered]@{
    authority=[ordered]@{sha256=(Get-Digest $taskAuthorityBytes); checksum='PASS'; mode=@('DISABLED','ACTIVE','SUSPENDED','USER_PAUSED')[$taskMode];
        schema=$taskAuthoritySchema; targetCount=$taskTokenCount; targets=$taskTokenMetadata;
        masterEnabledAtMillis=$taskMasterEnabledAt; invalidatedCount=$taskInvalidatedDigests.Count;
        generation=$taskGeneration; policyVersion=$taskPolicy; consentedAtMillis=$taskConsentedAt; tokenDigest=$taskTokenDigest;
        suspensionReasonOrdinal=if($taskReason -eq 4294967295){$null}else{$taskReason};
        budget=[ordered]@{boot=$taskBoot; elapsed=$taskElapsed; epoch=$taskEpoch; attempts=$taskAttempts; quarantineUntil=$taskQuarantine}}
    journal=[ordered]@{sha256=(Get-Digest $taskJournalBytes); schema=$taskSchema; claimedHighWater=$taskHigh; latest=$taskReceipt}
    scope='READ_ONLY_METADATA; not full app authorization validation; no raw identity, content or consent ID exported'
} | ConvertTo-Json -Depth 8 -Compress
