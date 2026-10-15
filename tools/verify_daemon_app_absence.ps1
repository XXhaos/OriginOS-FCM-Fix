param(
    [Parameter(Mandatory = $true)][int]$ExpectedAppPid,
    [Parameter(Mandatory = $true)][long]$ExpectedStartTicks,
    [Parameter(Mandatory = $true)][int]$ExpectedAppUid,
    [Parameter(Mandatory = $true)][int]$ExpectedDaemonPid,
    [switch]$Execute,
    [string]$Adb = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
)

# Finite reviewed acceptance: Guardian App SIGKILL + ONLY the already-authorized Toolbox target.
# No UI, wake/unlock, GMS, network, permission or Shizuku mutation exists in the quiet window.
$ErrorActionPreference = 'Stop'
if (-not $Execute) { throw 'Read the reviewed test plan and pass -Execute explicitly.' }
if ($ExpectedAppPid -le 1 -or $ExpectedDaemonPid -le 1 -or $ExpectedAppPid -eq $ExpectedDaemonPid) { throw 'Invalid exact process targets' }
if ($ExpectedAppUid -lt 10000 -or $ExpectedAppUid -gt 99999) { throw 'Expected a user-0 application UID' }
$taskAppUser = 'u0_a' + ($ExpectedAppUid - 10000)
function Read-Adb([string[]]$TaskArguments) {
    $taskText = & $Adb @TaskArguments
    if ($LASTEXITCODE -ne 0) { throw 'ADB operation failed' }
    return ($taskText -join "`n")
}
function App-Processes {
    $taskPs = Read-Adb @('shell','ps','-A','-o','USER,PID,NAME')
    return @($taskPs -split "`n" | Where-Object { $_ -match ('^\s*' + [regex]::Escape($taskAppUser) + '\s+') })
}
function Target-Stopped {
    $taskPackage = Read-Adb @('shell','dumpsys','package','fr.smarquis.fcm')
    $taskLine = ($taskPackage -split "`n" | Where-Object { $_ -match '^\s*User 0:.* installed=true ' } | Select-Object -First 1)
    if ($taskLine -notmatch ' stopped=(true|false) ') { throw 'Target stopped state unknown' }
    return $Matches[1] -eq 'true'
}
$taskStatus = Read-Adb @('shell','cat',"/proc/$ExpectedAppPid/status")
if ($taskStatus -notmatch "(?m)^Uid:\s+$ExpectedAppUid\s+$ExpectedAppUid\s+$ExpectedAppUid\s+$ExpectedAppUid\s*$") { throw 'Guardian App UID changed' }
$taskStat = Read-Adb @('shell','cat',"/proc/$ExpectedAppPid/stat")
if ($taskStat -notmatch '^\d+ \([^\r\n]+\) (.+)$') { throw 'Unknown process stat format' }
$taskStatFields = $Matches[1] -split ' '
if ([long]$taskStatFields[19] -ne $ExpectedStartTicks) { throw 'App PID was reused' }
$taskPs = Read-Adb @('shell','ps','-A','-o','USER,PID,NAME')
if ($taskPs -notmatch "(?m)^shell\s+$ExpectedDaemonPid\s+io\.github\.originosfcmguardian:guardian_protection\s*$") { throw 'Native owner changed' }
if ((App-Processes).Count -ne 1 -or (Target-Stopped)) { throw 'Baseline must have one App and unrestricted Toolbox' }
$taskDisplay = Read-Adb @('shell','dumpsys','power')
if ($taskDisplay -notmatch 'mWakefulness=Asleep') { throw 'Baseline must already be asleep' }

$taskClock = [Diagnostics.Stopwatch]::StartNew()
$taskAppAbsent = $true
$taskRecovered = $false
$taskInjected = $false
try {
    $null = Read-Adb @('shell','run-as','io.github.originosfcmguardian','kill','-9',"$ExpectedAppPid")
    if ((App-Processes).Count -ne 0) { throw 'App did not remain absent before injection' }
    $null = Read-Adb @('shell','am','force-stop','--user','0','fr.smarquis.fcm')
    $taskInjected = $true
    if (-not (Target-Stopped)) { throw 'Stopped=true was not observed; no valid injected window' }
    [pscustomobject]@{stage='INJECTED';elapsedMs=$taskClock.ElapsedMilliseconds;appAbsent=$true;targetStopped=$true} | ConvertTo-Json -Compress
    while ($taskClock.ElapsedMilliseconds -lt 75000) {
        Start-Sleep -Milliseconds 2000
        if ((App-Processes).Count -ne 0) { $taskAppAbsent = $false; break }
        if (-not (Target-Stopped)) { $taskRecovered = $true; break }
    }
    [pscustomobject]@{stage='TEST_ENDED';elapsedMs=$taskClock.ElapsedMilliseconds;
        appAbsentAtEverySample=$taskAppAbsent;targetRecovered=$taskRecovered;
        result= $(if ($taskAppAbsent -and $taskRecovered) {'FLAG_RECOVERED_NEEDS_JOURNAL_COUNT_READBACK'} else {'NOT_PASSED'})} | ConvertTo-Json -Compress
} finally {
    # This recovery is OUTSIDE the quiet evidence window and is not credited to Guardian.
    if ($taskInjected -and (Target-Stopped)) {
        Write-Output 'TEST_ENDED: restoring only Toolbox through its normal launcher; excluded from evidence'
        $null = Read-Adb @('shell','am','start','--user','0','-n','fr.smarquis.fcm/.view.ui.MainActivity')
        $null = Read-Adb @('shell','input','keyevent','KEYCODE_HOME')
        $null = Read-Adb @('shell','input','keyevent','KEYCODE_SLEEP')
        if (Target-Stopped) { throw 'Toolbox recovery requires attention' }
    }
}
