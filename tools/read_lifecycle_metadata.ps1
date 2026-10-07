param([string]$Adb = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'), [switch]$IncludeEvents)

# Read-only fixed own-app diagnostic journal. No authority, exception text or chat is read.
$ErrorActionPreference = 'Stop'
$taskInfo = [Diagnostics.ProcessStartInfo]::new()
$taskInfo.FileName = $Adb
$taskInfo.Arguments = 'exec-out run-as io.github.originosfcmguardian head -c 32769 no_backup/protection-lifecycle-v1.bin'
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
        $taskProcess.Kill()
        throw 'Lifecycle metadata read timed out'
    }
    if ($taskProcess.ExitCode -ne 0 -or -not $taskErrors.Wait(1000)) { throw 'Lifecycle journal unavailable' }
    $taskBytes = $taskBuffer.ToArray()
} finally { $taskProcess.Dispose(); $taskBuffer.Dispose() }
if ($taskBytes.Length -lt 32 -or $taskBytes.Length -gt 32768) { throw 'Invalid journal length' }
$taskPayload = [byte[]]$taskBytes[0..($taskBytes.Length - 33)]
$taskHash = [Security.Cryptography.SHA256]::Create()
try {
    $taskChecksum = $taskHash.ComputeHash($taskPayload)
    if ([Convert]::ToBase64String($taskChecksum) -ne [Convert]::ToBase64String($taskBytes[($taskBytes.Length - 32)..($taskBytes.Length - 1)])) {
        throw 'Lifecycle checksum mismatch'
    }
} finally { $taskHash.Dispose() }
$taskReader = [IO.BinaryReader]::new([IO.MemoryStream]::new($taskPayload))
function Read-Number([int]$Size) {
    $part = $taskReader.ReadBytes($Size)
    if ($part.Length -ne $Size) { throw 'Truncated journal' }
    [Array]::Reverse($part)
    if ($Size -eq 4) { return [BitConverter]::ToInt32($part, 0) }
    return [BitConverter]::ToInt64($part, 0)
}
function Read-Label {
    $count = $taskReader.ReadByte() * 256 + $taskReader.ReadByte()
    if ($count -lt 1 -or $count -gt 64) { throw 'Invalid enum length' }
    $label = [Text.Encoding]::ASCII.GetString($taskReader.ReadBytes($count))
    if ($label -notmatch '^[A-Z_]+$') { throw 'Invalid enum label' }
    return $label
}
try {
    if ((Read-Number 4) -ne 0x474C4A31 -or (Read-Number 4) -ne 1) { throw 'Unsupported journal' }
    $taskDropped = Read-Number 8
    $taskExpected = $taskReader.ReadBoolean()
    $null = Read-Number 4 # Stored service process ID; not needed for this output.
    $taskWatermark = Read-Number 8
    $taskExitAvailable = $taskReader.ReadBoolean()
    $taskCount = Read-Number 4
    if ($taskCount -lt 0 -or $taskCount -gt 64 -or $taskDropped -lt 0) { throw 'Invalid journal bounds' }
    $taskEvents = @(for ($taskIndex = 0; $taskIndex -lt $taskCount; $taskIndex++) {
        $at = Read-Number 8; $elapsed = Read-Number 8; $null = Read-Number 4
        $kind = Read-Label; $reason = Read-Label
        $exitReason = $null; $exitStatus = $null
        if ($taskReader.ReadBoolean()) { $exitReason = Read-Number 4; $exitStatus = Read-Number 4 }
        [pscustomobject]@{AtMillis=$at;ElapsedMillis=$elapsed;Kind=$kind;Reason=$reason;SystemExitReason=$exitReason;SystemExitStatus=$exitStatus}
    })
    if ($taskReader.BaseStream.Position -ne $taskReader.BaseStream.Length) { throw 'Unexpected journal suffix' }
    [pscustomobject]@{Checksum='PASS';Bytes=$taskBytes.Length;EventCount=$taskCount;Dropped=$taskDropped;
        ServiceHasNoRecordedEnd=$taskExpected;SystemExitHistoryAvailable=$taskExitAvailable;SystemExitThroughMillis=$taskWatermark;
        KindCounts=@($taskEvents | Group-Object Kind | Select-Object Name,Count);
        Events=if ($IncludeEvents) { $taskEvents } else { @() }} | ConvertTo-Json -Depth 5 -Compress
} finally { $taskReader.Dispose() }
