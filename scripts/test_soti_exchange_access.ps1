#requires -Version 7.2
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptPath = Join-Path $PSScriptRoot 'soti_exchange_access.ps1'
. $scriptPath -Command auth-check -Config 'dot-source-only'

$script:Tests = 0

function Assert-True {
    param([Parameter(Mandatory)][bool] $Condition, [Parameter(Mandatory)][string] $Message)
    $script:Tests++
    if (-not $Condition) { throw "assertion_failed:$Message" }
}

function Assert-Equal {
    param([AllowNull()][object] $Expected, [AllowNull()][object] $Actual, [Parameter(Mandatory)][string] $Message)
    $script:Tests++
    if ($Expected -ne $Actual) { throw "assertion_failed:${Message}:expected=$Expected:actual=$Actual" }
}

function Assert-ThrowsCode {
    param([Parameter(Mandatory)][scriptblock] $Action, [Parameter(Mandatory)][string] $Code)
    $script:Tests++
    try { & $Action; throw "assertion_failed:expected_error:$Code" }
    catch {
        if (-not $_.Exception.Message.Contains($Code, [StringComparison]::Ordinal)) { throw }
    }
}

function New-TestLogger {
    param([switch] $WithSink)
    $logger = New-Map
    $logger['level'] = 'Off'; $logger['timeout'] = 1L; $logger['runId'] = 'test-run'
    $logger['endpoint'] = if ($WithSink) { 'http://127.0.0.1:4318/v1/logs' } else { $null }
    if ($WithSink) {
        $logger['sendOtlp'] = { param($record) $script:AuditEvents++; $script:LastAuditEvent = $record['event'] }
    }
    return $logger
}

function Copy-Map {
    param([Parameter(Mandatory)][System.Collections.IDictionary] $Value)
    $json = ConvertTo-CanonicalJson $Value
    $document = [Text.Json.JsonDocument]::Parse($json)
    try { return ConvertFrom-JsonElement $document.RootElement }
    finally { $document.Dispose() }
}

$fixturePath = Join-Path $PSScriptRoot 'fixtures/soti_exchange_access/parity.json'
$fixture = Read-JsonFile $fixturePath
$cfg = Assert-Config $fixture['config']
$device = $fixture['device']

Assert-Equal $fixture['expectedPolicyFingerprint'] (Get-PolicyFingerprint $cfg) 'policy fingerprint parity'
$candidate = New-Map; $candidate['deviceId'] = 'device-01'; $candidate['referenceId'] = 'exchange-main'
Assert-Equal $fixture['expectedSnapshotId'] (Get-SnapshotId $fixture['expectedPolicyFingerprint'] $fixture['generatedAt'] $fixture['expiresAt'] @($candidate)) 'snapshot parity'
$missingPayload = Copy-Map $cfg; $missingPayload['actionPayloadTemplate'] = $null
Assert-ThrowsCode { [void] (Assert-Config $missingPayload) } 'verified_action_requires_payload_template'
$insecureUrl = Copy-Map $cfg; $insecureUrl['baseUrl'] = 'http://soti.example.test/MobiControl/api'
Assert-ThrowsCode { [void] (Assert-Config $insecureUrl) } 'invalid_https_url:baseUrl'
$embeddedSecret = Copy-Map $cfg; $embeddedSecret['clientSecret'] = 'forbidden'
Assert-ThrowsCode { [void] (Assert-Config $embeddedSecret) } 'secrets_or_identity_fields_forbidden_in_config'
$missingReferences = Copy-Map $cfg; [void] $missingReferences.Remove('exchangeReferenceIds')
$normalizedReferences = Assert-Config $missingReferences
Assert-Equal 0 $normalizedReferences['exchangeReferenceIds'].Count 'missing reference allowlist defaults empty'
Assert-Equal (Get-PolicyFingerprint $cfg) (Get-PolicyFingerprint $normalizedReferences) 'missing and empty reference policy parity'
$emptyEndpoint = Copy-Map $cfg; $emptyEndpoint['otlpLogsEndpoint'] = ''
Assert-ThrowsCode { [void] (Assert-Config $emptyEndpoint) } 'invalid_otlpLogsEndpoint'

$decision = Test-DeviceEligibility $device $cfg
Assert-Equal 'candidate' $decision['decision'] 'corporate quarantined candidate'
Assert-Equal 'corporate_quarantined' $decision['reason'] 'candidate reason'
$filteredConfig = Copy-Map $cfg; $filteredConfig['exchangeReferenceIds'] = @('other-exchange')
Assert-Equal 'exchange_record_count' (Test-DeviceEligibility $device $filteredConfig)['reason'] 'optional allowlist still filters'
$wrongCase = Copy-Map $device; $wrongCase['ManagementMode'] = 'deviceowner'
Assert-Equal 'management_mode_not_allowed' (Test-DeviceEligibility $wrongCase $cfg)['reason'] 'ordinal management mode'
$external = Copy-Map $device; $external['GroupPath'] = '/Corporate-External'
Assert-Equal 'non_corporate_group' (Test-DeviceEligibility $external $cfg)['reason'] 'group boundary'
$duplicate = Copy-Map $device; $duplicate['ExchangeOnlineEmailAccess'] = @($duplicate['ExchangeOnlineEmailAccess'][0], $duplicate['ExchangeOnlineEmailAccess'][0])
Assert-Equal 'exchange_record_count' (Test-DeviceEligibility $duplicate $cfg)['reason'] 'duplicate exchange record'
$paged = New-Map; $paged['Items'] = @($device); $paged['Next'] = 'page-2'
Assert-ThrowsCode { [void] (ConvertTo-DeviceList $paged) } 'device_pagination_not_supported_use_filtered_path'
$partial = New-Map; $partial['Items'] = @($device); $partial['TotalCount'] = 2L
Assert-ThrowsCode { [void] (ConvertTo-DeviceList $partial) } 'device_collection_may_be_partial'

$logger = New-TestLogger
$now = [DateTimeOffset]::ParseExact($fixture['generatedAt'], 'yyyy-MM-ddTHH:mm:ssZ', [Globalization.CultureInfo]::InvariantCulture)
$inspection = Invoke-Inspection {
    $listedDevices = [System.Collections.Generic.List[object]]::new()
    $listedDevices.Add($device)
    return (,$listedDevices)
} $cfg $logger $now
Assert-Equal $fixture['expectedPolicyFingerprint'] $inspection['policyFingerprint'] 'inspection policy fingerprint'
Assert-Equal $fixture['expectedSnapshotId'] $inspection['snapshotId'] 'inspection snapshot'
$emptyInspection = Invoke-Inspection {
    $emptyDevices = [System.Collections.Generic.List[object]]::new()
    return (,$emptyDevices)
} $cfg $logger $now
Assert-Equal 0 $emptyInspection['candidates'].Count 'empty fleet inspection'
$approval = New-Map; $approval['schemaVersion'] = 1L; $approval['snapshotId'] = $inspection['snapshotId']; $approval['approvals'] = $inspection['candidates']
$approved = Assert-Approval $inspection $approval $cfg $now.AddMinutes(1)
Assert-Equal 1 $approved.Count 'approval count'
Assert-ThrowsCode { [void] (Assert-Approval $inspection $approval $cfg $now.AddMinutes(31)) } 'inspection_report_expired'
$changedConfig = Copy-Map $cfg; $changedConfig['corporateGroupPrefixes'] = @('/Other')
Assert-ThrowsCode { [void] (Assert-Approval $inspection $approval $changedConfig $now.AddMinutes(1)) } 'inspection_policy_changed'
$duplicateApproval = Copy-Map $approval; $duplicateApproval['approvals'] = @($approval['approvals'][0], $approval['approvals'][0])
Assert-ThrowsCode { [void] (Assert-Approval $inspection $duplicateApproval $cfg $now.AddMinutes(1)) } 'approval_not_unique_candidate'

$script:StateDevice = Copy-Map $device
$script:ActionCount = 0
$script:AuditEvents = 0
$script:LastAuditEvent = $null
$getDevice = { param($id) $script:StateDevice }
$allow = {
    param($id, $referenceId)
    $script:ActionCount++
    $script:StateDevice['ExchangeOnlineEmailAccess'][0]['Status'] = 'Allowed'
}
$sinkLogger = New-TestLogger -WithSink
$result = Invoke-ApprovedActions -GetDevice $getDevice -AllowExchange $allow -Config $cfg -Logger $sinkLogger -Approved $approved
Assert-Equal 1 $script:ActionCount 'one action'
Assert-Equal 'allowed' $result['results'][0]['outcome'] 'allowed outcome'
Assert-True ($script:AuditEvents -ge 2) 'audit events delivered'
$second = Invoke-ApprovedActions -GetDevice $getDevice -AllowExchange $allow -Config $cfg -Logger $sinkLogger -Approved $approved
Assert-Equal 1 $script:ActionCount 'idempotent action count'
Assert-Equal 'already_allowed' $second['results'][0]['outcome'] 'already allowed outcome'

$script:StateDevice = Copy-Map $device
$script:ActionCount = 0
$ambiguous = { param($id, $referenceId) $script:ActionCount++; Throw-ApiError 'soti_transport_error' }
Assert-ThrowsCode {
    [void] (Invoke-ApprovedActions -GetDevice $getDevice -AllowExchange $ambiguous -Config $cfg -Logger $sinkLogger -Approved $approved)
} 'exchange_action_ambiguous_manual_review_required'
Assert-Equal 1 $script:ActionCount 'ambiguous post not retried'
$script:StateDevice = Copy-Map $device
$script:ActionCount = 0
$stdoutResult = Invoke-ApprovedActions -GetDevice $getDevice -AllowExchange $allow -Config $cfg -Logger (New-TestLogger) -Approved $approved
Assert-Equal 1 $script:ActionCount 'stdout-only action count'
Assert-Equal 'allowed' $stdoutResult['results'][0]['outcome'] 'stdout-only apply outcome'
$script:StateDevice = Copy-Map $device
$script:ActionCount = 0
$failingSinkLogger = New-TestLogger -WithSink
$failingSinkLogger['sendOtlp'] = { param($record) throw 'collector unavailable' }
$warningResult = Invoke-ApprovedActions -GetDevice $getDevice -AllowExchange $allow -Config $cfg -Logger $failingSinkLogger -Approved $approved
Assert-Equal 1 $script:ActionCount 'unavailable OTLP does not block action'
Assert-Equal 'allowed' $warningResult['results'][0]['outcome'] 'unavailable OTLP apply outcome'
$unverified = Copy-Map $cfg; $unverified['actionContractVerified'] = $false
Assert-ThrowsCode {
    [void] (Invoke-ApprovedActions -GetDevice $getDevice -AllowExchange $allow -Config $unverified -Logger $sinkLogger -Approved $approved)
} 'action_contract_not_verified'

$redacted = Protect-LogValue @{ token = 'secret'; deviceId = 'device-01'; nested = @{ emailAddress = 'person@example.test' }; count = 1L }
Assert-Equal '***' $redacted['token'] 'token redaction'
Assert-Equal '***' $redacted['nested']['emailAddress'] 'email redaction'
Assert-True ([string] $redacted['deviceId'] -match '^sha256:[0-9a-f]{12}$') 'device id hashing'
Assert-ThrowsCode { Test-OtlpResponse '{"partialSuccess":{"rejectedLogRecords":"1"}}' } 'otlp_logs_rejected'
Test-OtlpResponse '{"partialSuccess":{}}'

if (-not $IsWindows) {
    $secretPath = Join-Path ([IO.Path]::GetTempPath()) ("ahwotel-secret-{0}" -f [Guid]::NewGuid())
    try {
        [IO.File]::WriteAllText($secretPath, "value`n", $script:Utf8NoBom)
        [IO.File]::SetUnixFileMode($secretPath, [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite -bor [IO.UnixFileMode]::GroupRead)
        Assert-ThrowsCode { Read-SecretValue $secretPath 'unused' } 'secret_file_permissions_must_be_0600'
        [IO.File]::SetUnixFileMode($secretPath, [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite)
        Assert-Equal 'value' (Read-SecretValue $secretPath 'unused') 'private secret file'
    }
    finally { if ([IO.File]::Exists($secretPath)) { [IO.File]::Delete($secretPath) } }
}

$outputDirectory = Join-Path ([IO.Path]::GetTempPath()) ("ahwotel-output-{0}" -f [Guid]::NewGuid())
$outputPath = Join-Path $outputDirectory 'result.json'
try {
    $outputValue = New-Map; $outputValue['schemaVersion'] = 1L
    $outputValue['items'] = [System.Collections.Generic.List[object]]::new()
    Write-AtomicJson $outputPath $outputValue
    $written = Read-JsonFile $outputPath
    Assert-Equal 1 $written['schemaVersion'] 'atomic output readable'
    Assert-Equal 0 $written['items'].Count 'empty array preserved'
    if (-not $IsWindows) {
        $mode = [IO.File]::GetUnixFileMode($outputPath)
        Assert-Equal ([IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite) $mode 'output mode 0600'
    }
}
finally { if ([IO.Directory]::Exists($outputDirectory)) { [IO.Directory]::Delete($outputDirectory, $true) } }

$tokens = $null; $errors = $null
[void] [Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref] $tokens, [ref] $errors)
Assert-Equal 0 $errors.Count 'main script parser errors'
[Console]::Out.WriteLine("PowerShell tests: $script:Tests passed")
