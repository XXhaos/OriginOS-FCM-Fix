$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'analyze_survival_reports.ps1') -LibraryOnly

function Assert-Equal($Expected, $Actual, [string]$Name) {
    if ($Expected -ne $Actual) { throw "Assertion failed: $Name" }
}
function New-Exit($At, $TaskProcessId, $Reason = 3) {
    [pscustomobject]@{ event = 'SYSTEM_PROCESS_EXIT'; atEpochMillis = $At; guardianProcessId = $TaskProcessId; systemExitReason = $Reason; systemExitStatus = 0 }
}
$report = [pscustomobject]@{
    schema = 'guardian.combined-diagnostic.v8'; capturedAtEpochMillis = 10000
    environment = @{ guardianVersionCode = 69 }
    protectionLifecycle = @{ availability = 'AVAILABLE'; droppedEvents = 0; events = @(
        (New-Exit 1000 1), (New-Exit 1000 1), (New-Exit 4000 2), (New-Exit 9000 3 2),
        @{ event = 'PROCESS_STARTED'; atEpochMillis = 4500; guardianProcessId = 3 },
        @{ event = 'SERVICE_RUNNING'; atEpochMillis = 4700; guardianProcessId = 3 }
    ) }
    fcmProtection = @{ configured = $true; maintenance = 'ACTIVE'; recoveryReady = $true }
    fcmRecoveryTrial = @{ events = @(@{ action = 'OBSERVATION'; atEpochMillis = 1 }) }
}
$summary = Get-GuardianSurvivalSummary $report
Assert-Equal 3 $summary.retainedExitCount 'deduplicate equal tuples'
Assert-Equal 2 $summary.retainedNaturalLowMemoryCount 'natural count'
Assert-Equal 3.0 $summary.retainedMedianGapSeconds 'median interval'
Assert-Equal 1 $summary.retainedUnattributedSignalCount 'signals not automatically artificial'
Assert-Equal 0.5 $summary.reconstruction[1].firstRetainedProcessStartDelaySeconds 'retained start delay'
Assert-Equal 0.7 $summary.reconstruction[1].firstRetainedServiceRunningDelaySeconds 'retained service delay'
Assert-Equal $false $summary.fcm.continuousOperationProven 'ACTIVE is not delivery/continuous proof'
Assert-Equal 'UNKNOWN_UNLESS_RESOURCE_LIFETIME_MATCHED' $summary.historicalExitVersionAttribution 'export version is not exit version'
$classified = Get-GuardianSurvivalSummary $report -ArtificialExits @(9000)
Assert-Equal 0 $classified.retainedUnattributedSignalCount 'explicit artificial exclusion'
Assert-Equal 1 $classified.retainedKnownArtificialCount 'known artificial count'
$report.protectionLifecycle.events = @()
$empty = Get-GuardianSurvivalSummary $report
Assert-Equal $null $empty.retainedMedianGapSeconds 'no gap is unknown, not zero'
$report.schema = 'guardian.combined-diagnostic.v9'
$report | Add-Member -NotePropertyName protectionResources -NotePropertyValue @{
    availability = 'AVAILABLE'; exitAnalysis = @(
        @{ exitAtEpochMillis = 1000; processId = 1; systemExitReason = 3; systemExitStatus = 0 },
        @{ exitAtEpochMillis = 4000; processId = 2; systemExitReason = 3; systemExitStatus = 0 }
    )
}
$sidecar = Get-GuardianSurvivalSummary $report
Assert-Equal 2 $sidecar.retainedNaturalLowMemoryCount 'sidecar survives lifecycle eviction'
Assert-Equal 3.0 $sidecar.retainedMedianGapSeconds 'sidecar intervals'
$report.protectionLifecycle.events = @((New-Exit 1000 1))
Assert-Equal 2 (Get-GuardianSurvivalSummary $report).retainedNaturalLowMemoryCount 'merge deduplicates lifecycle and sidecar'
$report.schema = 'guardian.combined-diagnostic.v10'
$report | Add-Member -NotePropertyName removedCapabilities -NotePropertyValue @('DEEP_MONITORING', 'NOTIFICATION_FACTS', 'GUARDIAN_PUSH_SELF_TEST')
$lean = Get-GuardianSurvivalSummary $report
Assert-Equal 2 $lean.retainedNaturalLowMemoryCount 'v10 keeps resource exit analysis'
Assert-Equal 3 $lean.removedCapabilities.Count 'v10 omitted collection explicitly identified'
$report.schema = 'guardian.combined-diagnostic.v11'
$report | Add-Member -NotePropertyName fcmRecoveryEpisodes -NotePropertyValue @{
    availability = 'AVAILABLE'; retainedEpisodeCount = 1; previewLimit = 8; previewTruncated = $false
    episodes = @(@{ sequence = 1; kind = 'SERVICE_ABSENT'; outcome = 'CONNECTION_AFTER_REQUEST';
        requestStatus = 'REQUESTED'; requestReason = 'COMMAND_ACCEPTED'; timeoutObserved = $false; lastBlock = 'NONE' })
}
$episodeReport = Get-GuardianSurvivalSummary $report
Assert-Equal 2 $episodeReport.retainedNaturalLowMemoryCount 'v11 keeps resource exit analysis'
Assert-Equal 1 $episodeReport.fcm.retainedObservationChanges 'v11 retains event counts from their original source'
Assert-Equal 0 $episodeReport.fcm.retainedRequests 'episode outcome cannot create an event request count'
Assert-Equal $false $episodeReport.fcm.continuousOperationProven 'connection after request is not continuous protection or delivery proof'
Assert-Equal 3 $episodeReport.removedCapabilities.Count 'v11 keeps removed capabilities'
Assert-Equal 1 $episodeReport.fcm.episodes.outcomeCountsInPreview.CONNECTION_AFTER_REQUEST 'v11 episode preview actually analyzed'
Assert-Equal $null $episodeReport.fcm.episodes.pendingRequestsInPreview 'v11 missing pending information is unknown'
Assert-Equal $false $episodeReport.fcm.episodes.causalRepairProven 'v11 temporal sequence not causal proof'
$report.schema = 'guardian.combined-diagnostic.v12'
$report | Add-Member -NotePropertyName sourceAvailability -NotePropertyValue @{ fcmRecoveryEpisodes = 'PARTIAL' }
function New-EpisodePreview($Sequence, $Outcome, $Status = $null, $Reason = $null, $Pending = $false, $Timeout = $false) {
    @{
        sequence = $Sequence; kind = 'SERVICE_ABSENT'; outcome = $Outcome; requestStatus = $Status
        requestReason = $Reason; requestPending = $Pending; timeoutObserved = $Timeout; lastBlock = 'NONE'
        requestAtEpochMillis = if ($Pending -or $null -ne $Status) { 100 } else { $null }
        requestElapsedMillis = if ($Pending -or $null -ne $Status) { 10 } else { $null }
        resultAtEpochMillis = if ($null -ne $Status) { 120 } else { $null }
        resultElapsedMillis = if ($null -ne $Status) { 12 } else { $null }
    }
}
$report.fcmRecoveryEpisodes = @{
    availability = 'AVAILABLE'; retainedEpisodeCount = 9; previewLimit = 8; previewTruncated = $true
    omittedFromPreview = 2; evictedEpisodes = 4; writeFailures = 1; droppedUpdates = 3
    episodes = @(
        (New-EpisodePreview 1 'CONNECTION_AFTER_REQUEST' 'REQUESTED' 'COMMAND_ACCEPTED'),
        (New-EpisodePreview 2 'CONNECTION_WITHOUT_REQUEST'),
        (New-EpisodePreview 3 'REQUEST_UNKNOWN' 'UNKNOWN' 'TIMED_OUT'),
        (New-EpisodePreview 4 'REQUEST_NOT_SENT' 'NOT_SENT' 'AUTHORITY_LOST'),
        (New-EpisodePreview 5 'COVERAGE_GAP' 'REQUESTED' 'COMMAND_ACCEPTED' -Timeout $true),
        (New-EpisodePreview 6 'COVERAGE_GAP' -Pending $true),
        (New-EpisodePreview 7 'OBSERVING' -Pending $true -Timeout $true)
    )
}
$latest = Get-GuardianSurvivalSummary $report
Assert-Equal 9 $latest.fcm.episodes.retainedEpisodeCount 'retained private count is separate from preview'
Assert-Equal 7 $latest.fcm.episodes.previewEpisodeCount 'actual preview count'
Assert-Equal 2 $latest.fcm.episodes.omittedFromPreview 'omitted count'
Assert-Equal 4 $latest.fcm.episodes.evictedEpisodes 'evictions retained'
Assert-Equal 3 $latest.fcm.episodes.droppedUpdates 'drop count retained'
Assert-Equal 1 $latest.fcm.episodes.writeFailures 'write failures retained'
Assert-Equal 'PARTIAL' $latest.fcm.episodes.reportSourceAvailability 'source quality retained'
foreach ($outcome in @('OBSERVING','CONNECTION_AFTER_REQUEST','CONNECTION_WITHOUT_REQUEST','REQUEST_NOT_SENT','REQUEST_UNKNOWN')) {
    Assert-Equal 1 $latest.fcm.episodes.outcomeCountsInPreview.$outcome "v12 categorized $outcome"
}
Assert-Equal 2 $latest.fcm.episodes.outcomeCountsInPreview.COVERAGE_GAP 'pending coverage gap is not observing or repaired'
Assert-Equal 2 $latest.fcm.episodes.pendingRequestsInPreview 'pending counts include unresolved gap'
Assert-Equal 2 $latest.fcm.episodes.timeoutObservedInPreview 'timeout remains an observed flag'
Assert-Equal 0 $latest.fcm.retainedRequests 'episode summary not inferred as ordinary event count'
Assert-Equal $false $latest.fcm.episodes.automaticReconnectionAccepted 'counts cannot grant acceptance'
Assert-Equal $false $latest.fcm.episodes.notificationDeliveryProven 'counts cannot prove notifications'
foreach ($source in @('NOT_LOADED','CORRUPT','UNAVAILABLE')) {
    $report.fcmRecoveryEpisodes.availability = $source
    $unknown = Get-GuardianSurvivalSummary $report
    Assert-Equal $null $unknown.fcm.episodes.previewEpisodeCount 'unavailable preview is unknown, not zero'
    Assert-Equal $null $unknown.fcm.episodes.outcomeCountsInPreview.CONNECTION_AFTER_REQUEST 'retained stale outcome ignored'
    Assert-Equal $null $unknown.fcm.episodes.pendingRequestsInPreview 'unavailable pending unknown'
}
$report.fcmRecoveryEpisodes.availability = 'AVAILABLE'
$report.sourceAvailability.fcmRecoveryEpisodes = 'UNAVAILABLE'
Assert-Equal $null (Get-GuardianSurvivalSummary $report).fcm.episodes.previewEpisodeCount 'report source unavailable overrides stale available payload'
$report.sourceAvailability.fcmRecoveryEpisodes = 'PARTIAL'
function Assert-RejectedEpisode([scriptblock]$Change, [string]$Name) {
    $saved = $report.fcmRecoveryEpisodes | ConvertTo-Json -Depth 10
    & $Change
    $failedValidation = $false
    try { Get-GuardianSurvivalSummary $report | Out-Null } catch { $failedValidation = $true }
    Assert-Equal $true $failedValidation $Name
    $report.fcmRecoveryEpisodes = $saved | ConvertFrom-Json
}
Assert-RejectedEpisode { $report.fcmRecoveryEpisodes.episodes += $report.fcmRecoveryEpisodes.episodes[0..1] } 'reject overbound preview'
Assert-RejectedEpisode { $report.fcmRecoveryEpisodes.episodes[0].outcome = 'connection_after_request' } 'enum case must be exact'
Assert-RejectedEpisode { $report.fcmRecoveryEpisodes.episodes[0].requestReason = 'TIMED_OUT' } 'reject false accepted pair'
Assert-RejectedEpisode { $report.fcmRecoveryEpisodes.episodes[6].requestPending = 'true' } 'pending must be boolean'
Assert-RejectedEpisode { $report.fcmRecoveryEpisodes.omittedFromPreview = 0 } 'reject hidden omission'
Assert-RejectedEpisode { $report.fcmRecoveryEpisodes.episodes[1].sequence = 1 } 'reject duplicate sequence'
Assert-RejectedEpisode { $report.fcmRecoveryEpisodes.episodes[6].resultAtEpochMillis = 150 } 'pending cannot have result time'
$mcs = New-EpisodePreview 1 'REQUEST_NOT_SENT' 'NOT_SENT' 'NOT_DISCONNECTED'
$mcs.kind = 'PRESENT_DISCONNECTED'
$mcs.requestOperation = 'PRESENT_DISCONNECTED_MCS'
$report.fcmRecoveryEpisodes = @{ availability = 'AVAILABLE'; retainedEpisodeCount = 1; episodes = @($mcs)
    previewLimit = 8; previewTruncated = $false; omittedFromPreview = 0; evictedEpisodes = 0; writeFailures = 0; droppedUpdates = 0 }
Assert-Equal 1 (Get-GuardianSurvivalSummary $report).fcm.episodes.outcomeCountsInPreview.REQUEST_NOT_SENT 'MCS rejected request retained'
Assert-RejectedEpisode { $report.fcmRecoveryEpisodes.episodes[0].requestOperation = 'SERVICE_ABSENT_SEND' } 'reject mixed operation and observed branch'
Assert-RejectedEpisode { $report.fcmRecoveryEpisodes.episodes[0].requestOperation = 'UNKNOWN_OPERATION' } 'reject unknown operation'
$report.fcmRecoveryEpisodes = @{ availability = 'AVAILABLE'; retainedEpisodeCount = 0; episodes = @()
    previewLimit = 8; previewTruncated = $false; omittedFromPreview = 0; evictedEpisodes = 0; writeFailures = 0; droppedUpdates = 0 }
$emptyEpisodes = Get-GuardianSurvivalSummary $report
Assert-Equal 0 $emptyEpisodes.fcm.episodes.previewEpisodeCount 'available empty is a known zero'
Assert-Equal 0 $emptyEpisodes.fcm.episodes.pendingRequestsInPreview 'available empty pending is zero'
$report.schema = 'guardian.combined-diagnostic.v13'
function New-DaemonSummary {
    @{ sampleCount=2; firstSampleAtEpochMillis=1000; lastSampleAtEpochMillis=2000
        cpuDeltaMillis=20; cpuMeasuredElapsedMillis=1000; cpuSingleCorePercent=2.0
        rssMinKiB=100; rssMaxKiB=120; rssDeltaKiB=-20; threadsMax=8; invalidClockIntervals=0; gapIntervals=0 }
}
function New-DaemonSession([string]$Id = '000000000000000000000001') {
    @{ session=$Id; boot='000000000000000000000002'; processId=42; versionCode=85
        startedAtEpochMillis=1000; startedElapsedMillis=100; lastCheckpointAtEpochMillis=2000
        lastCheckpointElapsedMillis=1100; state='READY'; normalEndedAtEpochMillis=$null
        normalEndedElapsedMillis=$null; stoppedCheckedAtEpochMillis=1900; fcmCheckedAtEpochMillis=1950
        maxPollGapMillis=30000; summary=(New-DaemonSummary) }
}
$report | Add-Member -NotePropertyName daemonDiagnostics -NotePropertyValue @{
    availability='AVAILABLE'; collectionScope='GUARDIAN_PRIVILEGED_DAEMON_HISTORY'
    generatedAtEpochMillis=2100; currentSession='000000000000000000000001'; generation=3
    retainedSessionCount=1; retainedSampleCount=2; evictedSessions=0; evictedSamples=0; evictedEvents=0
    skippedSamples=1; failedOperations=0; unknownIntervals=1; omittedTimeBuckets=0
    sessions=@((New-DaemonSession)); timeBuckets=@(); recentSamples=@(
        @{ session='000000000000000000000001'; atEpochMillis=2000; elapsedMillis=1100; cpuTimeMillis=50
           rssKiB=100; threads=8; batteryPercent=$null; chargingStatus='UNKNOWN'; interactive=$false; deviceIdle=$null })
}
$daemon = Get-GuardianSurvivalSummary $report
Assert-Equal 'GUARDIAN_APP_PROCESS_HISTORY' $daemon.historicalExitCollectionScope 'old app exits have explicit scope'
Assert-Equal 2 $daemon.retainedNaturalLowMemoryCount 'v13 keeps two app historical exits'
Assert-Equal 'READY' $daemon.daemonEvidence.sessions[0].state 'app exits do not kill current daemon history'
Assert-Equal 'GUARDIAN_PRIVILEGED_DAEMON_HISTORY' $daemon.daemonEvidence.collectionScope 'daemon independent scope'
Assert-Equal $null $daemon.daemonEvidence.daemonTerminationCount 'unknown intervals do not become kill count'
Assert-Equal 1 $daemon.daemonEvidence.unknownIntervals 'unknown interval retained without attribution'
Assert-Equal 2.0 $daemon.daemonEvidence.sessions[0].summary.cpuSingleCorePercent 'daemon CPU summary retained'
Assert-Equal -20 $daemon.daemonEvidence.sessions[0].summary.rssDeltaKiB 'negative RSS change is valid'
Assert-Equal $null $daemon.daemonEvidence.recentSamples[0].batteryPercent 'missing device battery is unknown'
Assert-Equal 'START_AND_PENDING_AND_RETURNED_RESULT' $daemon.fcm.episodes.requestTimingScope 'v13 inherits v12 timing semantics'
foreach ($source in @('NOT_COLLECTED','INITIALIZING','UNAVAILABLE','CORRUPT','LOCKED')) {
    $report.daemonDiagnostics.availability=$source
    $unknownDaemon=Get-GuardianSurvivalSummary $report
    Assert-Equal $null $unknownDaemon.daemonEvidence.retainedSessionCount 'unavailable daemon count is not zero'
    Assert-Equal $null $unknownDaemon.daemonEvidence.evictedSamples 'unavailable daemon evictions are unknown'
    Assert-Equal 0 $unknownDaemon.daemonEvidence.sessions.Count 'unavailable stale daemon sessions omitted'
    Assert-Equal 0 $unknownDaemon.daemonEvidence.timeBuckets.Count 'unavailable daemon buckets omitted'
    Assert-Equal 0 $unknownDaemon.daemonEvidence.recentSamples.Count 'unavailable daemon sample preview omitted'
    Assert-Equal 2 $unknownDaemon.retainedNaturalLowMemoryCount 'daemon unavailable does not erase app history'
}
$report.daemonDiagnostics.availability='AVAILABLE'
$report.daemonDiagnostics.sessions[0].state='UNKNOWN_END'
Assert-Equal $null (Get-GuardianSurvivalSummary $report).daemonEvidence.daemonTerminationCount 'missing normal end cannot identify a kill'
$report.daemonDiagnostics.sessions[0].state='READY'
function Assert-RejectedDaemon([scriptblock]$Change, [string]$Name) {
    $saved = $report.daemonDiagnostics | ConvertTo-Json -Depth 20
    & $Change
    $failedValidation=$false
    try { Get-GuardianSurvivalSummary $report | Out-Null } catch { $failedValidation=$true }
    Assert-Equal $true $failedValidation $Name
    $report.daemonDiagnostics = $saved | ConvertFrom-Json
}
Assert-RejectedDaemon { $report.daemonDiagnostics.sessions=@(1..33 | ForEach-Object { New-DaemonSession ('{0:x24}' -f $_) }) } 'reject 33 sessions'
Assert-RejectedDaemon { $report.daemonDiagnostics.timeBuckets=@(1..169 | ForEach-Object { @{session='000000000000000000000001'; hourEpochMillis=$_; summary=(New-DaemonSummary)} }) } 'reject 169 buckets'
Assert-RejectedDaemon { $report.daemonDiagnostics.recentSamples=@(1..17 | ForEach-Object { $report.daemonDiagnostics.recentSamples[0] }) } 'reject 17 recent samples'
Assert-RejectedDaemon { $report.daemonDiagnostics.collectionScope='GUARDIAN_APP_PROCESS_HISTORY' } 'reject merged app and daemon scopes'
Assert-RejectedDaemon { $report.daemonDiagnostics.sessions[0].state='NORMAL_END' } 'normal end requires observed end times'
Assert-RejectedDaemon { $report.daemonDiagnostics.sessions[0].lastCheckpointElapsedMillis=99 } 'reject backwards monotonic checkpoint'
Assert-RejectedDaemon { $report.daemonDiagnostics.recentSamples[0].session='000000000000000000000999' } 'reject unrelated daemon sample'
Assert-RejectedDaemon { $report.daemonDiagnostics.sessions[0].boot='raw-boot-uuid' } 'reject unhashed daemon references'
Assert-RejectedDaemon { $report.daemonDiagnostics.sessions[0].summary.cpuDeltaMillis=-1 } 'reject negative daemon CPU delta'
Assert-RejectedDaemon { $report.daemonDiagnostics.timeBuckets=@(@{session='000000000000000000000001'; hourEpochMillis=0; sampleCount=0}) } 'reject obsolete flat hourly summary'
Assert-RejectedDaemon { $report.daemonDiagnostics.retainedSessionCount=0 } 'reject false daemon retained count'
Assert-RejectedDaemon { $report.daemonDiagnostics.sessions=$null } 'missing sessions cannot be empty successful history'
Assert-RejectedDaemon { $report.daemonDiagnostics.availability='available' } 'daemon availability case exact'
Assert-RejectedEpisode { $report.fcmRecoveryEpisodes.episodes=@((New-EpisodePreview 1 'OBSERVING')); $report.fcmRecoveryEpisodes.episodes[0].requestPending=$null; $report.fcmRecoveryEpisodes.retainedEpisodeCount=1 } 'v13 still requires explicit pending state'
# Exercise the documented maxima together, preserving the independent App history.
$report.daemonDiagnostics.sessions=@(1..32 | ForEach-Object { New-DaemonSession ('{0:x24}' -f $_) })
$report.daemonDiagnostics.currentSession='000000000000000000000001'
$report.daemonDiagnostics.retainedSessionCount=32
$report.daemonDiagnostics.retainedSampleCount=2016
$report.daemonDiagnostics.timeBuckets=@(0..167 | ForEach-Object {
    @{session='000000000000000000000001'; hourEpochMillis=[long]$_*3600000; summary=(New-DaemonSummary)}
})
$report.daemonDiagnostics.recentSamples=@(1..16 | ForEach-Object { @{session='000000000000000000000001'; atEpochMillis=2000; elapsedMillis=1100} })
$boundedDaemon=Get-GuardianSurvivalSummary $report
Assert-Equal 32 $boundedDaemon.daemonEvidence.sessions.Count '32 daemon sessions accepted'
Assert-Equal 168 $boundedDaemon.daemonEvidence.timeBuckets.Count '168 hourly buckets accepted'
Assert-Equal 2.0 $boundedDaemon.daemonEvidence.timeBuckets[0].summary.cpuSingleCorePercent 'mapper nested hourly summary retained'
Assert-Equal 16 $boundedDaemon.daemonEvidence.recentSamples.Count '16 recent samples accepted'
Assert-Equal 2016 $boundedDaemon.daemonEvidence.retainedSampleCount 'retained count independent of recent preview'
$taskReportFile=[System.IO.Path]::GetTempFileName()
try {
    $report | Add-Member -NotePropertyName testPadding -NotePropertyValue ('x'*131072)
    $report | ConvertTo-Json -Depth 24 | Set-Content -LiteralPath $taskReportFile -Encoding UTF8
    $fromFile=& (Join-Path $PSScriptRoot 'analyze_survival_reports.ps1') -ReportPath @($taskReportFile) | ConvertFrom-Json
    Assert-Equal 32 $fromFile[0].daemonEvidence.retainedSessionCount 'normal report over 128KiB within 1MiB accepted'
    $report.testPadding='x'*1048576
    $report | ConvertTo-Json -Depth 24 | Set-Content -LiteralPath $taskReportFile -Encoding UTF8
    $tooLarge=$false
    try { & (Join-Path $PSScriptRoot 'analyze_survival_reports.ps1') -ReportPath @($taskReportFile) | Out-Null } catch { $tooLarge=$true }
    Assert-Equal $true $tooLarge 'report over 1MiB refused'
} finally { Remove-Item -LiteralPath $taskReportFile -ErrorAction SilentlyContinue }
$report.schema = 'bad'
$rejected = $false
try { Get-GuardianSurvivalSummary $report | Out-Null } catch { $rejected = $true }
Assert-Equal $true $rejected 'unknown schema refused'
Write-Output 'SURVIVAL_ANALYSIS_TESTS_PASS'
