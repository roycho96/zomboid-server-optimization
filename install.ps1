param(
    [ValidateSet('Status','Install','Update','Uninstall')][string]$Mode = 'Status',
    [string]$GameDir = 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid',
    [string]$Manifest = (Join-Path $PSScriptRoot 'manifest.json'),
    [string]$Java = ''
)
$ErrorActionPreference = 'Stop'
$gameRoot = [IO.Path]::GetFullPath($GameDir).TrimEnd('\')
$packageRoot = Join-Path $gameRoot 'roy-server-opt'
$statePath = Join-Path $packageRoot 'installed.json'
$entry = 'roy-server-opt/roy-server-opt-0.6.0.jar'
$oldEntry = 'roy-server-opt/roy-server-opt-0.2.0.jar'
$previousEntry = 'roy-server-opt/roy-server-opt-0.3.0.jar'
$previousFourEntry = 'roy-server-opt/roy-server-opt-0.4.0.jar'
$previousFiveEntry = 'roy-server-opt/roy-server-opt-0.5.0.jar'
$serverEntries = @($entry,$oldEntry,$previousEntry,$previousFourEntry,$previousFiveEntry)
$nativeEntry = 'roy-ballistics/roy-ballistics-native-0.1.0.jar'
$legacyEntries = @('roy-core/roy-core-0.1.0.jar','roy-net/roy-vehicle-net-0.1.0.jar',$oldEntry,$previousEntry,$previousFourEntry,$previousFiveEntry)
$launcherNames = @('ProjectZomboid64.json','ProjectZomboid64.bat','ProjectZomboid64ShowConsole.bat')
$utf8 = New-Object Text.UTF8Encoding($false, $true)

function Hash([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Hash-Bytes([byte[]]$Bytes) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($algorithm.ComputeHash($Bytes)).Replace('-','').ToLowerInvariant() }
    finally { $algorithm.Dispose() }
}

function Read-Text([string]$Path) { return [IO.File]::ReadAllText($Path, $utf8) }
function Read-Json([string]$Path) { return (Read-Text $Path | ConvertFrom-Json) }
function Json($Value) { return ($Value | ConvertTo-Json -Depth 20) }
function Is-Hash($Value) { return ($Value -is [string] -and $Value -cmatch '^[0-9a-f]{64}$') }

function Write-BytesAtomic([string]$Path, [byte[]]$Bytes) {
    $temporary = $Path + '.roy-server-opt-writing-' + [guid]::NewGuid().ToString('N')
    try {
        [IO.File]::WriteAllBytes($temporary, $Bytes)
        if (Test-Path -LiteralPath $Path) { [IO.File]::Replace($temporary, $Path, [NullString]::Value) }
        else { [IO.File]::Move($temporary, $Path) }
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
}

function Write-TextAtomic([string]$Path, [string]$Text) {
    Write-BytesAtomic $Path ($utf8.GetBytes($Text))
}

function Equal-Sequence($A, $B) { return ((@($A) -join "`n") -ceq (@($B) -join "`n")) }

function Check-Classpath($Classpath, [bool]$Clean = $false, [bool]$Batch = $false) {
    $values = @($Classpath)
    $allowed = @('pzopt/aot/pzopt.jar','projectzomboid.jar',$nativeEntry)
    if ($Batch) { $allowed += './' }
    if (!$Clean) { $allowed += $legacyEntries; $allowed += $entry }
    if ($values.Count -eq 0 -or $values[-1] -cne 'projectzomboid.jar' -or
        @($values | Where-Object { $_ -cnotin $allowed }).Count -ne 0 -or
        @($values | Select-Object -Unique).Count -ne $values.Count) {
        throw '확인되지 않은 classpath입니다. launcher 파일은 변경하지 않았습니다.'
    }
}

function Batch-Classpath([string]$Text) {
    $matches = [regex]::Matches($Text, '(?im)^SET PZ_CLASSPATH=([^\r\n]*)')
    if ($matches.Count -ne 1) { throw '각 BAT에는 SET PZ_CLASSPATH 설정이 정확히 하나 있어야 합니다.' }
    return @($matches[0].Groups[1].Value.Split(';'))
}

function With-BatchClasspath([string]$Text, $Classpath) {
    return [regex]::Replace($Text, '(?im)^SET PZ_CLASSPATH=[^\r\n]*', ('SET PZ_CLASSPATH=' + (@($Classpath) -join ';')))
}

function Without-Aot([string]$Text) {
    $pattern = '(?i)(?<!\S)(?:"(?:-XX:AOTCache=|-Xlog:aot=)[^"\r\n]*"|(?:-XX:AOTCache=|-Xlog:aot=)(?:"[^"\r\n]*"|[^\s"]+)|-Xlog:aot)(?=\s|$)'
    return [regex]::Replace($Text, $pattern, '')
}

function Read-Launchers {
    $texts = @{}
    $bytes = @{}
    $batchClasspaths = @{}
    foreach ($name in $launcherNames) {
        $path = Join-Path $gameRoot $name
        if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw ('launcher 파일이 없습니다: ' + $path) }
        $bytes[$name] = [IO.File]::ReadAllBytes($path)
        $texts[$name] = Read-Text $path
    }
    $launcher = $texts[$launcherNames[0]] | ConvertFrom-Json
    $classpath = @($launcher.classpath)
    Check-Classpath $classpath
    if ($null -eq $launcher.vmArgs) { throw 'launcher의 vmArgs 설정이 없습니다.' }
    foreach ($name in $launcherNames | Select-Object -Skip 1) {
        $batchClasspaths[$name] = @(Batch-Classpath $texts[$name])
        Check-Classpath $batchClasspaths[$name] $false $true
    }
    return [pscustomobject]@{ texts=$texts; bytes=$bytes; classpath=$classpath; batch_classpaths=$batchClasspaths }
}

function Clean-Launchers($Current) {
    $cleanClasspath = @($Current.classpath | Where-Object { $_ -cnotin $legacyEntries -and $_ -cne $entry })
    Check-Classpath $cleanClasspath $true
    $launcher = $Current.texts[$launcherNames[0]] | ConvertFrom-Json
    $launcher.classpath = $cleanClasspath
    $texts = @{}
    $batchClasspaths = @{}
    $texts[$launcherNames[0]] = Json $launcher
    foreach ($name in $launcherNames | Select-Object -Skip 1) {
        $batchClasspaths[$name] = @($Current.batch_classpaths[$name] | Where-Object { $_ -cnotin $legacyEntries -and $_ -cne $entry })
        Check-Classpath $batchClasspaths[$name] $true $true
        $texts[$name] = With-BatchClasspath $Current.texts[$name] $batchClasspaths[$name]
    }
    return [pscustomobject]@{ texts=$texts; classpath=$cleanClasspath; batch_classpaths=$batchClasspaths }
}

function Native-First($Classpath) {
    return @($Classpath | Where-Object { $_ -ceq $nativeEntry }) + @($Classpath | Where-Object { $_ -cne $nativeEntry })
}

function Check-NativeCompanion($Current) {
    $all = @($Current.classpath)
    foreach ($name in $launcherNames | Select-Object -Skip 1) { $all += @($Current.batch_classpaths[$name]) }
    if ($all -cnotcontains $nativeEntry) { return }
    $nativeState = Read-Json (Join-Path $gameRoot 'roy-ballistics\installed.json')
    if ($nativeState.package_id -cne 'roy-ballistics-native' -or $nativeState.version -cne '0.1.0' -or
        $nativeState.status -ne 'installed' -or !(Is-Hash $nativeState.artifact_sha256) -or
        ![string]::Equals($nativeState.game_root,$gameRoot,[StringComparison]::OrdinalIgnoreCase) -or
        (Hash (Join-Path $gameRoot $nativeEntry)) -cne $nativeState.artifact_sha256) {
        throw '함께 설치된 탄도 backend의 jar와 설치 기록이 다릅니다.'
    }
}

function Remove-OwnEntries($Current) {
    $texts = @{}
    $json = $Current.texts[$launcherNames[0]] | ConvertFrom-Json
    $json.classpath = @($Current.classpath | Where-Object { $_ -cnotin $serverEntries })
    $texts[$launcherNames[0]] = Json $json
    foreach ($name in $launcherNames | Select-Object -Skip 1) {
        $values = @($Current.batch_classpaths[$name] | Where-Object { $_ -cnotin $serverEntries })
        $texts[$name] = With-BatchClasspath $Current.texts[$name] $values
    }
    return $texts
}

function Check-State($State) {
    if ($State.schema_version -notin @(1,2) -or $State.package_id -cne 'roy-server-opt' -or $State.version -notin @('0.2.0','0.3.0','0.4.0','0.5.0','0.6.0') -or
        $State.status -notin @('installed','uninstalled') -or !(Is-Hash $State.artifact_sha256) -or
        !(Is-Hash $State.game_jar_sha256) -or $State.transaction -cnotmatch '^[0-9]{8}-[0-9]{6}-[0-9a-f]{32}$' -or
        ![string]::Equals($State.game_root, $gameRoot, [StringComparison]::OrdinalIgnoreCase) -or
        @($State.files).Count -ne 3 -or !(Equal-Sequence @($State.files.name | Sort-Object) @($launcherNames | Sort-Object))) {
        throw '서버 최적화 설치 기록이 현재 폴더와 다르거나 손상됐습니다.'
    }
    $backupRoot = Join-Path $packageRoot ('backups\' + $State.transaction)
    foreach ($record in $State.files) {
        foreach ($field in @('raw_sha256','clean_sha256','installed_sha256')) {
            if (!(Is-Hash $record.$field)) { throw '설치 기록의 hash 형식이 올바르지 않습니다.' }
        }
        foreach ($kind in @('raw','clean')) {
            $path = Join-Path $backupRoot ($kind + '\' + $record.name)
            $expected = if ($kind -eq 'raw') { $record.raw_sha256 } else { $record.clean_sha256 }
            if ((Hash $path) -cne $expected) { throw ('백업 파일의 hash가 다릅니다: ' + $path) }
        }
    }
    $clean = @{}
    foreach ($name in $launcherNames) { $clean[$name] = Read-Text (Join-Path $backupRoot ('clean\' + $name)) }
    $cleanJson = $clean[$launcherNames[0]] | ConvertFrom-Json
    Check-Classpath @($cleanJson.classpath) $true
    foreach ($name in $launcherNames | Select-Object -Skip 1) {
        Check-Classpath @(Batch-Classpath $clean[$name]) $true $true
    }
    return $backupRoot
}

function Check-CurrentState($State, [string]$HashField) {
    foreach ($record in $State.files) {
        $target = Join-Path $gameRoot $record.name
        if ((Hash $target) -cne $record.$HashField) { throw ('설치 기록 이후 launcher가 변경됐습니다: ' + $target) }
    }
}

function Rollback-Launchers($Snapshot, $PreviousState) {
    $failures = New-Object 'Collections.Generic.List[string]'
    foreach ($name in $launcherNames) {
        try {
            $path = Join-Path $gameRoot $name
            if (!(Test-Path -LiteralPath $path) -or (Hash $path) -cne (Hash-Bytes $Snapshot[$name])) { Write-BytesAtomic $path $Snapshot[$name] }
        }
        catch { $failures.Add($_.Exception.Message) }
    }
    try {
        if ($null -ne $PreviousState) {
            if (!(Test-Path -LiteralPath $statePath) -or (Hash $statePath) -cne (Hash-Bytes $PreviousState)) { Write-BytesAtomic $statePath $PreviousState }
        }
        elseif (Test-Path -LiteralPath $statePath) { Remove-Item -LiteralPath $statePath }
    } catch { $failures.Add($_.Exception.Message) }
    if ($failures.Count -ne 0) { throw ('백업을 이용한 수동 복구가 필요합니다: ' + ($failures -join '; ')) }
}

function Recipe-File([string]$Relative) {
    $root = [IO.Path]::GetFullPath((Split-Path -Parent $Manifest)).TrimEnd('\')
    $path = [IO.Path]::GetFullPath((Join-Path $root $Relative))
    if (!$path.StartsWith(($root + '\'), [StringComparison]::OrdinalIgnoreCase)) { throw '패키지 파일 경로가 올바르지 않습니다.' }
    return $path
}

function Generate-Patch([string]$Output) {
    $recipe = Recipe-File 'payload/roy-server-recipe-0.6.0.jar'
    $helpers = Recipe-File 'payload/roy-server-helpers-0.6.0.jar'
    $game = Join-Path $gameRoot 'projectzomboid.jar'
    $classpath = @($recipe,(Recipe-File 'tools/asm-9.9.1.jar'),(Recipe-File 'tools/asm-tree-9.9.1.jar'),$game) -join ';'
    $optimizerSource = if (Test-Path -LiteralPath $optimizer) { $optimizer } else { '-' }
    $sourceText = @(& $Java '-Xverify:all' '-cp' $classpath 'VerifyServerVocalsSources' $game $optimizerSource)
    if ($LASTEXITCODE -ne 0) { throw '게임과 PZ Optimizer의 서버 음성 갱신 조건이 검증되지 않았습니다.' }
    $sourceContract = ($sourceText -join "`n") | ConvertFrom-Json
    if ($sourceContract.verified -ne $true) { throw '서버 음성 갱신 검증 결과가 올바르지 않습니다.' }
    $membershipText = @(& $Java '-Xverify:all' '-cp' $classpath 'VerifyNativeMembershipSources' $game $optimizerSource)
    if ($LASTEXITCODE -ne 0) { throw '게임과 PZ Optimizer의 좀비 목록 검색 조건이 검증되지 않았습니다.' }
    $membershipContract = ($membershipText -join "`n") | ConvertFrom-Json
    if ($membershipContract.verified -ne $true) { throw '좀비 목록 검색 검증 결과가 올바르지 않습니다.' }
    $extraContracts = @{}
    foreach ($verifier in @('VerifyZombieRequestSources','VerifyServerDirectionSources','VerifyClasspathSources')) {
        $contractArgs = @('-Xverify:all','-cp',$classpath,$verifier,$game,$optimizerSource)
        if ($verifier -in @('VerifyZombieRequestSources','VerifyClasspathSources')) { $contractArgs += $gameRoot }
        $lines = @(& $Java @contractArgs)
        if ($LASTEXITCODE -ne 0) { throw ('새 서버 최적화의 소스 계약을 검증하지 못했습니다: ' + $verifier) }
        $contract = ($lines -join [Environment]::NewLine) | ConvertFrom-Json
        if ($contract.verified -ne $true) { throw ('소스 계약 결과가 올바르지 않습니다: ' + $verifier) }
        $extraContracts[$verifier] = $contract
    }
    $source = $manifestData.source_classes
    $generated = $manifestData.generated_classes
    $engineClasses = @('zombie/iso/IsoCell.class','zombie/network/PacketsCache.class','zombie/popman/NetworkZombiePacker.class','zombie/iso/Vector2.class','zombie/network/ServerLOS.class')
    $args = @('-Xverify:all','-cp',$classpath,'RoyServerRecipe',$game,$helpers,$Output,$manifestData.game_jar_sha256)
    foreach ($name in $engineClasses) { $args += $source.PSObject.Properties[$name].Value }
    foreach ($name in $engineClasses) { $args += $generated.PSObject.Properties[$name].Value }
    $text = @(& $Java @args)
    if ($LASTEXITCODE -ne 0) { throw '설치된 게임 파일의 로컬 패치 생성에 실패했습니다. launcher는 변경하지 않았습니다.' }
    $report = ($text -join "`n") | ConvertFrom-Json
    if ((Hash $Output) -cne $report.artifact_sha256 -or $report.artifact_sha256 -cne $manifestData.generated_artifact_sha256 -or
        $report.game_server_transformed -ne $false -or $report.packet_inbox_included -ne $false) { throw '생성된 패치의 hash 또는 범위가 일치하지 않습니다.' }
    $expected = @($manifestData.expected_generated_entries.PSObject.Properties)
    if (@($report.entries.PSObject.Properties).Count -ne $expected.Count) { throw '생성된 패치의 파일 목록이 다릅니다.' }
    foreach ($property in $expected) {
        if ($report.entries.PSObject.Properties[$property.Name].Value -cne $property.Value) { throw ('생성된 클래스가 다릅니다: ' + $property.Name) }
    }
    $verifyClasspath = @($recipe,$Output)
    if (Test-Path -LiteralPath $optimizer) { $verifyClasspath += $optimizer }
    $verifyClasspath += $game
    $text = @(& $Java '-Xverify:all' '-cp' ($verifyClasspath -join ';') 'VerifyServerPatch' $Output $game)
    if ($LASTEXITCODE -ne 0) { throw '게임 JRE의 생성 클래스 검증에 실패했습니다. launcher는 변경하지 않았습니다.' }
    $verified = ($text -join "`n") | ConvertFrom-Json
    if ($verified.verified -ne $true -or $verified.game_server_original -ne $true -or $verified.vehicle_patch_absent -ne $true -or $verified.packet_batching -ne $false) { throw '원본 queue 및 차량 클래스 검증이 통과하지 않았습니다.' }
    Write-TextAtomic ($Output + '.verification.json') (Json ([pscustomobject]@{ build=$report; runtime=$verified; vocals_source_contract=$sourceContract; membership_source_contract=$membershipContract; extra_source_contracts=$extraContracts }))
    return $report
}

if ($Mode -eq 'Status') {
    $launcher = Read-Json (Join-Path $gameRoot $launcherNames[0])
    $state = if (Test-Path -LiteralPath $statePath) { Read-Json $statePath } else { $null }
    [pscustomobject]@{
        Installed=(@($launcher.classpath | Where-Object { $_ -cin $serverEntries }).Count -gt 0)
        Version=$(if ($null -ne $state) { $state.version } else { $null })
        UpdateAvailable=($null -ne $state -and ($state.version -ne '0.6.0' -or @($launcher.classpath)[0] -cnotin @($entry,$nativeEntry)))
        LegacyVehicleEnabled=(@($launcher.classpath) -ccontains $legacyEntries[1])
        LegacyCoreEnabled=(@($launcher.classpath) -ccontains $legacyEntries[0])
        RecordStatus=$(if ($null -ne $state) { $state.status } else { 'missing' })
        Settings=(Join-Path $packageRoot 'roy-core.properties')
    } | ConvertTo-Json
    return
}

if ($Mode -eq 'Uninstall') {
    if (!(Test-Path -LiteralPath $statePath)) { throw '완료된 서버 최적화 설치 기록이 없습니다.' }
    $lock = [IO.File]::Open((Join-Path $gameRoot 'roy-launcher-update.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try {
        $previousState = [IO.File]::ReadAllBytes($statePath)
        $state = Read-Json $statePath
        $backupRoot = Check-State $state
        $current = Read-Launchers
        Check-NativeCompanion $current
        if ($state.status -eq 'uninstalled') {
            $all = @($current.classpath)
            foreach ($name in $launcherNames | Select-Object -Skip 1) { $all += @($current.batch_classpaths[$name]) }
            if (@($all | Where-Object { $_ -cin $serverEntries }).Count -gt 0) { throw '해제 기록과 달리 서버 최적화 entry가 남아 있습니다.' }
            Write-Output '서버 최적화가 이미 해제돼 있습니다. 이전 차량 보정도 다시 활성화하지 않습니다.'
            return
        }
        if ((Hash (Join-Path $gameRoot 'projectzomboid.jar')) -cne $state.game_jar_sha256) { throw '게임 버전이 변경됐습니다. 해제 전에 launcher와 백업을 확인해야 합니다.' }
        $snapshot = @{}
        foreach ($name in $launcherNames) { $snapshot[$name] = [IO.File]::ReadAllBytes((Join-Path $gameRoot $name)) }
        try {
            $texts = Remove-OwnEntries $current
            foreach ($record in $state.files) {
                Write-TextAtomic (Join-Path $gameRoot $record.name) $texts[$record.name]
            }
            $check = Read-Launchers
            Check-NativeCompanion $check
            $all = @($check.classpath)
            foreach ($name in $launcherNames | Select-Object -Skip 1) { $all += @($check.batch_classpaths[$name]) }
            if (@($all | Where-Object { $_ -cin $serverEntries }).Count -gt 0) { throw '해제 후 서버 최적화 entry가 남아 있습니다.' }
            $state.status = 'uninstalled'
            $state | Add-Member -NotePropertyName uninstalled_at -NotePropertyValue (Get-Date -Format o) -Force
            Write-TextAtomic $statePath (Json $state)
        } catch {
            $originalFailure = $_
            Rollback-Launchers $snapshot $previousState
            throw $originalFailure
        }
        Write-Output '서버 최적화 entry만 제거했습니다. 현재 탄도 backend·JVM 인수·jar·백업은 보존했습니다.'
        return
    } finally { $lock.Dispose() }
}

$manifestData = Read-Json $Manifest
if ($manifestData.schema_version -ne 2 -or $manifestData.version -cne '0.6.0') { throw '지원하지 않는 패키지 manifest입니다.' }
foreach ($name in @('generated_artifact_sha256','game_jar_sha256','optimizer_jar_sha256')) {
    if (!(Is-Hash $manifestData.$name)) { throw ('manifest hash 형식이 올바르지 않습니다: ' + $name) }
}
foreach ($name in $legacyEntries) {
    $property = $manifestData.legacy_jars.PSObject.Properties[$name]
    if ($null -eq $property -or !(Is-Hash $property.Value)) { throw ('이전 jar의 확인된 hash가 없습니다: ' + $name) }
}
foreach ($name in @('iso_cell','rate_windows','rate_cache_integration','shared_settings')) {
    if ($manifestData.validation.$name -ne $true) { throw ('필수 동작 검증 기록이 없습니다: ' + $name) }
}
$payloadNames = @('payload/roy-server-helpers-0.6.0.jar','payload/roy-server-recipe-0.6.0.jar','tools/asm-9.9.1.jar','tools/asm-tree-9.9.1.jar','licenses/ASM-BSD-3-Clause.txt')
if (@($manifestData.files.PSObject.Properties).Count -ne $payloadNames.Count) { throw '패키지 payload 목록이 다릅니다.' }
foreach ($name in $payloadNames) {
    $expected = $manifestData.files.PSObject.Properties[$name].Value
    if (!(Is-Hash $expected) -or (Hash (Recipe-File $name)) -cne $expected) { throw ('패키지 파일 hash가 다릅니다: ' + $name) }
}
foreach ($name in @('zombie/iso/IsoCell.class','zombie/network/PacketsCache.class','zombie/popman/NetworkZombiePacker.class','zombie/iso/Vector2.class','zombie/network/ServerLOS.class')) {
    if (!(Is-Hash $manifestData.source_classes.PSObject.Properties[$name].Value) -or !(Is-Hash $manifestData.generated_classes.PSObject.Properties[$name].Value)) { throw '게임 클래스의 검증 hash가 없습니다.' }
}
if ($manifestData.game_classes_distributed -ne $false -or $manifestData.game_server_transformed -ne $false -or $manifestData.packet_inbox_included -ne $false) { throw '패키지 범위가 서버 처리 최적화와 일치하지 않습니다.' }
if ((Hash (Join-Path $gameRoot 'projectzomboid.jar')) -cne $manifestData.game_jar_sha256) { throw '확인된 게임 버전의 hash와 다릅니다.' }
$optimizer = Join-Path $gameRoot 'pzopt\aot\pzopt.jar'
if ((Test-Path -LiteralPath $optimizer) -and (Hash $optimizer) -cnotin @($manifestData.known_optimizer_sha256)) { throw '확인되지 않은 PZ Optimizer 버전입니다.' }
if (!$Java) { $Java = Join-Path $gameRoot 'jre64\bin\java.exe' }
if (!(Test-Path -LiteralPath $Java -PathType Leaf)) { throw '게임의 jre64/bin/java.exe를 찾을 수 없습니다.' }
$before = Read-Launchers
Check-NativeCompanion $before
$allClasspath = @($before.classpath)
foreach ($name in $launcherNames | Select-Object -Skip 1) { $allClasspath += $before.batch_classpaths[$name] }
foreach ($name in $legacyEntries) {
    $path = Join-Path $gameRoot $name
    if ($allClasspath -ccontains $name -and !(Test-Path -LiteralPath $path)) { throw ('classpath에 지정된 이전 jar가 없습니다: ' + $name) }
    if ((Test-Path -LiteralPath $path) -and (Hash $path) -cne $manifestData.legacy_jars.PSObject.Properties[$name].Value) { throw ('확인되지 않은 이전 jar의 hash입니다: ' + $name) }
}
if ($allClasspath -ccontains 'pzopt/aot/pzopt.jar' -and !(Test-Path -LiteralPath $optimizer)) { throw 'classpath에 지정된 PZ Optimizer jar가 없습니다.' }
if (!(Test-Path -LiteralPath $packageRoot)) { New-Item -ItemType Directory -Path $packageRoot | Out-Null }
$lock = [IO.File]::Open((Join-Path $gameRoot 'roy-launcher-update.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
    foreach ($name in $launcherNames) {
        if ((Hash (Join-Path $gameRoot $name)) -cne (Hash-Bytes $before.bytes[$name])) {
            throw '검사 도중 launcher가 변경됐습니다. 다른 설치 도구를 닫은 뒤 다시 실행하세요.'
        }
    }
    Check-NativeCompanion $before
    $previousState = if (Test-Path -LiteralPath $statePath) { [IO.File]::ReadAllBytes($statePath) } else { $null }
    $existing = if ($null -ne $previousState) { Read-Json $statePath } else { $null }
    if ($allClasspath -ccontains $entry) {
        if ($null -eq $existing) { throw '서버 최적화 classpath는 있지만 완료된 설치 기록이 없습니다.' }
        Check-State $existing | Out-Null
        if ($existing.status -ne 'installed' -or $existing.version -ne '0.6.0') { throw '기존 설치 기록이 현재 classpath와 다릅니다.' }
        if ((Hash (Join-Path $packageRoot 'roy-server-opt-0.6.0.jar')) -cne $existing.artifact_sha256) { throw '설치된 서버 패치 jar가 변경됐습니다.' }
        $sameLaunchers = $true
        foreach ($record in $existing.files) { if ((Hash (Join-Path $gameRoot $record.name)) -cne $record.installed_sha256) { $sameLaunchers = $false } }
        if ($sameLaunchers -and $existing.recipe_manifest_sha256 -ceq (Hash $Manifest)) {
            Write-Output '검증된 서버 최적화 0.6.0이 이미 설치돼 있습니다.'
            return
        }
        if ($Mode -ne 'Update') { throw 'launcher 또는 recipe가 변경됐습니다. 새 패키지의 Update.cmd를 실행하세요.' }
    }
    if ($null -ne $existing) {
        Check-State $existing | Out-Null
        if ($existing.status -eq 'installed') {
            if ($Mode -ne 'Update') { throw '기존 설치를 보존하려면 새 패키지의 Update.cmd를 실행하세요.' }
            if ($existing.version -in @('0.2.0','0.3.0','0.4.0','0.5.0')) {
                $recordEntry = 'roy-server-opt/roy-server-opt-' + $existing.version + '.jar'
                if ($allClasspath -cnotcontains $recordEntry -or $existing.artifact_sha256 -cne $manifestData.legacy_jars.PSObject.Properties[$recordEntry].Value) { throw '이전 버전의 설치 기록과 classpath가 다릅니다.' }
            } elseif ($allClasspath -cnotcontains $entry) { throw '설치 기록에 해당하는 classpath entry가 없습니다.' }
        } elseif ($Mode -eq 'Update') { throw '현재 설치가 해제돼 있습니다. Install.cmd를 사용하세요.' }
    } elseif ($Mode -eq 'Update' -or @($allClasspath | Where-Object { $_ -cin @($oldEntry,$previousEntry,$previousFourEntry,$previousFiveEntry) }).Count -gt 0) {
        throw '업데이트할 완료된 설치 기록이 없습니다.'
    }
    $clean = Clean-Launchers $before
    $transaction = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N')
    $backupRoot = Join-Path $packageRoot ('backups\' + $transaction)
    New-Item -ItemType Directory -Path (Join-Path $backupRoot 'raw'), (Join-Path $backupRoot 'clean') -Force | Out-Null
    $records = @()
    foreach ($name in $launcherNames) {
        $rawPath = Join-Path $backupRoot ('raw\' + $name)
        $cleanPath = Join-Path $backupRoot ('clean\' + $name)
        [IO.File]::WriteAllBytes($rawPath, $before.bytes[$name])
        Write-TextAtomic $cleanPath $clean.texts[$name]
        $records += [pscustomobject]@{ name=$name; raw_sha256=(Hash $rawPath); clean_sha256=(Hash $cleanPath); installed_sha256=$null }
    }
    if ($null -ne $previousState) { [IO.File]::WriteAllBytes((Join-Path $backupRoot 'previous-installed.json'), $previousState) }
    $installedJar = Join-Path $packageRoot 'roy-server-opt-0.6.0.jar'
    $previousJar = if (Test-Path -LiteralPath $installedJar) { [IO.File]::ReadAllBytes($installedJar) } else { $null }
    try {
        $generatedPath = Join-Path $backupRoot 'generated-roy-server-opt-0.6.0.jar'
        $generatedReport = Generate-Patch $generatedPath
        if (!(Test-Path -LiteralPath $installedJar) -or (Hash $installedJar) -cne $generatedReport.artifact_sha256) { Write-BytesAtomic $installedJar ([IO.File]::ReadAllBytes($generatedPath)) }
        if ((Hash $installedJar) -cne $generatedReport.artifact_sha256) { throw '복사된 로컬 생성 패치 jar가 다릅니다.' }
        $settings = Join-Path $packageRoot 'roy-core.properties'
        if (!(Test-Path -LiteralPath $settings)) { Write-TextAtomic $settings "packetBatching=false`nemptyRemovalGuard=true`npacketRateWindows=true`nserverZombieVocalsGuard=true`nserverZombieMembershipHints=true`nserverZombieRequestIndex=true`nserverDirectionMemo=true`n" }
        $installedClasspath = @(Native-First (@($entry) + @($clean.classpath)))
        $launcher = $clean.texts[$launcherNames[0]] | ConvertFrom-Json
        $launcher.classpath = $installedClasspath
        $launcher.vmArgs = @($launcher.vmArgs | Where-Object { $_ -notmatch '^-XX:AOTCache=' -and $_ -notmatch '^-Xlog:aot(?:=|$)' })
        Write-TextAtomic (Join-Path $gameRoot $launcherNames[0]) (Json $launcher)
        foreach ($name in $launcherNames | Select-Object -Skip 1) {
            $text = Without-Aot (With-BatchClasspath $clean.texts[$name] @(Native-First (@($entry) + @($clean.batch_classpaths[$name]))))
            Write-TextAtomic (Join-Path $gameRoot $name) $text
        }
        $check = Read-Launchers
        if (!(Equal-Sequence $check.classpath $installedClasspath)) { throw '설치 후 classpath 검증이 일치하지 않습니다.' }
        foreach ($name in $launcherNames | Select-Object -Skip 1) {
            if (!(Equal-Sequence $check.batch_classpaths[$name] @(Native-First (@($entry) + @($clean.batch_classpaths[$name]))))) { throw ('설치 후 BAT classpath가 다릅니다: ' + $name) }
        }
        $checkJson = $check.texts[$launcherNames[0]] | ConvertFrom-Json
        if (!(Equal-Sequence @($checkJson.vmArgs) @($launcher.vmArgs))) { throw '설치 후 JVM 인수가 다릅니다.' }
        foreach ($record in $records) { $record.installed_sha256 = Hash (Join-Path $gameRoot $record.name) }
        $sourceScript = [IO.Path]::GetFullPath($PSCommandPath)
        $installedScript = Join-Path $packageRoot 'install.ps1'
        if (![string]::Equals($sourceScript, $installedScript, [StringComparison]::OrdinalIgnoreCase)) {
            Copy-Item -LiteralPath $sourceScript -Destination $installedScript -Force
        }
        Write-TextAtomic (Join-Path $packageRoot 'Uninstall.cmd') "@echo off`r`npowershell.exe -NoProfile -ExecutionPolicy Bypass -File `"%~dp0install.ps1`" -Mode Uninstall -GameDir `"%~dp0..`"`r`npause`r`n"
        $state = [pscustomobject]@{
            schema_version=2; package_id='roy-server-opt'; version='0.6.0'; status='installed'; game_root=$gameRoot
            transaction=$transaction; installed_at=(Get-Date -Format o); artifact_sha256=$generatedReport.artifact_sha256
            recipe_manifest_sha256=(Hash $Manifest); previous_version=$(if ($null -ne $existing) { $existing.version } else { $null })
            game_jar_sha256=$manifestData.game_jar_sha256; optimizer_jar_sha256=$manifestData.optimizer_jar_sha256
            files=$records; packet_batching_default=$false; live_multiplayer_verified=$false
        }
        Write-TextAtomic $statePath (Json $state)
    } catch {
        $originalFailure = $_
        Rollback-Launchers $before.bytes $previousState
        if ($null -ne $previousJar -and (Hash $installedJar) -cne (Hash-Bytes $previousJar)) { Write-BytesAtomic $installedJar $previousJar }
        throw $originalFailure
    }
    Write-Output '서버 처리 최적화 0.6.0을 설치했습니다. 다음 게임/호스트 재시작부터 적용됩니다. 차량 보정과 실험 queue는 포함하지 않습니다.'
} finally { $lock.Dispose() }
