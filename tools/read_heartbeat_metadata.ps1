param([string]$Adb = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'))

# Fixed own-app file only. Does not export session/attempt/consent IDs or GMS installation binding.
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Adb)) { throw 'ADB not found' }
$taskInfo = [Diagnostics.ProcessStartInfo]::new()
$taskInfo.FileName = $Adb
$taskInfo.Arguments = 'exec-out run-as io.github.originosfcmguardian head -c 1025 no_backup/gms-auto-heartbeat-v1.bin'
$taskInfo.UseShellExecute = $false
$taskInfo.CreateNoWindow = $true
$taskInfo.RedirectStandardOutput = $true
$taskInfo.RedirectStandardError = $true
$taskProcess = [Diagnostics.Process]::Start($taskInfo)
$taskBuffer = [IO.MemoryStream]::new()
try {
    $taskCopy = $taskProcess.StandardOutput.BaseStream.CopyToAsync($taskBuffer)
    $taskErrors = $taskProcess.StandardError.ReadToEndAsync()
    if (-not $taskCopy.Wait(5000) -or -not $taskProcess.WaitForExit(1000)) {
        $taskProcess.Kill($true)
        throw 'Heartbeat metadata read timed out'
    }
    if ($taskProcess.ExitCode -ne 0 -or -not $taskErrors.Wait(1000)) { throw 'Heartbeat metadata unavailable' }
    $taskBytes = $taskBuffer.ToArray()
} finally { $taskBuffer.Dispose(); $taskProcess.Dispose() }
if ($taskBytes.Length -lt 32 -or $taskBytes.Length -gt 1024) { throw 'Heartbeat metadata size invalid' }

function Get-HeartbeatDigest([byte[]]$Bytes) {
    $taskHash = [Security.Cryptography.SHA256]::Create()
    try { ([BitConverter]::ToString($taskHash.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $taskHash.Dispose() }
}
function Read-HeartbeatNumber([IO.BinaryReader]$Reader, [int]$Size) {
    [long]$taskValue = 0
    for ($taskIndex=0; $taskIndex -lt $Size; $taskIndex++) { $taskValue=$taskValue*256+$Reader.ReadByte() }
    $taskValue
}
function Read-HeartbeatFlag([IO.BinaryReader]$Reader) {
    $taskValue=$Reader.ReadByte()
    if ($taskValue -notin @(0,1)) { throw 'Invalid heartbeat boolean' }
    $taskValue -eq 1
}
function Read-HeartbeatText([IO.BinaryReader]$Reader) {
    $taskLength=Read-HeartbeatNumber $Reader 2
    if ($taskLength -gt 64 -or $taskLength -gt $Reader.BaseStream.Length-$Reader.BaseStream.Position) { throw 'Invalid heartbeat text length' }
    [Text.UTF8Encoding]::new($false,$true).GetString($Reader.ReadBytes($taskLength))
}
function Read-HeartbeatEnum([IO.BinaryReader]$Reader, [string[]]$Names) {
    $taskOrdinal=Read-HeartbeatNumber $Reader 4
    if ($taskOrdinal -eq 4294967295) { return $null }
    if ($taskOrdinal -ge $Names.Length) { throw 'Unknown heartbeat enum' }
    $Names[$taskOrdinal]
}
function Assert-HeartbeatUuid([string]$Value) {
    if ($Value -and $Value -cnotmatch '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$') { throw 'Invalid heartbeat UUID' }
}

$taskPayload=[byte[]]$taskBytes[0..($taskBytes.Length-33)]
$taskChecksum=([BitConverter]::ToString($taskBytes[($taskBytes.Length-32)..($taskBytes.Length-1)])).Replace('-','').ToLowerInvariant()
if ((Get-HeartbeatDigest $taskPayload) -ne $taskChecksum) { throw 'Heartbeat checksum mismatch' }
$taskReader=[IO.BinaryReader]::new([IO.MemoryStream]::new($taskPayload))
try {
    if ((Read-HeartbeatNumber $taskReader 4) -ne 0x474D5348) { throw 'Invalid heartbeat magic' }
    $taskSchema=Read-HeartbeatNumber $taskReader 4
    if ($taskSchema -notin @(1,2,3)) { throw 'Unknown heartbeat schema' }
    $taskGeneration=Read-HeartbeatNumber $taskReader 8
    if ($taskGeneration -lt 1) { throw 'Invalid heartbeat generation' }
    $taskConfigured=Read-HeartbeatFlag $taskReader
    $taskPause=Read-HeartbeatEnum $taskReader @('RECONFIRM','HOST_LOST','UNKNOWN_RESULT','LEFTOVER_PENDING','OFF_PENDING','STORAGE_FAILURE','WAITING_HOST')
    $taskAttempt=Read-HeartbeatText $taskReader
    $taskBinding=Read-HeartbeatText $taskReader
    $taskSession=Read-HeartbeatText $taskReader
    Assert-HeartbeatUuid $taskAttempt; Assert-HeartbeatUuid $taskSession
    if ($taskBinding -and $taskBinding -cnotmatch '^[a-f0-9]{64}$') { throw 'Invalid heartbeat binding' }
    if ($taskAttempt -and (-not $taskBinding -or -not $taskSession)) { throw 'Incomplete heartbeat pending' }
    $taskStatus=Read-HeartbeatEnum $taskReader @('NOT_SENT','REQUESTED','UNKNOWN')
    $taskReason=Read-HeartbeatEnum $taskReader @('INVALID_REQUEST','SHELL_UNAVAILABLE','WRONG_USER','IDENTITY_UNKNOWN','IDENTITY_CHANGED','SHARED_UID','OBSERVATION_UNKNOWN','REPORTED_DISCONNECTED','STALE_OBSERVATION','BUSY','TIMED_OUT','CANCELLED','BRIDGE_UNAVAILABLE','COMMAND_ACCEPTED','COMMAND_FAILED','OUTPUT_INCOMPLETE','MALFORMED_RESPONSE','SESSION_REVOKED','INTERVAL_NOT_ELAPSED','DUPLICATE_ATTEMPT','CALLER_UNAVAILABLE')
    if (($null -eq $taskStatus) -ne ($null -eq $taskReason) -or ($taskStatus -eq 'REQUESTED') -ne ($taskReason -eq 'COMMAND_ACCEPTED')) { throw 'Invalid heartbeat result pair' }
    $taskConsent=$false; $taskConsentVersion=$null; $taskConsentUser=$null; $taskResumeBlock=$null; $taskResultEpoch=$null
    if ($taskSchema -ge 2) {
        $taskConsent=Read-HeartbeatFlag $taskReader
        if ($taskConsent) {
            $taskConsentVersion=Read-HeartbeatNumber $taskReader 4
            $taskEpoch=Read-HeartbeatText $taskReader
            $taskConsentUser=Read-HeartbeatNumber $taskReader 4
            $taskConsentBinding=Read-HeartbeatText $taskReader
            Assert-HeartbeatUuid $taskEpoch
            if ($taskConsentVersion -notin @(1,2,3) -or ($taskConsentVersion -ge 2 -and $taskSchema -ne 3) -or -not $taskEpoch -or $taskConsentUser -ne 0 -or
                -not $taskConfigured -or -not $taskBinding -or $taskConsentBinding -cne $taskBinding) { throw 'Invalid durable heartbeat consent' }
        }
        $taskResumeBlock=Read-HeartbeatEnum $taskReader @('COLD_RECONSTRUCTION','UNKNOWN_RESULT','LEFTOVER_PENDING','IDENTITY_CHANGED','SECURITY_FAILURE','STORAGE_FAILURE','UNCERTAIN_OWNER')
        $taskResultEpoch=Read-HeartbeatText $taskReader
        Assert-HeartbeatUuid $taskResultEpoch
    }
    $taskOwnerPresent=$false; $taskPendingArm=$null
    if ($taskSchema -eq 3) {
        $taskOwnerPresent=Read-HeartbeatFlag $taskReader
        if ($taskOwnerPresent) {
            $taskOwnerUid=Read-HeartbeatNumber $taskReader 4
            $taskOwnerPid=Read-HeartbeatNumber $taskReader 4
            $taskOwnerStartTicks=Read-HeartbeatNumber $taskReader 8
            $taskOwnerBoot=Read-HeartbeatText $taskReader
            Assert-HeartbeatUuid $taskOwnerBoot
            if ($taskOwnerUid -lt 10000 -or $taskOwnerUid -ge 100000 -or $taskOwnerPid -le 0 -or
                $taskOwnerStartTicks -le 0 -or -not $taskOwnerBoot) { throw 'Invalid heartbeat owner instance' }
        }
        $taskPendingArm=Read-HeartbeatText $taskReader
        Assert-HeartbeatUuid $taskPendingArm
        if ($taskConsentVersion -ge 2 -and -not $taskOwnerPresent) { throw 'Missing cold-resume caller identity' }
    }
    if ($taskReader.BaseStream.Position -ne $taskReader.BaseStream.Length) { throw 'Unexpected heartbeat trailing bytes' }
} finally { $taskReader.Dispose() }
[ordered]@{
    schema=$taskSchema; generation=$taskGeneration; configured=$taskConfigured; pause=$taskPause;
    pending=!!$taskAttempt; bindingPresent=!!$taskBinding; remoteSessionPresent=!!$taskSession;
    lastStatus=$taskStatus; lastReason=$taskReason; durableConsent=$taskConsent;
    consentVersion=$taskConsentVersion; consentUser=$taskConsentUser; resumeBlock=$taskResumeBlock;
    callerInstancePresent=$taskOwnerPresent; pendingArm=!!$taskPendingArm;
    resultBelongsToCurrentConsent=($taskConsent -and !!$taskResultEpoch -and $taskResultEpoch -ceq $taskEpoch);
    sha256=(Get-HeartbeatDigest $taskBytes); scope='READ_ONLY_CHECKSUM_VALIDATED_METADATA_NOT_RUNTIME_AUTHORITY'
} | ConvertTo-Json -Compress
