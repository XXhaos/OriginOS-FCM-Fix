[CmdletBinding()]
param(
    [string[]]$ReportPath,
    [long[]]$KnownArtificialExitAtEpochMillis = @(),
    [switch]$LibraryOnly
)

# Episode totals describe the exported preview only, never the whole private ring or delivery.
function Get-GuardianFcmEpisodeSummary {
    param([Parameter(Mandatory)]$Report)
    $section = $Report.fcmRecoveryEpisodes
    $availability = if ($null -eq $section) { 'MISSING' } else { $section.availability }
    if ($availability -cnotin @('MISSING','NOT_LOADED','AVAILABLE','CORRUPT','UNAVAILABLE')) {
        throw 'Unknown episode source availability'
    }
    function Read-EpisodeCounter($Value, [long]$Maximum = [long]::MaxValue) {
        if ($null -eq $Value) { return $null }
        if ($Value -isnot [ValueType] -or $Value -is [bool] -or [decimal]$Value -lt 0 -or
            [decimal]$Value -gt $Maximum -or [decimal]$Value -ne [decimal][long]$Value) {
            throw 'Invalid episode scalar count'
        }
        [long]$Value
    }
    $outcomes = @('OBSERVING','CONNECTION_AFTER_REQUEST','CONNECTION_WITHOUT_REQUEST',
        'REQUEST_NOT_SENT','REQUEST_UNKNOWN','COVERAGE_GAP')
    $counts = [ordered]@{}
    foreach ($outcome in $outcomes) { $counts[$outcome] = $null }
    $retained = $null; $omitted = $null; $previewCount = $null; $pending = $null; $timeout = $null
    $requestTiming = if ($Report.schema -in @('guardian.combined-diagnostic.v12','guardian.combined-diagnostic.v13')) { 'START_AND_PENDING_AND_RETURNED_RESULT' } else { 'LEGACY_PENDING_AND_RETURN_TIME_UNKNOWN' }
    $evicted = Read-EpisodeCounter $section.evictedEpisodes
    $failed = Read-EpisodeCounter $section.writeFailures
    $dropped = Read-EpisodeCounter $section.droppedUpdates
    if ($availability -eq 'AVAILABLE' -and $Report.sourceAvailability.fcmRecoveryEpisodes -notin @('LOADING','CORRUPT','UNAVAILABLE','MISSING')) {
        $retained = Read-EpisodeCounter $section.retainedEpisodeCount 32
        if ($null -eq $retained -or $null -eq $section.episodes) { throw 'Missing available episode preview' }
        $episodes = @($section.episodes)
        if ($episodes.Count -gt 8 -or $episodes.Count -gt $retained) { throw 'Episode preview limit exceeded' }
        $previewCount = $episodes.Count
        $omitted = $retained - $previewCount
        if ($null -ne $section.omittedFromPreview -and
            (Read-EpisodeCounter $section.omittedFromPreview 32) -ne $omitted) { throw 'Episode omission count mismatch' }
        if ($null -ne $section.previewLimit -and (Read-EpisodeCounter $section.previewLimit 8) -ne 8) {
            throw 'Unknown episode preview limit'
        }
        if ($null -ne $section.previewTruncated -and ($section.previewTruncated -isnot [bool] -or
            $section.previewTruncated -ne ($omitted -gt 0))) { throw 'Episode preview truncation mismatch' }
        foreach ($outcome in $outcomes) { $counts[$outcome] = 0 }
        $timeout = 0
        if ($Report.schema -in @('guardian.combined-diagnostic.v12','guardian.combined-diagnostic.v13')) { $pending = 0 }
        $previous = 0L
        foreach ($episode in $episodes) {
            $sequence = Read-EpisodeCounter $episode.sequence
            if ($null -eq $sequence -or $sequence -le $previous) { throw 'Invalid episode sequence order' }
            $previous = $sequence
            if ($episode.outcome -cnotin $outcomes -or $episode.kind -cnotin @('SERVICE_ABSENT','PRESENT_DISCONNECTED') -or
                $episode.timeoutObserved -isnot [bool] -or
                $episode.lastBlock -cnotin @('NONE','PRESENT_DISCONNECTED_NOT_ADMITTED','NOT_QUALIFIED','INELIGIBLE','OBSERVATION_UNKNOWN')) {
                throw 'Invalid episode state'
            }
            $status = $episode.requestStatus; $reason = $episode.requestReason
            if (($null -eq $status) -ne ($null -eq $reason) -or
                ($null -ne $status -and ($status -cnotin @('NOT_SENT','REQUESTED','UNKNOWN') -or
                    $reason -cnotin @('COMMAND_ACCEPTED','NOT_ABSENT','IDENTITY_CHANGED','TARGET_UNQUALIFIED','AUTHORITY_LOST',
                        'BUSY','TIMED_OUT','CANCELLED','COMMAND_FAILED','BRIDGE_UNAVAILABLE','INVALID_REQUEST','NOT_DISCONNECTED') -or
                    ($status -eq 'REQUESTED') -ne ($reason -eq 'COMMAND_ACCEPTED')))) { throw 'Invalid episode request result' }
            if (($episode.outcome -eq 'CONNECTION_AFTER_REQUEST' -and $status -ne 'REQUESTED') -or
                ($episode.outcome -eq 'CONNECTION_WITHOUT_REQUEST' -and $null -ne $status) -or
                ($episode.outcome -eq 'REQUEST_NOT_SENT' -and $status -ne 'NOT_SENT') -or
                ($episode.outcome -eq 'REQUEST_UNKNOWN' -and $status -ne 'UNKNOWN')) { throw 'Invalid episode outcome/result relation' }
            if ($null -ne $episode.requestOperation -and
                ($episode.requestOperation -cnotin @('SERVICE_ABSENT_SEND','PRESENT_DISCONNECTED_MCS') -or
                 $null -eq $episode.requestAtEpochMillis -or
                 (($episode.requestOperation -eq 'SERVICE_ABSENT_SEND') -ne ($episode.kind -eq 'SERVICE_ABSENT')))) {
                throw 'Invalid episode request operation'
            }
            if ($Report.schema -in @('guardian.combined-diagnostic.v12','guardian.combined-diagnostic.v13')) {
                if ($episode.requestPending -isnot [bool]) { throw 'Missing episode pending state' }
                $requestAt = Read-EpisodeCounter $episode.requestAtEpochMillis
                $requestElapsed = Read-EpisodeCounter $episode.requestElapsedMillis
                $resultAt = Read-EpisodeCounter $episode.resultAtEpochMillis
                $resultElapsed = Read-EpisodeCounter $episode.resultElapsedMillis
                if (($null -eq $requestAt) -ne ($null -eq $requestElapsed) -or
                    ($null -eq $resultAt) -ne ($null -eq $resultElapsed) -or
                    ($null -ne $requestAt -and $requestAt -eq 0) -or ($null -ne $resultAt -and $resultAt -eq 0) -or
                    ($episode.requestPending -and ($null -eq $requestAt -or $null -ne $status -or $null -ne $resultAt)) -or
                    (-not $episode.requestPending -and (($null -eq $requestAt) -ne ($null -eq $status))) -or
                    ($null -ne $resultAt -and ($null -eq $status -or $null -eq $requestAt -or $resultElapsed -lt $requestElapsed)) -or
                    ($episode.outcome -eq 'CONNECTION_WITHOUT_REQUEST' -and $null -ne $requestAt)) {
                    throw 'Invalid episode pending/result time relation'
                }
                if ($episode.requestPending) { $pending++ }
            }
            $counts[$episode.outcome]++
            if ($episode.timeoutObserved) { $timeout++ }
        }
    }
    [pscustomobject]@{
        sourceAvailability = $availability
        reportSourceAvailability = $Report.sourceAvailability.fcmRecoveryEpisodes
        retainedEpisodeCount = $retained
        previewEpisodeCount = $previewCount
        omittedFromPreview = $omitted
        evictedEpisodes = $evicted
        writeFailures = $failed
        droppedUpdates = $dropped
        outcomeCountsInPreview = [pscustomobject]$counts
        pendingRequestsInPreview = $pending
        timeoutObservedInPreview = $timeout
        requestTimingScope = $requestTiming
        evidenceScope = 'EXPORTED_BOUNDED_PREVIEW_ONLY_NOT_FULL_RING_OR_DELIVERY'
        automaticReconnectionAccepted = $false
        causalRepairProven = $false
        notificationDeliveryProven = $false
    }
}

# Daemon history is independent of the ordinary App's historical exits and resource samples.
function Get-GuardianDaemonEvidence {
    param([Parameter(Mandatory)]$Report)
    $section = if ($Report.schema -eq 'guardian.combined-diagnostic.v13') { $Report.daemonDiagnostics } else { $null }
    $availability = if ($null -eq $section) { 'MISSING' } else { $section.availability }
    if ($availability -cnotin @('MISSING','NOT_COLLECTED','INITIALIZING','AVAILABLE','UNAVAILABLE','CORRUPT','LOCKED')) {
        throw 'Unknown daemon source availability'
    }
    function Read-DaemonNumber($Value, [bool]$Required = $false, [decimal]$Minimum = 0,
            [decimal]$Maximum = [long]::MaxValue, [bool]$Integer = $true) {
        if ($null -eq $Value) {
            if ($Required) { throw 'Missing daemon scalar' }
            return $null
        }
        if ($Value -isnot [ValueType] -or $Value -is [bool]) { throw 'Invalid daemon scalar' }
        try { $number = [decimal]$Value } catch { throw 'Invalid daemon scalar' }
        if ($number -lt $Minimum -or $number -gt $Maximum -or ($Integer -and [decimal]::Truncate($number) -ne $number)) {
            throw 'Invalid daemon scalar range'
        }
        if ($Integer) { [long]$number } else { [double]$number }
    }
    function Read-DaemonText($Value, [bool]$Required = $true) {
        if ($null -eq $Value -and -not $Required) { return $null }
        if ($Value -isnot [string] -or $Value -cnotmatch '^[a-f0-9]{24}$') {
            throw 'Invalid daemon identity'
        }
        $Value
    }
    function Read-DaemonSummary($Value) {
        if ($null -eq $Value) { throw 'Missing daemon summary' }
        $result = [ordered]@{ sampleCount = Read-DaemonNumber $Value.sampleCount $true 0 2016 }
        foreach ($name in @('firstSampleAtEpochMillis','lastSampleAtEpochMillis','cpuDeltaMillis',
                'cpuMeasuredElapsedMillis','rssMinKiB','rssMaxKiB','threadsMax','invalidClockIntervals','gapIntervals')) {
            $result[$name] = Read-DaemonNumber $Value.$name
        }
        $result.rssDeltaKiB = Read-DaemonNumber $Value.rssDeltaKiB $false ([long]::MinValue)
        $result.cpuSingleCorePercent = Read-DaemonNumber $Value.cpuSingleCorePercent $false 0 ([long]::MaxValue) $false
        [pscustomobject]$result
    }
    $output = [ordered]@{
        availability = $availability
        collectionScope = 'GUARDIAN_PRIVILEGED_DAEMON_HISTORY'
        generatedAtEpochMillis = $null; currentSession = $null; generation = $null
        retainedSessionCount = $null; retainedSampleCount = $null
        evictedSessions = $null; evictedSamples = $null; evictedEvents = $null
        skippedSamples = $null; failedOperations = $null; unknownIntervals = $null
        sessions = @(); timeBuckets = @(); omittedTimeBuckets = $null; recentSamples = @()
        daemonTerminationCount = $null
        terminationAttribution = 'UNKNOWN_END_OR_MISSING_NORMAL_END_DOES_NOT_PROVE_KILL'
        cpuScope = 'DAEMON_PROCESS_SINGLE_CORE_PERCENT_NOT_SYSTEM_CPU_OR_BATTERY_DRAIN'
        continuousProtectionProven = $false
    }
    if ($null -eq $section) { return [pscustomobject]$output }
    if ($section.collectionScope -cne 'GUARDIAN_PRIVILEGED_DAEMON_HISTORY') { throw 'Invalid daemon collection scope' }
    # Validate container bounds even when unavailable, but never count stale payloads as observations.
    $sessions = @(if ($null -ne $section.sessions) { $section.sessions })
    $buckets = @(if ($null -ne $section.timeBuckets) { $section.timeBuckets })
    $samples = @(if ($null -ne $section.recentSamples) { $section.recentSamples })
    if ($sessions.Count -gt 32 -or $buckets.Count -gt 168 -or $samples.Count -gt 16) { throw 'Daemon preview limit exceeded' }
    if ($availability -ne 'AVAILABLE') { return [pscustomobject]$output }
    if ($null -eq $section.sessions -or $null -eq $section.timeBuckets -or $null -eq $section.recentSamples) {
        throw 'Missing available daemon history'
    }
    $output.generatedAtEpochMillis = Read-DaemonNumber $section.generatedAtEpochMillis $true 1
    $output.currentSession = Read-DaemonText $section.currentSession $false
    $output.generation = Read-DaemonNumber $section.generation $true
    $output.retainedSessionCount = Read-DaemonNumber $section.retainedSessionCount $true 0 32
    $output.retainedSampleCount = Read-DaemonNumber $section.retainedSampleCount $true 0 2016
    if ($output.retainedSessionCount -ne $sessions.Count -or $samples.Count -gt $output.retainedSampleCount) {
        throw 'Daemon retained count mismatch'
    }
    foreach ($name in @('evictedSessions','evictedSamples','evictedEvents','skippedSamples','failedOperations','unknownIntervals','omittedTimeBuckets')) {
        $output[$name] = Read-DaemonNumber $section.$name $true
    }
    $knownSessions = @{}
    $output.sessions = @(foreach ($session in $sessions) {
        $id = Read-DaemonText $session.session
        if ($knownSessions.ContainsKey($id)) { throw 'Duplicate daemon session' }
        $knownSessions[$id] = $true
        if ($session.state -cnotin @('BEGIN','READY','OFF','WAITING','CLOSING','NORMAL_END','UNKNOWN_END')) { throw 'Invalid daemon state' }
        $entry = [ordered]@{ session = $id; boot = Read-DaemonText $session.boot
            processId = Read-DaemonNumber $session.processId $true 1 ([int]::MaxValue)
            versionCode = Read-DaemonNumber $session.versionCode $true 1
            startedAtEpochMillis = Read-DaemonNumber $session.startedAtEpochMillis $true 1
            startedElapsedMillis = Read-DaemonNumber $session.startedElapsedMillis $true
            lastCheckpointAtEpochMillis = Read-DaemonNumber $session.lastCheckpointAtEpochMillis $true 1
            lastCheckpointElapsedMillis = Read-DaemonNumber $session.lastCheckpointElapsedMillis $true
            state = $session.state
            normalEndedAtEpochMillis = Read-DaemonNumber $session.normalEndedAtEpochMillis $false 1
            normalEndedElapsedMillis = Read-DaemonNumber $session.normalEndedElapsedMillis
            stoppedCheckedAtEpochMillis = Read-DaemonNumber $session.stoppedCheckedAtEpochMillis $false 1
            fcmCheckedAtEpochMillis = Read-DaemonNumber $session.fcmCheckedAtEpochMillis $false 1
            maxPollGapMillis = Read-DaemonNumber $session.maxPollGapMillis $true
            summary = Read-DaemonSummary $session.summary }
        if ($entry.lastCheckpointElapsedMillis -lt $entry.startedElapsedMillis -or
            ($null -eq $entry.normalEndedAtEpochMillis) -ne ($null -eq $entry.normalEndedElapsedMillis) -or
            ($session.state -eq 'NORMAL_END') -ne ($null -ne $entry.normalEndedAtEpochMillis) -or
            ($null -ne $entry.normalEndedElapsedMillis -and $entry.normalEndedElapsedMillis -lt $entry.startedElapsedMillis)) {
            throw 'Invalid daemon monotonic/end relation'
        }
        [pscustomobject]$entry
    })
    if ($null -ne $output.currentSession -and -not $knownSessions.ContainsKey($output.currentSession)) { throw 'Unknown current daemon session' }
    $bucketKeys = @{}
    $output.timeBuckets = @(foreach ($bucket in $buckets) {
        $id = Read-DaemonText $bucket.session
        $hour = Read-DaemonNumber $bucket.hourEpochMillis $true
        $key = '{0}:{1}' -f $id, $hour
        if (-not $knownSessions.ContainsKey($id) -or $bucketKeys.ContainsKey($key)) { throw 'Invalid daemon bucket identity' }
        $bucketKeys[$key] = $true
        $entry = [ordered]@{ session = $id; hourEpochMillis = $hour; summary = Read-DaemonSummary $bucket.summary }
        [pscustomobject]$entry
    })
    $output.recentSamples = @(foreach ($sample in $samples) {
        $id = Read-DaemonText $sample.session
        if (-not $knownSessions.ContainsKey($id)) { throw 'Unknown daemon sample session' }
        $entry = [ordered]@{ session = $id
            atEpochMillis = Read-DaemonNumber $sample.atEpochMillis $true 1
            elapsedMillis = Read-DaemonNumber $sample.elapsedMillis $true }
        foreach ($name in @('cpuTimeMillis','rssKiB','heapUsedBytes','heapMaxBytes','nativeAllocatedBytes','threads')) {
            $entry[$name] = Read-DaemonNumber $sample.$name
        }
        $entry.batteryPercent = Read-DaemonNumber $sample.batteryPercent $false 0 100
        if ($null -ne $sample.chargingStatus -and $sample.chargingStatus -cnotin @('CHARGING','FULL','DISCHARGING','NOT_CHARGING','UNKNOWN')) {
            throw 'Invalid daemon charging status'
        }
        $entry.chargingStatus = $sample.chargingStatus
        foreach ($name in @('interactive','deviceIdle')) {
            if ($null -ne $sample.$name -and $sample.$name -isnot [bool]) { throw 'Invalid daemon device state' }
            $entry[$name] = $sample.$name
        }
        [pscustomobject]$entry
    })
    [pscustomobject]$output
}

# Read-only offline analysis. Export version is NOT the version of historical exits.
function Get-GuardianSurvivalSummary {
    param([Parameter(Mandatory)]$Report, [long[]]$ArtificialExits = @())
    if ($Report.schema -notin @('guardian.combined-diagnostic.v8', 'guardian.combined-diagnostic.v9', 'guardian.combined-diagnostic.v10', 'guardian.combined-diagnostic.v11', 'guardian.combined-diagnostic.v12', 'guardian.combined-diagnostic.v13')) {
        throw 'Unsupported report schema'
    }
    $events = @($Report.protectionLifecycle.events)
    if ($events.Count -gt 64) { throw 'Lifecycle event limit exceeded' }
    $unique = @{}
    foreach ($event in $events) {
        if ($event.event -ne 'SYSTEM_PROCESS_EXIT') { continue }
        if ([long]$event.atEpochMillis -le 0 -or [int]$event.guardianProcessId -le 0) { throw 'Invalid exit identity/time' }
        $key = '{0}:{1}:{2}:{3}' -f $event.atEpochMillis, $event.guardianProcessId, $event.systemExitReason, $event.systemExitStatus
        $unique[$key] = $event
    }
    # v9-v13 retain ordinary App exits independently of noisy tile/lifecycle events.
    if ($Report.schema -in @('guardian.combined-diagnostic.v9', 'guardian.combined-diagnostic.v10', 'guardian.combined-diagnostic.v11', 'guardian.combined-diagnostic.v12', 'guardian.combined-diagnostic.v13') -and $Report.protectionResources.availability -eq 'AVAILABLE') {
        $resourceExits = @($Report.protectionResources.exitAnalysis)
        if ($resourceExits.Count -gt 32) { throw 'Resource exit limit exceeded' }
        foreach ($exit in $resourceExits) {
            if ([long]$exit.exitAtEpochMillis -le 0 -or [int]$exit.processId -le 0) { throw 'Invalid resource exit identity/time' }
            $key = '{0}:{1}:{2}:{3}' -f $exit.exitAtEpochMillis, $exit.processId, $exit.systemExitReason, $exit.systemExitStatus
            $unique[$key] = [pscustomobject]@{
                event = 'SYSTEM_PROCESS_EXIT'; atEpochMillis = [long]$exit.exitAtEpochMillis
                guardianProcessId = [int]$exit.processId; systemExitReason = [int]$exit.systemExitReason
                systemExitStatus = [int]$exit.systemExitStatus
            }
        }
    }
    $exits = @($unique.Values | Sort-Object atEpochMillis, guardianProcessId)
    $natural = @($exits | Where-Object { $_.systemExitReason -eq 3 -and $_.atEpochMillis -notin $ArtificialExits })
    $gaps = @()
    for ($index = 1; $index -lt $natural.Count; $index++) {
        $gap = ([long]$natural[$index].atEpochMillis - [long]$natural[$index - 1].atEpochMillis) / 1000.0
        if ($gap -ge 0) { $gaps += $gap }
    }
    $median = $null
    $mean = $null
    if ($gaps.Count -gt 0) {
        $sorted = @($gaps | Sort-Object)
        $middle = [int][math]::Floor($sorted.Count / 2)
        $median = if ($sorted.Count % 2 -eq 0) { ($sorted[$middle - 1] + $sorted[$middle]) / 2.0 } else { $sorted[$middle] }
        $mean = ($gaps | Measure-Object -Average).Average
    }
    $restartEvidence = @()
    foreach ($exit in $exits) {
        $nextExit = $exits | Where-Object { $_.atEpochMillis -gt $exit.atEpochMillis } | Select-Object -First 1
        $upperBound = if ($nextExit) { [long]$nextExit.atEpochMillis } else { [long]$Report.capturedAtEpochMillis }
        $start = $events | Where-Object {
            $_.event -eq 'PROCESS_STARTED' -and $_.atEpochMillis -gt $exit.atEpochMillis -and
            $_.atEpochMillis -le $upperBound -and $_.guardianProcessId -ne $exit.guardianProcessId
        } | Sort-Object atEpochMillis | Select-Object -First 1
        $running = if ($start) {
            $events | Where-Object {
                $_.event -eq 'SERVICE_RUNNING' -and $_.guardianProcessId -eq $start.guardianProcessId -and
                $_.atEpochMillis -ge $start.atEpochMillis -and $_.atEpochMillis -le $upperBound
            } | Sort-Object atEpochMillis | Select-Object -First 1
        } else { $null }
        $restartEvidence += [pscustomobject]@{
            exitAtEpochMillis = [long]$exit.atEpochMillis
            knownArtificial = $exit.atEpochMillis -in $ArtificialExits
            exitReason = [int]$exit.systemExitReason
            firstRetainedProcessStartDelaySeconds = if ($start) { ([long]$start.atEpochMillis - [long]$exit.atEpochMillis) / 1000.0 } else { $null }
            firstRetainedServiceRunningDelaySeconds = if ($running) { ([long]$running.atEpochMillis - [long]$exit.atEpochMillis) / 1000.0 } else { $null }
        }
    }
    $fcm = @($Report.fcmRecoveryTrial.events)
    if ($fcm.Count -gt 64) { throw 'FCM event limit exceeded' }
    [pscustomobject]@{
        schema = 'guardian.survival-analysis.v1'
        reportSchema = $Report.schema
        reportCapturedAtEpochMillis = [long]$Report.capturedAtEpochMillis
        reportVersionCode = $Report.environment.guardianVersionCode
        removedCapabilities = @($Report.removedCapabilities)
        historicalExitVersionAttribution = 'UNKNOWN_UNLESS_RESOURCE_LIFETIME_MATCHED'
        historicalExitCollectionScope = 'GUARDIAN_APP_PROCESS_HISTORY'
        daemonEvidence = Get-GuardianDaemonEvidence $Report
        lifecycleAvailability = $Report.protectionLifecycle.availability
        lifecycleDroppedEvents = $Report.protectionLifecycle.droppedEvents
        retainedExitCount = $exits.Count
        retainedNaturalLowMemoryCount = $natural.Count
        retainedCrashOrAnrCount = @($exits | Where-Object { $_.systemExitReason -in @(4, 5, 6) -and $_.atEpochMillis -notin $ArtificialExits }).Count
        retainedPackageUpdateCount = @($exits | Where-Object systemExitReason -eq 16).Count
        retainedUnattributedSignalCount = @($exits | Where-Object { $_.systemExitReason -eq 2 -and $_.atEpochMillis -notin $ArtificialExits }).Count
        retainedKnownArtificialCount = @($exits | Where-Object { $_.atEpochMillis -in $ArtificialExits }).Count
        retainedAdjacentLowMemoryGapSeconds = $gaps
        retainedMedianGapSeconds = $median
        retainedMeanGapSeconds = $mean
        reconstruction = $restartEvidence
        fcm = [pscustomobject]@{
            configuredAtExport = $Report.fcmProtection.configured
            maintenanceAtExport = $Report.fcmProtection.maintenance
            recoveryReadyAtExport = $Report.fcmProtection.recoveryReady
            retainedObservationChanges = @($fcm | Where-Object action -eq 'OBSERVATION').Count
            retainedRequests = @($fcm | Where-Object action -eq 'REQUEST_RESULT').Count
            retainedReadbacks = @($fcm | Where-Object action -eq 'READBACK').Count
            resourceEvidence = $Report.protectionResources
            episodes = Get-GuardianFcmEpisodeSummary $Report
            continuousOperationProven = $false
        }
        limitations = @(
            'Retained exit intervals are not exposure-adjusted daily failure rates or a matched causal A/B.'
            'Zero dropped events does not rule out normal ring eviction or missing historical exits.'
            'Restart delays are first retained records, not exact functional protection downtime.'
            'FCM OBSERVATION events record state changes, not every completed read; their age is not polling downtime.'
            'Configured/ACTIVE/accepted requests do not prove keepalive effect, repair causality or message delivery.'
            'Episode counts cover the latest exported preview only; omitted/evicted records and unavailable sources leave history unknown. CONNECTION_AFTER_REQUEST is temporal association, not unique causality or delivery. Legacy reports lack pending/returned-result timestamps.'
            'Existing v8 reports cannot reconstruct pre-exit memory pressure or per-process growth; v9 resource samples are sparse correlations, not killer attribution.'
            'App exits do not describe daemon termination. Missing NORMAL_END and unknown intervals do not identify kills; daemon CPU is process-level and battery fields describe device state, not attributable drain.'
        )
    }
}

if ($LibraryOnly) { return }
if (-not $ReportPath -or $ReportPath.Count -gt 8) { throw 'Provide one to eight JSON report paths' }
$summaries = foreach ($path in $ReportPath) {
    $file = Get-Item -LiteralPath $path -ErrorAction Stop
    if ($file.PSIsContainer -or $file.Length -gt 1048576) { throw 'Report must be a JSON file within 1 MiB' }
    $report = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop
    Get-GuardianSurvivalSummary -Report $report -ArtificialExits $KnownArtificialExitAtEpochMillis
}
ConvertTo-Json -InputObject @($summaries) -Depth 16
