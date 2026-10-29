#requires -Version 5.1
<#
.SYNOPSIS
Safely inspects and approves SOTI-managed devices for Exchange access.

.DESCRIPTION
Windows PowerShell 5.1 implementation of soti_exchange_access.py. All variants
use the same configuration and artifact schema. Secrets are read from protected
files or, only in an interactive terminal, from a secure prompt.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('auth-check', 'inspect', 'apply', 'verify')]
    [string] $Command,

    [Parameter(Mandatory)]
    [string] $Config,

    [string] $DeviceId,
    [string] $Output,
    [string] $Report,
    [string] $Approvals,
    [ValidateSet('APPLY')]
    [string] $Confirm
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:SchemaVersion = 1
$script:DiagnosticLevels = @('Off', 'Basic', 'Verbose')
$script:SensitiveKeyPattern = '(?i)password|secret|token|authorization|email|account'
$script:Utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$script:JsonSerializer = $null

class SotiContractException : System.Exception {
    SotiContractException([string] $message) : base($message) {}
}

class SotiApiException : System.Exception {
    SotiApiException([string] $message) : base($message) {}
}

function Throw-ContractError {
    param([Parameter(Mandatory)][string] $Code)
    throw [SotiContractException]::new($Code)
}

function Throw-ApiError {
    param([Parameter(Mandatory)][string] $Code)
    throw [SotiApiException]::new($Code)
}

function Get-UtcTimestamp {
    return [DateTimeOffset]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffffffZ', [Globalization.CultureInfo]::InvariantCulture)
}

function Get-UnixTimeNanosecondsString {
    $epochTicks = 621355968000000000L
    $nanoseconds = ([DateTimeOffset]::UtcNow.UtcDateTime.Ticks - $epochTicks) * 100L
    return $nanoseconds.ToString([Globalization.CultureInfo]::InvariantCulture)
}

function Assert-WindowsPowerShell51 {
    $edition = if ($PSVersionTable.ContainsKey('PSEdition')) { [string] $PSVersionTable['PSEdition'] } else { 'Desktop' }
    if ($env:OS -ne 'Windows_NT' -or $edition -ne 'Desktop' -or
        $PSVersionTable.PSVersion.Major -ne 5 -or $PSVersionTable.PSVersion.Minor -lt 1) {
        Throw-ContractError 'windows_powershell_5_1_required'
    }
}

function Initialize-HttpRuntime {
    try { Add-Type -AssemblyName System.Net.Http -ErrorAction Stop }
    catch { Throw-ContractError 'system_net_http_unavailable' }
    [Net.ServicePointManager]::SecurityProtocol =
        [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
}

function Get-JsonSerializer {
    if ($null -eq $script:JsonSerializer) {
        try { Add-Type -AssemblyName System.Web.Extensions -ErrorAction Stop }
        catch { Throw-ContractError 'system_web_extensions_unavailable' }
        $script:JsonSerializer = New-Object System.Web.Script.Serialization.JavaScriptSerializer
        $script:JsonSerializer.MaxJsonLength = [int]::MaxValue
        $script:JsonSerializer.RecursionLimit = 100
    }
    return $script:JsonSerializer
}

function ConvertFrom-FrameworkJsonValue {
    param([AllowNull()][object] $Value)

    if ($null -eq $Value) { return $null }
    if ($Value -is [System.Collections.IDictionary]) {
        $map = [System.Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
        foreach ($entry in $Value.GetEnumerator()) {
            $map[[string] $entry.Key] = ConvertFrom-FrameworkJsonValue $entry.Value
        }
        return $map
    }
    if ($Value -is [System.Collections.IList]) {
        $items = [System.Collections.Generic.List[object]]::new()
        foreach ($item in $Value) { $items.Add((ConvertFrom-FrameworkJsonValue $item)) }
        return (,$items)
    }
    return $Value
}

function ConvertFrom-JsonText {
    param([Parameter(Mandatory)][AllowEmptyString()][string] $Text)

    try {
        $value = (Get-JsonSerializer).DeserializeObject($Text)
        return ConvertFrom-FrameworkJsonValue $value
    }
    catch [SotiContractException] { throw }
    catch { Throw-ContractError 'invalid_json' }
}

function Read-JsonFile {
    param([Parameter(Mandatory)][string] $Path)

    try {
        $text = [IO.File]::ReadAllText([IO.Path]::GetFullPath($Path), $script:Utf8NoBom)
        return ConvertFrom-JsonText ([string] $text)
    }
    catch {
        Throw-ContractError ("invalid_json:{0}" -f [IO.Path]::GetFileName($Path))
    }
}

function Test-IsMap {
    param([AllowNull()][object] $Value)
    return $Value -is [System.Collections.IDictionary]
}

function Test-IsArray {
    param([AllowNull()][object] $Value)
    return $Value -is [System.Collections.IList]
}

function Test-HasKey {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary] $Map,
        [Parameter(Mandatory)][string] $Key
    )
    return $Map.Contains($Key)
}

function Get-MapValue {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary] $Map,
        [Parameter(Mandatory)][string] $Key,
        [AllowNull()][object] $Default = $null
    )
    if ($Map.Contains($Key)) { $value = $Map[$Key] }
    else { $value = $Default }
    if ($value -is [System.Collections.IList]) { return (,$value) }
    else { return $value }
}

function ConvertTo-PythonJsonString {
    param([Parameter(Mandatory)][AllowEmptyString()][string] $Value)

    $builder = [Text.StringBuilder]::new()
    [void] $builder.Append('"')
    foreach ($character in $Value.ToCharArray()) {
        $number = [int] $character
        switch ($number) {
            8 { [void] $builder.Append('\b'); continue }
            9 { [void] $builder.Append('\t'); continue }
            10 { [void] $builder.Append('\n'); continue }
            12 { [void] $builder.Append('\f'); continue }
            13 { [void] $builder.Append('\r'); continue }
            34 { [void] $builder.Append('\"'); continue }
            92 { [void] $builder.Append('\\'); continue }
        }
        if ($number -lt 0x20 -or $number -gt 0x7f) {
            [void] $builder.Append(('\u{0:x4}' -f $number))
        }
        else {
            [void] $builder.Append($character)
        }
    }
    [void] $builder.Append('"')
    return $builder.ToString()
}

function ConvertTo-CanonicalJson {
    param([AllowNull()][object] $Value)

    if ($null -eq $Value) { return 'null' }
    if ($Value -is [string]) { return ConvertTo-PythonJsonString -Value $Value }
    if ($Value -is [bool]) { if ($Value) { return 'true' } else { return 'false' } }
    if ($Value -is [byte] -or $Value -is [sbyte] -or $Value -is [int16] -or
        $Value -is [uint16] -or $Value -is [int32] -or $Value -is [uint32] -or
        $Value -is [int64] -or $Value -is [uint64]) {
        return [Convert]::ToString($Value, [Globalization.CultureInfo]::InvariantCulture)
    }
    if ($Value -is [decimal]) {
        return $Value.ToString('G29', [Globalization.CultureInfo]::InvariantCulture)
    }
    if ($Value -is [double] -or $Value -is [single]) {
        return $Value.ToString('R', [Globalization.CultureInfo]::InvariantCulture)
    }
    if (Test-IsMap $Value) {
        $parts = [System.Collections.Generic.List[string]]::new()
        $keys = @($Value.Keys | ForEach-Object { [string] $_ })
        [Array]::Sort($keys, [StringComparer]::Ordinal)
        foreach ($key in $keys) {
            $parts.Add(('{0}:{1}' -f (ConvertTo-PythonJsonString $key), (ConvertTo-CanonicalJson $Value[$key])))
        }
        return '{' + [string]::Join(',', $parts) + '}'
    }
    if (Test-IsArray $Value) {
        $parts = @($Value | ForEach-Object { ConvertTo-CanonicalJson $_ })
        return '[' + [string]::Join(',', $parts) + ']'
    }
    Throw-ContractError ("unsupported_canonical_json_type:{0}" -f $Value.GetType().FullName)
}

function Get-Sha256Hex {
    param([Parameter(Mandatory)][string] $Value)
    $bytes = $script:Utf8NoBom.GetBytes($Value)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try { $hash = $sha256.ComputeHash($bytes) }
    finally { $sha256.Dispose() }
    return ([BitConverter]::ToString($hash).Replace('-', '').ToLowerInvariant())
}

function ConvertTo-OutputObject {
    param([AllowNull()][object] $Value)
    if ($null -eq $Value) { return $null }
    if (Test-IsMap $Value) {
        $ordered = [ordered]@{}
        $keys = @($Value.Keys | ForEach-Object { [string] $_ })
        [Array]::Sort($keys, [StringComparer]::Ordinal)
        foreach ($key in $keys) { $ordered[$key] = ConvertTo-OutputObject $Value[$key] }
        return $ordered
    }
    if (Test-IsArray $Value) {
        $items = @($Value | ForEach-Object { ConvertTo-OutputObject $_ })
        return (,$items)
    }
    return $Value
}

function Set-PrivateFilePermissions {
    param([Parameter(Mandatory)][string] $Path)

    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $security = [Security.AccessControl.FileSecurity]::new()
    $security.SetAccessRuleProtection($true, $false)
    $rights = [Security.AccessControl.FileSystemRights]::Read -bor [Security.AccessControl.FileSystemRights]::Write
    $rule = [Security.AccessControl.FileSystemAccessRule]::new(
        $identity, $rights, [Security.AccessControl.AccessControlType]::Allow)
    [void] $security.AddAccessRule($rule)
    [IO.File]::SetAccessControl($Path, $security)
}

function Write-AtomicJson {
    param(
        [Parameter(Mandatory)][string] $Path,
        [Parameter(Mandatory)][AllowNull()][object] $Value
    )

    $fullPath = [IO.Path]::GetFullPath($Path)
    $directory = [IO.Path]::GetDirectoryName($fullPath)
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    $temporary = [IO.Path]::Combine($directory, ('.{0}.{1}.tmp' -f [IO.Path]::GetFileName($fullPath), [Guid]::NewGuid()))
    try {
        $json = (ConvertTo-OutputObject $Value | ConvertTo-Json -Depth 100) + "`n"
        [IO.File]::WriteAllText($temporary, $json, $script:Utf8NoBom)
        Set-PrivateFilePermissions $temporary
        if ([IO.File]::Exists($fullPath)) {
            Set-PrivateFilePermissions $fullPath
            [IO.File]::Replace($temporary, $fullPath, $null)
        }
        else { [IO.File]::Move($temporary, $fullPath) }
        Set-PrivateFilePermissions $fullPath
    }
    finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}

function Test-SecretFilePermissions {
    param([Parameter(Mandatory)][string] $Path)

    $broadSids = @('S-1-1-0', 'S-1-5-11', 'S-1-5-32-545')
    try {
        $security = [IO.File]::GetAccessControl($Path)
        $rules = $security.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])
        foreach ($rule in $rules) {
            $sid = $rule.IdentityReference.Value
            $readRights = [Security.AccessControl.FileSystemRights]::Read -bor
                [Security.AccessControl.FileSystemRights]::ReadData -bor
                [Security.AccessControl.FileSystemRights]::ReadAndExecute -bor
                [Security.AccessControl.FileSystemRights]::Modify -bor
                [Security.AccessControl.FileSystemRights]::FullControl
            if ($rule.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
                $broadSids -contains $sid -and ($rule.FileSystemRights -band $readRights)) {
                Throw-ContractError ("secret_file_permissions_too_broad:{0}" -f [IO.Path]::GetFileName($Path))
            }
        }
    }
    catch [SotiContractException] { throw }
    catch { Throw-ContractError ("secret_file_permissions_unreadable:{0}" -f [IO.Path]::GetFileName($Path)) }
}

function Read-SecretValue {
    param(
        [AllowEmptyString()][string] $Path,
        [Parameter(Mandatory)][string] $Prompt
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        if ([Environment]::UserInteractive -and -not [Console]::IsInputRedirected) {
            $secure = Read-Host -Prompt $Prompt -AsSecureString
            $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
            try {
                $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
                if (-not [string]::IsNullOrWhiteSpace($plain)) { return $plain }
            }
            finally {
                [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
            }
        }
        Throw-ContractError 'secret_file_required'
    }
    try {
        $fullPath = [IO.Path]::GetFullPath($Path)
        Test-SecretFilePermissions $fullPath
        $value = [IO.File]::ReadAllText($fullPath, $script:Utf8NoBom).Trim()
    }
    catch [SotiContractException] { throw }
    catch { Throw-ContractError ("secret_file_unreadable:{0}" -f [IO.Path]::GetFileName($Path)) }
    if ([string]::IsNullOrWhiteSpace($value)) {
        Throw-ContractError ("secret_file_empty:{0}" -f [IO.Path]::GetFileName($Path))
    }
    return $value
}

function Test-StringInList {
    param([Parameter(Mandatory)][string] $Value, [Parameter(Mandatory)][System.Collections.IList] $Values)
    foreach ($item in $Values) {
        if ([string]::Equals($Value, [string] $item, [StringComparison]::Ordinal)) { return $true }
    }
    return $false
}

function Assert-StringArray {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary] $Map,
        [Parameter(Mandatory)][string] $Name,
        [switch] $AllowEmpty
    )
    if (-not $Map.Contains($Name) -or -not (Test-IsArray $Map[$Name]) -or
        (-not $AllowEmpty -and $Map[$Name].Count -eq 0)) {
        Throw-ContractError "invalid_config:$Name"
    }
    foreach ($item in $Map[$Name]) {
        if ($item -isnot [string] -or [string]::IsNullOrEmpty($item)) { Throw-ContractError "invalid_config:$Name" }
    }
}

function Assert-Url {
    param(
        [AllowNull()][object] $Value,
        [Parameter(Mandatory)][string] $Name,
        [switch] $RequireApiPath
    )
    [Uri] $uri = $null
    if ($Value -isnot [string] -or -not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref] $uri) -or
        $uri.Scheme -ne 'https' -or [string]::IsNullOrEmpty($uri.Host) -or
        -not [string]::IsNullOrEmpty($uri.UserInfo)) {
        Throw-ContractError "invalid_https_url:$Name"
    }
    if (-not [string]::IsNullOrEmpty($uri.Query) -or -not [string]::IsNullOrEmpty($uri.Fragment)) {
        Throw-ContractError "url_query_or_fragment_forbidden:$Name"
    }
    if ($RequireApiPath -and -not $uri.AbsolutePath.TrimEnd('/').EndsWith('/MobiControl/api', [StringComparison]::Ordinal)) {
        Throw-ContractError 'baseUrl_must_end_with_MobiControl_api'
    }
    return $Value.TrimEnd('/')
}

function Assert-RelativePath {
    param([AllowNull()][object] $Value, [Parameter(Mandatory)][string] $Name)
    if ($Value -isnot [string] -or [string]::IsNullOrEmpty($Value) -or $Value.StartsWith('/') -or
        $Value.StartsWith('http:', [StringComparison]::Ordinal) -or
        $Value.StartsWith('https:', [StringComparison]::Ordinal) -or $Value.Contains('..')) {
        Throw-ContractError "invalid_config:$Name"
    }
    return $Value
}

function Assert-PathTemplate {
    param([AllowNull()][object] $Value, [Parameter(Mandatory)][string] $Name)
    $valueString = Assert-RelativePath $Value $Name
    $placeholders = @([regex]::Matches($valueString, '\{([^{}]+)\}') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
    if ($placeholders.Count -ne 1 -or $placeholders[0] -ne 'deviceId') {
        Throw-ContractError "invalid_config:${Name}_placeholders"
    }
    return $valueString
}

function Assert-IntegerSetting {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary] $Config,
        [Parameter(Mandatory)][string] $Name,
        [Parameter(Mandatory)][long] $Default,
        [Parameter(Mandatory)][long] $Minimum,
        [Parameter(Mandatory)][long] $Maximum
    )
    $value = if ($Config.Contains($Name)) { $Config[$Name] } else { $Default }
    if ($value -isnot [long] -or $value -lt $Minimum -or $value -gt $Maximum) {
        Throw-ContractError "invalid_config:$Name"
    }
    $Config[$Name] = $value
}

function Assert-Config {
    param([AllowNull()][object] $Raw)

    if (-not (Test-IsMap $Raw)) { Throw-ContractError 'config_must_be_object' }
    foreach ($key in $Raw.Keys) {
        if ([string] $key -match $script:SensitiveKeyPattern) { Throw-ContractError 'secrets_or_identity_fields_forbidden_in_config' }
    }
    $config = [hashtable]::new([StringComparer]::Ordinal)
    foreach ($key in $Raw.Keys) { $config[[string] $key] = $Raw[$key] }
    $config['baseUrl'] = Assert-Url (Get-MapValue $config 'baseUrl') 'baseUrl' -RequireApiPath
    foreach ($name in @('corporateGroupPrefixes', 'allowedManagementModes', 'activeDeviceStatuses',
        'quarantineStatuses', 'allowedExchangeStatuses')) {
        Assert-StringArray $config $name
    }
    $referenceIds = Get-MapValue $config 'exchangeReferenceIds' ([System.Collections.Generic.List[object]]::new())
    if (-not (Test-IsArray $referenceIds)) { Throw-ContractError 'invalid_config:exchangeReferenceIds' }
    $config['exchangeReferenceIds'] = $referenceIds
    Assert-StringArray $config 'exchangeReferenceIds' -AllowEmpty
    $requiredFields = @('deviceId', 'groupPath', 'managementMode', 'deviceStatus', 'exchangeAccess', 'exchangeReferenceId', 'exchangeStatus')
    $fields = Get-MapValue $config 'fields'
    if (-not (Test-IsMap $fields) -or $fields.Count -ne $requiredFields.Count) { Throw-ContractError 'invalid_config:fields' }
    foreach ($name in $requiredFields) {
        if (-not $fields.Contains($name) -or $fields[$name] -isnot [string] -or [string]::IsNullOrEmpty($fields[$name])) {
            Throw-ContractError 'invalid_config:fields'
        }
    }
    $diagnostic = if ($config.Contains('diagnostic')) { $config['diagnostic'] } else { 'Off' }
    if ($diagnostic -isnot [string] -or -not (Test-StringInList $diagnostic $script:DiagnosticLevels)) {
        Throw-ContractError 'invalid_config:diagnostic'
    }
    $config['diagnostic'] = $diagnostic
    Assert-IntegerSetting $config 'reportTtlMinutes' 30 1 1440
    Assert-IntegerSetting $config 'requestTimeoutSeconds' 20 1 120
    Assert-IntegerSetting $config 'verifyTimeoutSeconds' 60 1 600
    Assert-IntegerSetting $config 'verifyPollSeconds' 3 1 60
    $config['devicesPath'] = Assert-RelativePath (Get-MapValue $config 'devicesPath' 'devices') 'devicesPath'
    $config['devicePathTemplate'] = Assert-PathTemplate (Get-MapValue $config 'devicePathTemplate' 'devices/{deviceId}') 'devicePathTemplate'
    $config['actionPathTemplate'] = Assert-PathTemplate (Get-MapValue $config 'actionPathTemplate' 'devices/{deviceId}/actions') 'actionPathTemplate'
    $excluded = Get-MapValue $config 'excludedDeviceIds' ([System.Collections.Generic.List[object]]::new())
    if (-not (Test-IsArray $excluded)) { Throw-ContractError 'invalid_config:excludedDeviceIds' }
    foreach ($item in $excluded) {
        if ($item -isnot [string] -or [string]::IsNullOrEmpty($item)) { Throw-ContractError 'invalid_config:excludedDeviceIds' }
    }
    $config['excludedDeviceIds'] = $excluded
    $verified = Get-MapValue $config 'actionContractVerified' $false
    if ($verified -isnot [bool]) { Throw-ContractError 'invalid_config:actionContractVerified' }
    $config['actionContractVerified'] = $verified
    $payload = Get-MapValue $config 'actionPayloadTemplate'
    if ($null -ne $payload -and -not (Test-IsMap $payload)) { Throw-ContractError 'invalid_config:actionPayloadTemplate' }
    if ($null -ne $payload) {
        $payloadJson = ConvertTo-CanonicalJson $payload
        foreach ($match in [regex]::Matches($payloadJson, '\{([^{}]+)\}')) {
            if (-not (Test-StringInList $match.Groups[1].Value @('deviceId', 'referenceId'))) {
                Throw-ContractError 'invalid_config:actionPayloadTemplate_placeholders'
            }
        }
    }
    if ($verified -and $null -eq $payload) { Throw-ContractError 'verified_action_requires_payload_template' }
    $endpoint = Get-MapValue $config 'otlpLogsEndpoint'
    if ($null -ne $endpoint) {
        [Uri] $uri = $null
        if ($endpoint -isnot [string] -or -not [Uri]::TryCreate($endpoint, [UriKind]::Absolute, [ref] $uri) -or
            $uri.Scheme -notin @('http', 'https') -or [string]::IsNullOrEmpty($uri.Host) -or
            -not [string]::IsNullOrEmpty($uri.UserInfo) -or -not [string]::IsNullOrEmpty($uri.Query) -or
            -not [string]::IsNullOrEmpty($uri.Fragment)) {
            Throw-ContractError 'invalid_otlpLogsEndpoint'
        }
        if (-not $uri.AbsolutePath.EndsWith('/v1/logs', [StringComparison]::Ordinal)) {
            Throw-ContractError 'otlpLogsEndpoint_must_end_with_v1_logs'
        }
    }
    $auditPath = Get-MapValue $config 'auditLogPath'
    if ($null -ne $auditPath -and ($auditPath -isnot [string] -or [string]::IsNullOrWhiteSpace($auditPath))) {
        Throw-ContractError 'invalid_config:auditLogPath'
    }
    return $config
}

function Get-DottedValue {
    param([AllowNull()][object] $Value, [Parameter(Mandatory)][string] $Path)
    $current = $Value
    foreach ($segment in $Path.Split('.')) {
        if (-not (Test-IsMap $current) -or -not $current.Contains($segment)) {
            Throw-ContractError "missing_response_field:$Path"
        }
        $current = $current[$segment]
    }
    if ($current -is [System.Collections.IList]) { return (,$current) }
    else { return $current }
}

function ConvertTo-DeviceList {
    param([AllowNull()][object] $Value)
    if (Test-IsArray $Value) {
        $items = $Value
    }
    elseif (Test-IsMap $Value) {
        foreach ($name in @('Next', 'next', 'NextPage', 'nextPage', 'ContinuationToken', 'continuationToken')) {
            if ($Value.Contains($name) -and $Value[$name]) { Throw-ContractError 'device_pagination_not_supported_use_filtered_path' }
        }
        $keys = @(@('Items', 'items', 'Devices', 'devices', 'Data', 'data') | Where-Object { $Value.Contains($_) })
        if ($keys.Count -ne 1 -or -not (Test-IsArray $Value[$keys[0]])) { Throw-ContractError 'unsupported_device_collection_shape' }
        $items = $Value[$keys[0]]
        foreach ($name in @('Total', 'total', 'TotalCount', 'totalCount', 'TotalResults', 'totalResults')) {
            if ($Value.Contains($name)) {
                $total = $Value[$name]
                if ($total -isnot [long] -or $total -ne $items.Count) { Throw-ContractError 'device_collection_may_be_partial' }
            }
        }
    }
    else { Throw-ContractError 'unsupported_device_collection_shape' }
    foreach ($item in $items) { if (-not (Test-IsMap $item)) { Throw-ContractError 'device_collection_contains_non_object' } }
    return ,$items
}

function Test-GroupMatch {
    param([Parameter(Mandatory)][string] $Path, [Parameter(Mandatory)][System.Collections.IList] $Prefixes)
    $normalized = $Path.TrimEnd('/')
    foreach ($prefixValue in $Prefixes) {
        $prefix = ([string] $prefixValue).TrimEnd('/')
        if ([string]::Equals($normalized, $prefix, [StringComparison]::Ordinal) -or
            $normalized.StartsWith($prefix + '/', [StringComparison]::Ordinal)) { return $true }
    }
    return $false
}

function Get-PolicyMaterial {
    param([Parameter(Mandatory)][System.Collections.IDictionary] $Config)
    $result = [hashtable]::new([StringComparer]::Ordinal)
    foreach ($name in @('baseUrl', 'corporateGroupPrefixes', 'allowedManagementModes', 'activeDeviceStatuses',
        'exchangeReferenceIds', 'quarantineStatuses', 'allowedExchangeStatuses', 'fields', 'devicesPath',
        'devicePathTemplate', 'actionPathTemplate', 'actionPayloadTemplate', 'excludedDeviceIds', 'actionContractVerified')) {
        $result[$name] = Get-MapValue $Config $name
    }
    return $result
}

function Get-PolicyFingerprint {
    param([Parameter(Mandatory)][System.Collections.IDictionary] $Config)
    return Get-Sha256Hex (ConvertTo-CanonicalJson (Get-PolicyMaterial $Config))
}

function Get-SnapshotId {
    param(
        [Parameter(Mandatory)][string] $PolicyFingerprint,
        [Parameter(Mandatory)][string] $GeneratedAt,
        [Parameter(Mandatory)][string] $ExpiresAt,
        [Parameter(Mandatory)][System.Collections.IList] $Candidates
    )
    $material = [hashtable]::new([StringComparer]::Ordinal)
    $material['policy'] = $PolicyFingerprint
    $material['generatedAt'] = $GeneratedAt
    $material['expiresAt'] = $ExpiresAt
    $material['candidates'] = $Candidates
    return Get-Sha256Hex (ConvertTo-CanonicalJson $material)
}

function New-Map {
    $result = [hashtable]::new([StringComparer]::Ordinal)
    return $result
}

function Test-DeviceEligibility {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary] $Device,
        [Parameter(Mandatory)][System.Collections.IDictionary] $Config
    )
    $fields = $Config['fields']
    $deviceIdValue = [string] (Get-DottedValue $Device $fields['deviceId'])
    $groupPath = [string] (Get-DottedValue $Device $fields['groupPath'])
    $managementMode = [string] (Get-DottedValue $Device $fields['managementMode'])
    $deviceStatus = [string] (Get-DottedValue $Device $fields['deviceStatus'])
    $result = New-Map
    $result['deviceId'] = $deviceIdValue
    $result['groupPath'] = $groupPath
    $result['managementMode'] = $managementMode
    $result['deviceStatus'] = $deviceStatus
    if (Test-StringInList $deviceIdValue $Config['excludedDeviceIds']) {
        $result['decision'] = 'skip'; $result['reason'] = 'manual_exclusion'; return $result
    }
    if (-not (Test-GroupMatch $groupPath $Config['corporateGroupPrefixes'])) {
        $result['decision'] = 'skip'; $result['reason'] = 'non_corporate_group'; return $result
    }
    if (-not (Test-StringInList $managementMode $Config['allowedManagementModes'])) {
        $result['decision'] = 'skip'; $result['reason'] = 'management_mode_not_allowed'; return $result
    }
    if (-not (Test-StringInList $deviceStatus $Config['activeDeviceStatuses'])) {
        $result['decision'] = 'skip'; $result['reason'] = 'device_not_active'; return $result
    }
    $records = Get-DottedValue $Device $fields['exchangeAccess']
    if (-not (Test-IsArray $records)) { Throw-ContractError 'exchange_access_must_be_array' }
    $matching = @()
    $invalidReferences = 0
    $referenceAllowlist = $Config['exchangeReferenceIds']
    foreach ($record in $records) {
        if (-not (Test-IsMap $record)) { Throw-ContractError 'exchange_access_contains_non_object' }
        $reference = Get-DottedValue $record $fields['exchangeReferenceId']
        if ($reference -isnot [string] -or [string]::IsNullOrWhiteSpace($reference)) {
            $invalidReferences++; continue
        }
        if ($referenceAllowlist.Count -eq 0 -or (Test-StringInList $reference $referenceAllowlist)) { $matching += $record }
    }
    if ($invalidReferences -gt 0) {
        $result['decision'] = 'manual'; $result['reason'] = 'exchange_reference_invalid'; $result['recordCount'] = [long] $records.Count
        return $result
    }
    if ($matching.Count -ne 1) {
        $result['decision'] = 'manual'; $result['reason'] = 'exchange_record_count'; $result['recordCount'] = [long] $matching.Count
        return $result
    }
    $referenceId = [string] (Get-DottedValue $matching[0] $fields['exchangeReferenceId'])
    $exchangeStatus = [string] (Get-DottedValue $matching[0] $fields['exchangeStatus'])
    $result['referenceId'] = $referenceId
    $result['exchangeStatus'] = $exchangeStatus
    if (Test-StringInList $exchangeStatus $Config['allowedExchangeStatuses']) {
        $result['decision'] = 'noop'; $result['reason'] = 'already_allowed'; return $result
    }
    if (-not (Test-StringInList $exchangeStatus $Config['quarantineStatuses'])) {
        $result['decision'] = 'manual'; $result['reason'] = 'unexpected_exchange_status'; return $result
    }
    $result['decision'] = 'candidate'; $result['reason'] = 'corporate_quarantined'
    return $result
}

function Protect-LogValue {
    param([AllowNull()][object] $Value)
    if ($null -eq $Value) { return $null }
    if (Test-IsMap $Value) {
        $result = New-Map
        foreach ($keyValue in $Value.Keys) {
            $key = [string] $keyValue
            if ($key -match $script:SensitiveKeyPattern) { $result[$key] = '***' }
            elseif ($key.Equals('deviceId', [StringComparison]::OrdinalIgnoreCase) -or
                $key.Equals('referenceId', [StringComparison]::OrdinalIgnoreCase)) {
                $result[$key] = 'sha256:' + (Get-Sha256Hex ([string] $Value[$key])).Substring(0, 12)
            }
            else { $result[$key] = Protect-LogValue $Value[$key] }
        }
        return $result
    }
    if (Test-IsArray $Value) {
        $items = [System.Collections.Generic.List[object]]::new()
        foreach ($item in $Value) { $items.Add((Protect-LogValue $item)) }
        return (,$items)
    }
    return $Value
}

function Test-OtlpResponse {
    param([AllowNull()][string] $Raw)
    if ([string]::IsNullOrEmpty($Raw)) { return }
    try { $response = ConvertFrom-JsonText $Raw }
    catch { Throw-ApiError 'otlp_response_not_json' }
    if (-not (Test-IsMap $response)) { Throw-ApiError 'otlp_response_invalid' }
    $partial = Get-MapValue $response 'partialSuccess'
    if ($null -eq $partial) { $partial = Get-MapValue $response 'partial_success' }
    if ($null -eq $partial) { return }
    if (-not (Test-IsMap $partial)) { Throw-ApiError 'otlp_response_invalid' }
    foreach ($name in @('rejectedLogRecords', 'rejected_log_records')) {
        if ($partial.Contains($name) -and ([string] $partial[$name]) -notin @('', '0')) { Throw-ApiError 'otlp_logs_rejected' }
    }
    foreach ($name in @('errorMessage', 'error_message')) {
        if ($partial.Contains($name) -and -not [string]::IsNullOrEmpty([string] $partial[$name])) { Throw-ApiError 'otlp_logs_rejected' }
    }
}

function Send-OtlpRecord {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary] $Logger,
        [Parameter(Mandatory)][System.Collections.IDictionary] $Record
    )
    $body = @{
        resourceLogs = @(@{
            resource = @{ attributes = @(@{ key = 'service.name'; value = @{ stringValue = 'ahwotel-soti-exchange-access' } }) }
            scopeLogs = @(@{
                scope = @{ name = 'ahwotel.soti.exchange' }
                logRecords = @(@{
                    timeUnixNano = Get-UnixTimeNanosecondsString
                    severityText = $Record['severity']
                    body = @{ stringValue = ($Record | ConvertTo-Json -Depth 100 -Compress) }
                })
            })
        })
    } | ConvertTo-Json -Depth 100 -Compress
    Initialize-HttpRuntime
    $handler = [Net.Http.HttpClientHandler]::new()
    $client = [Net.Http.HttpClient]::new($handler)
    try {
        $client.Timeout = [TimeSpan]::FromSeconds($Logger['timeout'])
        $content = [Net.Http.StringContent]::new($body, $script:Utf8NoBom, 'application/json')
        try { $response = $client.PostAsync($Logger['endpoint'], $content).GetAwaiter().GetResult() }
        finally { $content.Dispose() }
        try {
            if (-not $response.IsSuccessStatusCode) { Throw-ApiError ("otlp_http_status:{0}" -f [int] $response.StatusCode) }
            Test-OtlpResponse ($response.Content.ReadAsStringAsync().GetAwaiter().GetResult())
        }
        finally { $response.Dispose() }
    }
    finally { $client.Dispose(); $handler.Dispose() }
}

function New-EventLogger {
    param([Parameter(Mandatory)][System.Collections.IDictionary] $Config, [Parameter(Mandatory)][string] $RunId, [switch] $RequireAudit)
    $logger = New-Map
    $logger['level'] = $Config['diagnostic']
    $logger['endpoint'] = Get-MapValue $Config 'otlpLogsEndpoint'
    $logger['timeout'] = $Config['requestTimeoutSeconds']
    $logger['runId'] = $RunId
    $auditPath = Get-MapValue $Config 'auditLogPath'
    if ($RequireAudit -and [string]::IsNullOrWhiteSpace([string] $auditPath)) { Throw-ContractError 'audit_log_required_for_apply' }
    if (-not [string]::IsNullOrWhiteSpace([string] $auditPath)) {
        $fullPath = [IO.Path]::GetFullPath([string] $auditPath)
        $parent = [IO.Path]::GetDirectoryName($fullPath)
        if (-not [IO.Directory]::Exists($parent)) { Throw-ContractError 'audit_log_parent_missing' }
        if ([IO.File]::Exists($fullPath) -and (([IO.File]::GetAttributes($fullPath) -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
            Throw-ContractError 'audit_log_symlink_forbidden'
        }
        if (-not [IO.File]::Exists($fullPath)) {
            $created = [IO.File]::Open($fullPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::Read)
            $created.Dispose(); Set-PrivateFilePermissions $fullPath
        }
        Test-SecretFilePermissions $fullPath
        $logger['auditPath'] = $fullPath
    }
    return $logger
}

function Write-AuditLine {
    param([Parameter(Mandatory)][System.Collections.IDictionary] $Logger, [Parameter(Mandatory)][string] $Line)
    if ($Logger.Contains('writeAudit')) { & $Logger['writeAudit'] $Line; return }
    $path = [string] (Get-MapValue $Logger 'auditPath')
    if ([string]::IsNullOrWhiteSpace($path)) { return }
    try {
        $stream = [IO.File]::Open($path, [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::Read)
        try {
            $bytes = $script:Utf8NoBom.GetBytes($Line + "`n")
            $stream.Write($bytes, 0, $bytes.Length); $stream.Flush($true)
        }
        finally { $stream.Dispose() }
    }
    catch { Throw-ApiError 'audit_log_write_failed' }
}

function Write-Event {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary] $Logger,
        [Parameter(Mandatory)][string] $Event,
        [ValidateSet('INFO', 'WARN', 'ERROR')][string] $Severity = 'INFO',
        [System.Collections.IDictionary] $Metadata = (New-Map)
    )
    $record = New-Map
    $record['timestamp'] = Get-UtcTimestamp
    $record['severity'] = $Severity
    $record['event'] = $Event
    $record['runId'] = $Logger['runId']
    $record['metadata'] = Protect-LogValue $Metadata
    $line = $record | ConvertTo-Json -Depth 100 -Compress
    if ($Severity -in @('WARN', 'ERROR')) { [Console]::Error.WriteLine($line) } else { [Console]::Out.WriteLine($line) }
    Write-AuditLine $Logger $line
    $endpoint = $Logger['endpoint']
    if (-not [string]::IsNullOrEmpty($endpoint)) {
        try {
            if ($Logger.Contains('sendOtlp')) { & $Logger['sendOtlp'] $record }
            else { Send-OtlpRecord $Logger $record }
        }
        catch {
            $warning = @{ timestamp = Get-UtcTimestamp; severity = 'WARN'; event = 'audit_sink_unavailable'; runId = $Logger['runId'] }
            [Console]::Error.WriteLine(($warning | ConvertTo-Json -Compress))
        }
    }
}

function Write-DiagnosticEvent {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary] $Logger,
        [Parameter(Mandatory)][string] $Event,
        [System.Collections.IDictionary] $Metadata = (New-Map),
        [switch] $VerboseOnly
    )
    if ($Logger['level'] -eq 'Verbose' -or ($Logger['level'] -eq 'Basic' -and -not $VerboseOnly)) {
        Write-Event $Logger $Event -Metadata $Metadata
    }
}

function New-SotiClient {
    param([Parameter(Mandatory)][System.Collections.IDictionary] $Config, [Parameter(Mandatory)][System.Collections.IDictionary] $Logger)
    if ([string]::IsNullOrEmpty($env:SOTI_CLIENT_ID) -or [string]::IsNullOrEmpty($env:SOTI_USERNAME)) {
        Throw-ContractError 'SOTI_CLIENT_ID_and_SOTI_USERNAME_required'
    }
    $client = New-Map
    $client['config'] = $Config
    $client['logger'] = $Logger
    $client['clientId'] = $env:SOTI_CLIENT_ID
    $client['username'] = $env:SOTI_USERNAME
    $client['clientSecret'] = Read-SecretValue $env:SOTI_CLIENT_SECRET_FILE 'SOTI client secret'
    $client['password'] = Read-SecretValue $env:SOTI_PASSWORD_FILE 'SOTI user password'
    $client['token'] = $null
    Initialize-HttpRuntime
    $handler = [Net.Http.HttpClientHandler]::new()
    $http = [Net.Http.HttpClient]::new($handler)
    $http.Timeout = [TimeSpan]::FromSeconds($Config['requestTimeoutSeconds'])
    $client['handler'] = $handler
    $client['http'] = $http
    return $client
}

function Close-SotiClient {
    param([AllowNull()][System.Collections.IDictionary] $Client)
    if ($null -ne $Client) {
        if ($Client.Contains('http')) { $Client['http'].Dispose() }
        if ($Client.Contains('handler')) { $Client['handler'].Dispose() }
    }
}

function Invoke-SotiRawRequest {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary] $Client,
        [Parameter(Mandatory)][string] $Method,
        [Parameter(Mandatory)][string] $Path,
        [AllowNull()][string] $Body,
        [System.Collections.IDictionary] $Headers = (New-Map),
        [string] $ContentType
    )
    $base = $Client['config']['baseUrl'].TrimEnd('/') + '/'
    $uri = [Uri]::new([Uri] $base, $Path.TrimStart('/'))
    $metadata = New-Map
    $metadata['method'] = $Method
    $metadata['pathHash'] = 'sha256:' + (Get-Sha256Hex $Path).Substring(0, 12)
    Write-DiagnosticEvent $Client['logger'] 'soti_request' -Metadata $metadata -VerboseOnly
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), $uri)
    try {
        foreach ($key in $Headers.Keys) { [void] $request.Headers.TryAddWithoutValidation([string] $key, [string] $Headers[$key]) }
        [void] $request.Headers.TryAddWithoutValidation('Accept', 'application/json')
        if ($null -ne $Body) { $request.Content = [Net.Http.StringContent]::new($Body, $script:Utf8NoBom, $ContentType) }
        try { $response = $Client['http'].SendAsync($request).GetAwaiter().GetResult() }
        catch { Throw-ApiError 'soti_transport_error' }
        try {
            $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if (-not $response.IsSuccessStatusCode) { Throw-ApiError ("soti_http_status:{0}" -f [int] $response.StatusCode) }
        }
        finally { $response.Dispose() }
    }
    finally { $request.Dispose() }
    if ([string]::IsNullOrEmpty($raw)) { return $null }
    try { return ConvertFrom-JsonText $raw }
    catch { Throw-ContractError 'soti_response_not_json' }
}

function Connect-SotiClient {
    param([Parameter(Mandatory)][System.Collections.IDictionary] $Client)
    $credentials = '{0}:{1}' -f $Client['clientId'], $Client['clientSecret']
    $basic = [Convert]::ToBase64String($script:Utf8NoBom.GetBytes($credentials))
    $form = 'grant_type=password&username={0}&password={1}' -f
        [Uri]::EscapeDataString($Client['username']), [Uri]::EscapeDataString($Client['password'])
    $headers = New-Map; $headers['Authorization'] = 'Basic ' + $basic
    $value = Invoke-SotiRawRequest $Client 'POST' 'token' $form $headers 'application/x-www-form-urlencoded'
    if (-not (Test-IsMap $value) -or -not $value.Contains('access_token') -or
        $value['access_token'] -isnot [string] -or [string]::IsNullOrEmpty($value['access_token'])) {
        Throw-ContractError 'token_response_missing_access_token'
    }
    $Client['token'] = $value['access_token']
    Write-Event $Client['logger'] 'soti_auth_succeeded'
}

function Invoke-SotiRequest {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary] $Client,
        [Parameter(Mandatory)][string] $Method,
        [Parameter(Mandatory)][string] $Path,
        [AllowNull()][object] $Payload
    )
    if ($null -eq $Client['token']) { Connect-SotiClient $Client }
    $headers = New-Map; $headers['Authorization'] = 'Bearer ' + $Client['token']
    $body = if ($null -eq $Payload) { $null } else { ConvertTo-CanonicalJson $Payload }
    return Invoke-SotiRawRequest $Client $Method $Path $body $headers 'application/json'
}

function Get-SotiDevices {
    param([Parameter(Mandatory)][System.Collections.IDictionary] $Client)
    return ConvertTo-DeviceList (Invoke-SotiRequest $Client 'GET' $Client['config']['devicesPath'] $null)
}

function Get-SotiDevice {
    param([Parameter(Mandatory)][System.Collections.IDictionary] $Client, [Parameter(Mandatory)][string] $Id)
    $path = $Client['config']['devicePathTemplate'].Replace('{deviceId}', [Uri]::EscapeDataString($Id))
    $value = Invoke-SotiRequest $Client 'GET' $path $null
    if (-not (Test-IsMap $value)) { Throw-ContractError 'device_response_must_be_object' }
    return $value
}

function Expand-ActionTemplate {
    param([AllowNull()][object] $Value, [Parameter(Mandatory)][string] $Id, [Parameter(Mandatory)][string] $ReferenceId)
    if ($null -eq $Value) { return $null }
    if (Test-IsMap $Value) {
        $result = New-Map
        foreach ($key in $Value.Keys) { $result[[string] $key] = Expand-ActionTemplate $Value[$key] $Id $ReferenceId }
        return $result
    }
    if (Test-IsArray $Value) {
        $items = [System.Collections.Generic.List[object]]::new()
        foreach ($item in $Value) { $items.Add((Expand-ActionTemplate $item $Id $ReferenceId)) }
        return (,$items)
    }
    if ($Value -is [string]) { return $Value.Replace('{deviceId}', $Id).Replace('{referenceId}', $ReferenceId) }
    return $Value
}

function Invoke-AllowExchange {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary] $Client,
        [Parameter(Mandatory)][string] $Id,
        [Parameter(Mandatory)][string] $ReferenceId
    )
    if (-not $Client['config']['actionContractVerified']) { Throw-ContractError 'action_contract_not_verified' }
    $path = $Client['config']['actionPathTemplate'].Replace('{deviceId}', [Uri]::EscapeDataString($Id))
    $payload = Expand-ActionTemplate $Client['config']['actionPayloadTemplate'] $Id $ReferenceId
    return Invoke-SotiRequest $Client 'POST' $path $payload
}

function Invoke-Inspection {
    param(
        [Parameter(Mandatory)][scriptblock] $ListDevices,
        [Parameter(Mandatory)][System.Collections.IDictionary] $Config,
        [Parameter(Mandatory)][System.Collections.IDictionary] $Logger,
        [DateTimeOffset] $Now = [DateTimeOffset]::UtcNow
    )
    $listed = & $ListDevices
    if (Test-IsMap $listed) { $listedItems = @($listed) }
    elseif (Test-IsArray $listed) { $listedItems = $listed }
    else { Throw-ContractError 'unsupported_device_collection_shape' }
    $decisionList = [System.Collections.Generic.List[object]]::new()
    foreach ($listedDevice in $listedItems) { $decisionList.Add((Test-DeviceEligibility $listedDevice $Config)) }
    $decisions = [System.Collections.Generic.List[object]]::new()
    foreach ($decision in @($decisionList | Sort-Object { $_['deviceId'] })) { $decisions.Add($decision) }
    $candidates = [System.Collections.Generic.List[object]]::new()
    foreach ($decision in $decisions) {
        if ($decision['decision'] -eq 'candidate') {
            $candidate = New-Map
            $candidate['deviceId'] = $decision['deviceId']; $candidate['referenceId'] = $decision['referenceId']
            $candidates.Add($candidate)
        }
    }
    $generatedAt = $Now.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ', [Globalization.CultureInfo]::InvariantCulture)
    $expiresAt = $Now.AddMinutes($Config['reportTtlMinutes']).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ', [Globalization.CultureInfo]::InvariantCulture)
    $policy = Get-PolicyFingerprint $Config
    $result = New-Map
    $result['schemaVersion'] = [long] $script:SchemaVersion
    $result['kind'] = 'soti-exchange-inspection'
    $result['runId'] = $Logger['runId']
    $result['generatedAt'] = $generatedAt
    $result['expiresAt'] = $expiresAt
    $result['policyFingerprint'] = $policy
    $result['snapshotId'] = Get-SnapshotId -PolicyFingerprint $policy -GeneratedAt $generatedAt -ExpiresAt $expiresAt -Candidates $candidates
    $result['candidates'] = $candidates
    $result['decisions'] = $decisions
    $metadata = New-Map; $metadata['devices'] = [long] $decisions.Count; $metadata['candidates'] = [long] $candidates.Count
    Write-Event $Logger 'inspection_completed' -Metadata $metadata
    return $result
}

function ConvertTo-DateTimeOffset {
    param([AllowNull()][object] $Value)
    if ($Value -isnot [string] -or $Value -notmatch '(?:Z|[+-][0-9]{2}:[0-9]{2})$') { Throw-ContractError 'invalid_timestamp' }
    $parsed = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse($Value, [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::None, [ref] $parsed)) {
        Throw-ContractError 'invalid_timestamp'
    }
    return $parsed.ToUniversalTime()
}

function Assert-Approval {
    param(
        [AllowNull()][object] $InspectionReport,
        [AllowNull()][object] $Approval,
        [Parameter(Mandatory)][System.Collections.IDictionary] $Config,
        [DateTimeOffset] $Now = [DateTimeOffset]::UtcNow
    )
    if (-not (Test-IsMap $InspectionReport) -or $InspectionReport['schemaVersion'] -ne $script:SchemaVersion -or
        $InspectionReport['kind'] -ne 'soti-exchange-inspection') { Throw-ContractError 'invalid_inspection_report' }
    if ($InspectionReport['policyFingerprint'] -ne (Get-PolicyFingerprint $Config)) { Throw-ContractError 'inspection_policy_changed' }
    $generatedAt = $InspectionReport['generatedAt']; [void] (ConvertTo-DateTimeOffset $generatedAt)
    $expiresAt = $InspectionReport['expiresAt']; $expiry = ConvertTo-DateTimeOffset $expiresAt
    $candidates = $InspectionReport['candidates']
    if (-not (Test-IsArray $candidates)) { Throw-ContractError 'invalid_inspection_candidates' }
    foreach ($item in $candidates) {
        if (-not (Test-IsMap $item) -or $item.Count -ne 2 -or -not $item.Contains('deviceId') -or -not $item.Contains('referenceId') -or
            $item['deviceId'] -isnot [string] -or [string]::IsNullOrEmpty($item['deviceId']) -or
            $item['referenceId'] -isnot [string] -or [string]::IsNullOrEmpty($item['referenceId'])) {
            Throw-ContractError 'invalid_inspection_candidates'
        }
    }
    $expected = Get-SnapshotId $InspectionReport['policyFingerprint'] $generatedAt $expiresAt $candidates
    if ($InspectionReport['snapshotId'] -ne $expected) { Throw-ContractError 'inspection_snapshot_invalid' }
    if ($expiry -le $Now.ToUniversalTime()) { Throw-ContractError 'inspection_report_expired' }
    if (-not (Test-IsMap $Approval) -or $Approval['schemaVersion'] -ne $script:SchemaVersion) { Throw-ContractError 'invalid_approval_file' }
    if ($Approval['snapshotId'] -ne $InspectionReport['snapshotId']) { Throw-ContractError 'approval_snapshot_mismatch' }
    $values = $Approval['approvals']
    if (-not (Test-IsArray $values) -or $values.Count -eq 0) { Throw-ContractError 'approval_list_empty' }
    $allowed = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($item in $candidates) { [void] $allowed.Add($item['deviceId'] + "`0" + $item['referenceId']) }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $result = [System.Collections.Generic.List[object]]::new()
    foreach ($item in $values) {
        if (-not (Test-IsMap $item) -or $item.Count -ne 2 -or -not $item.Contains('deviceId') -or -not $item.Contains('referenceId')) {
            Throw-ContractError 'invalid_approval_item'
        }
        $pair = [string] $item['deviceId'] + "`0" + [string] $item['referenceId']
        if (-not $allowed.Contains($pair) -or -not $seen.Add($pair)) { Throw-ContractError 'approval_not_unique_candidate' }
        $copy = New-Map; $copy['deviceId'] = [string] $item['deviceId']; $copy['referenceId'] = [string] $item['referenceId']; $result.Add($copy)
    }
    return (,$result)
}

function Wait-ForAllowed {
    param(
        [Parameter(Mandatory)][scriptblock] $GetDevice,
        [Parameter(Mandatory)][System.Collections.IDictionary] $Config,
        [Parameter(Mandatory)][string] $Id,
        [Parameter(Mandatory)][string] $ReferenceId
    )
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($true) {
        $decision = Test-DeviceEligibility (& $GetDevice $Id) $Config
        if (-not [string]::Equals([string] $decision['referenceId'], $ReferenceId, [StringComparison]::Ordinal)) {
            Throw-ContractError 'reference_id_changed_after_action'
        }
        if ($decision['decision'] -eq 'noop') { return $decision }
        if ($decision['decision'] -ne 'candidate') { Throw-ContractError 'unexpected_state_after_action' }
        if ($timer.Elapsed.TotalSeconds -ge $Config['verifyTimeoutSeconds']) { Throw-ApiError 'exchange_access_verification_timeout' }
        Start-Sleep -Seconds $Config['verifyPollSeconds']
    }
}

function Invoke-ApprovedActions {
    param(
        [Parameter(Mandatory)][scriptblock] $GetDevice,
        [Parameter(Mandatory)][scriptblock] $AllowExchange,
        [Parameter(Mandatory)][System.Collections.IDictionary] $Config,
        [Parameter(Mandatory)][System.Collections.IDictionary] $Logger,
        [Parameter(Mandatory)][System.Collections.IList] $Approved
    )
    if (-not $Config['actionContractVerified']) { Throw-ContractError 'action_contract_not_verified' }
    if ([string]::IsNullOrWhiteSpace([string] (Get-MapValue $Logger 'auditPath'))) { Throw-ContractError 'audit_log_required_for_apply' }
    $preflight = New-Map; $preflight['approvals'] = [long] $Approved.Count
    $preflight['auditSink'] = if ([string]::IsNullOrEmpty([string] $Logger['endpoint'])) { 'stdout+file' } else { 'stdout+file+otlp' }
    Write-Event $Logger 'apply_audit_preflight' -Metadata $preflight
    $results = @()
    foreach ($approvedItem in $Approved) {
        $id = $approvedItem['deviceId']; $referenceId = $approvedItem['referenceId']
        $current = Test-DeviceEligibility (& $GetDevice $id) $Config
        if ($current['decision'] -eq 'noop') {
            if (-not [string]::Equals([string] $current['referenceId'], $referenceId, [StringComparison]::Ordinal)) {
                Throw-ContractError 'approved_reference_id_changed'
            }
            $outcome = 'already_allowed'
        }
        elseif ($current['decision'] -eq 'candidate') {
            if (-not [string]::Equals([string] $current['referenceId'], $referenceId, [StringComparison]::Ordinal)) {
                Throw-ContractError 'approved_reference_id_changed'
            }
            $started = New-Map; $started['deviceId'] = $id; $started['referenceId'] = $referenceId
            Write-Event $Logger 'exchange_action_started' -Metadata $started
            try {
                try { [void] (& $AllowExchange $id $referenceId) }
                catch [SotiApiException] {
                    try { $reread = Test-DeviceEligibility (& $GetDevice $id) $Config }
                    catch { Throw-ApiError 'exchange_action_reread_failed_manual_review_required' }
                    if ($reread['decision'] -ne 'noop') {
                        $errorMetadata = New-Map; $errorMetadata['deviceId'] = $id
                        Write-Event $Logger 'exchange_action_ambiguous' -Severity ERROR -Metadata $errorMetadata
                        Throw-ApiError 'exchange_action_ambiguous_manual_review_required'
                    }
                }
                [void] (Wait-ForAllowed $GetDevice $Config $id $referenceId)
            }
            catch {
                $actionError = $_
                $failureOutcome = switch ($_.Exception.Message) {
                    'exchange_access_verification_timeout' { 'verification_timeout'; break }
                    'unexpected_state_after_action' { 'unexpected_state'; break }
                    'reference_id_changed_after_action' { 'unexpected_state'; break }
                    'exchange_action_ambiguous_manual_review_required' { 'ambiguous'; break }
                    'exchange_action_reread_failed_manual_review_required' { 'reread_failed'; break }
                    default { 'action_failed' }
                }
                $finished = New-Map; $finished['deviceId'] = $id; $finished['referenceId'] = $referenceId; $finished['outcome'] = $failureOutcome
                try { Write-Event $Logger 'exchange_action_finished' -Severity ERROR -Metadata $finished }
                catch [SotiApiException] {
                    if ($_.Exception.Message -eq 'audit_log_write_failed') { Throw-ApiError 'audit_log_write_failed_after_action_manual_review_required' }
                    throw
                }
                if ($actionError.Exception.Message -eq 'audit_log_write_failed') { Throw-ApiError 'audit_log_write_failed_after_action_manual_review_required' }
                throw $actionError
            }
            $finished = New-Map; $finished['deviceId'] = $id; $finished['referenceId'] = $referenceId; $finished['outcome'] = 'allowed'
            try { Write-Event $Logger 'exchange_action_finished' -Metadata $finished }
            catch [SotiApiException] {
                if ($_.Exception.Message -eq 'audit_log_write_failed') { Throw-ApiError 'audit_log_write_failed_after_action_manual_review_required' }
                throw
            }
            $outcome = 'allowed'
        }
        else { Throw-ContractError ("device_no_longer_eligible:{0}" -f $current['reason']) }
        $result = New-Map; $result['deviceId'] = $id; $result['referenceId'] = $referenceId; $result['outcome'] = $outcome
        $results += $result
        try { Write-Event $Logger 'exchange_access_result' -Metadata $result }
        catch [SotiApiException] {
            if ($_.Exception.Message -eq 'audit_log_write_failed') { Throw-ApiError 'audit_log_write_failed_after_action_manual_review_required' }
            throw
        }
    }
    $outputValue = New-Map
    $outputValue['schemaVersion'] = [long] $script:SchemaVersion
    $outputValue['kind'] = 'soti-exchange-apply-result'
    $outputValue['runId'] = $Logger['runId']
    $outputValue['completedAt'] = Get-UtcTimestamp
    $outputValue['results'] = $results
    return $outputValue
}

function Invoke-Verification {
    param(
        [Parameter(Mandatory)][scriptblock] $GetDevice,
        [Parameter(Mandatory)][System.Collections.IDictionary] $Config,
        [Parameter(Mandatory)][System.Collections.IDictionary] $Logger,
        [Parameter(Mandatory)][System.Collections.IList] $Approved
    )
    $results = @()
    foreach ($approvedItem in $Approved) {
        $decision = Test-DeviceEligibility (& $GetDevice $approvedItem['deviceId']) $Config
        $result = New-Map
        $result['deviceId'] = $approvedItem['deviceId']; $result['referenceId'] = $approvedItem['referenceId']
        $result['allowed'] = [string]::Equals([string] $decision['referenceId'], [string] $approvedItem['referenceId'], [StringComparison]::Ordinal) -and
            $decision['decision'] -eq 'noop'
        $result['reason'] = $decision['reason']; $results += $result
    }
    $metadata = New-Map; $metadata['devices'] = [long] $results.Count
    $metadata['allowed'] = [long] @($results | Where-Object { $_['allowed'] }).Count
    Write-Event $Logger 'verification_completed' -Metadata $metadata
    $outputValue = New-Map
    $outputValue['schemaVersion'] = [long] $script:SchemaVersion
    $outputValue['kind'] = 'soti-exchange-verification'
    $outputValue['runId'] = $Logger['runId']; $outputValue['completedAt'] = Get-UtcTimestamp; $outputValue['results'] = $results
    return $outputValue
}

function Assert-CommandArguments {
    param([Parameter(Mandatory)][string] $SelectedCommand)
    switch ($SelectedCommand) {
        'auth-check' { if ([string]::IsNullOrEmpty($DeviceId)) { Throw-ContractError 'auth_check_requires_device_id' } }
        'inspect' { if ([string]::IsNullOrEmpty($Output)) { Throw-ContractError 'inspect_requires_output' } }
        'apply' {
            if ([string]::IsNullOrEmpty($Report) -or [string]::IsNullOrEmpty($Approvals) -or
                [string]::IsNullOrEmpty($Output) -or -not [string]::Equals($Confirm, 'APPLY', [StringComparison]::Ordinal)) {
                Throw-ContractError 'apply_arguments_invalid'
            }
        }
        'verify' {
            if ([string]::IsNullOrEmpty($Report) -or [string]::IsNullOrEmpty($Approvals) -or [string]::IsNullOrEmpty($Output)) {
                Throw-ContractError 'verify_arguments_invalid'
            }
        }
    }
}

function Invoke-SotiExchangeAccessMain {
    $runId = [Guid]::NewGuid().ToString()
    $client = $null
    try {
        Assert-WindowsPowerShell51
        Assert-CommandArguments $Command
        $configValue = Assert-Config (Read-JsonFile $Config)
        $logger = New-EventLogger $configValue $runId -RequireAudit:($Command -eq 'apply')
        $approved = $null
        if ($Command -in @('apply', 'verify')) {
            $approved = Assert-Approval (Read-JsonFile $Report) (Read-JsonFile $Approvals) $configValue
            if ($Command -eq 'apply' -and -not $configValue['actionContractVerified']) { Throw-ContractError 'action_contract_not_verified' }
        }
        $client = New-SotiClient $configValue $logger
        $getDevice = { param($id) Get-SotiDevice $client $id }
        switch ($Command) {
            'auth-check' {
                Connect-SotiClient $client
                $decision = Test-DeviceEligibility (Get-SotiDevice $client $DeviceId) $configValue
                $metadata = New-Map; $metadata['deviceReadable'] = $true; $metadata['decision'] = $decision['decision']; $metadata['reason'] = $decision['reason']
                Write-Event $logger 'auth_check_completed' -Metadata $metadata
            }
            'inspect' {
                $inspection = Invoke-Inspection { Get-SotiDevices $client } $configValue $logger
                Write-AtomicJson $Output $inspection
            }
            'apply' {
                $result = Invoke-ApprovedActions -GetDevice $getDevice -AllowExchange {
                    param($id, $reference) Invoke-AllowExchange $client $id $reference
                } -Config $configValue -Logger $logger -Approved $approved
                Write-AtomicJson $Output $result
            }
            'verify' {
                $result = Invoke-Verification -GetDevice $getDevice -Config $configValue -Logger $logger -Approved $approved
                Write-AtomicJson $Output $result
            }
        }
        return 0
    }
    catch [SotiContractException] {
        $errorRecord = @{ timestamp = Get-UtcTimestamp; severity = 'ERROR'; event = 'command_failed'; runId = $runId; error = $_.Exception.Message }
        [Console]::Error.WriteLine(($errorRecord | ConvertTo-Json -Compress)); return 2
    }
    catch [SotiApiException] {
        $errorRecord = @{ timestamp = Get-UtcTimestamp; severity = 'ERROR'; event = 'command_failed'; runId = $runId; error = $_.Exception.Message }
        [Console]::Error.WriteLine(($errorRecord | ConvertTo-Json -Compress)); return 2
    }
    catch {
        $errorRecord = @{ timestamp = Get-UtcTimestamp; severity = 'ERROR'; event = 'internal_error'; runId = $runId; error = 'unexpected_failure' }
        [Console]::Error.WriteLine(($errorRecord | ConvertTo-Json -Compress)); return 3
    }
    finally { Close-SotiClient $client }
}

if ($MyInvocation.InvocationName -ne '.') {
    exit (Invoke-SotiExchangeAccessMain)
}
