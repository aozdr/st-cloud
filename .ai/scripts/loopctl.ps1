#requires -Version 5.1
<#
.SYNOPSIS
  Agent Loop V2 状态校验与迁移入口。
.DESCRIPTION
  V2 State 使用 JSON-compatible YAML（JSON 本身是合法 YAML），以避免不同机器
  的 YAML 模块差异。所有写命令支持 -DryRun，成功写入使用同目录临时文件替换。
#>
[CmdletBinding()]
param(
  [Parameter(Position = 0, Mandatory = $true)]
  [ValidateSet('init','validate','propose','evaluate','stale','complete','transition','reconcile','validate-dispatch','dispatch-transition','report-blocker','repair-blocker')]
  [string]$Command,
  [Parameter(Position = 1)] [string]$StatePath,
  [string]$DefinitionPath,
  [string]$ProposalPath,
  [string]$DispatchPath,
  [ValidateSet('design','code','artifact')] [string]$RevisionKind,
  [string]$RevisionValue,
  [string]$ArtifactId,
  [string]$TaskId,
  [ValidateSet('small','medium','large')] [string]$Scale,
  [string]$Objective,
  [string]$Scope = '',
  [string[]]$CompletionCriteria,
  [ValidateSet('running','abandoned')] [string]$Status,
  [string]$Actor = 'workflow-manager',
  [string]$EventId,
  [string]$DispatchId,
  [ValidateSet('planned','spawned','acked','running','returned','evaluated','failed')] [string]$DispatchStatus,
  [string]$Fingerprint,
  [string]$RepairEvidence,
  [ValidateSet('resolved','open')] [string]$RepairOutcome,
  [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
if (-not $DefinitionPath) { $DefinitionPath = Join-Path (Split-Path $PSScriptRoot -Parent) 'loop\exit-criteria.yaml' }
$script:AiRoot = Split-Path $PSScriptRoot -Parent
$script:RepoRoot = Split-Path $script:AiRoot -Parent
$script:StateSchemaPath = Join-Path $script:AiRoot 'schema\loop-state.schema.json'
$script:DispatchSchemaPath = Join-Path $script:AiRoot 'schema\dispatch.schema.json'

function Stop-Loop([string]$Code, [string]$Message) {
  throw [System.InvalidOperationException]::new("[$Code] $Message")
}

function Test-Field($Object, [string]$Name) {
  return $null -ne $Object -and $null -ne $Object.PSObject.Properties[$Name]
}

function Require-Text($Object, [string]$Name, [string]$Code = 'SCHEMA_VALIDATION_FAILED') {
  if (-not (Test-Field $Object $Name) -or [string]::IsNullOrWhiteSpace([string]$Object.$Name)) {
    Stop-Loop $Code "缺少非空字段 '$Name'"
  }
}

function Read-Document([string]$Path) {
  if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    Stop-Loop 'FILE_NOT_FOUND' "文件不存在：$Path"
  }
  $raw = Get-Content -LiteralPath $Path -Encoding UTF8 -Raw
  try { return $raw | ConvertFrom-Json }
  catch {
    $yaml = Get-Command ConvertFrom-Yaml -ErrorAction SilentlyContinue
    if ($yaml) {
      try { return $raw | ConvertFrom-Yaml }
      catch { Stop-Loop 'PARSE_ERROR' "无法解析 JSON-compatible YAML：$Path；$($_.Exception.Message)" }
    }
    Stop-Loop 'PARSE_ERROR' "V2 State 必须使用 JSON-compatible YAML；当前环境未安装 ConvertFrom-Yaml：$Path"
  }
}

function ConvertTo-Array($Value) {
  if ($null -eq $Value) { return @() }
  return @($Value)
}

function Resolve-JsonPointer($Root, [string]$Pointer) {
  if (-not $Pointer.StartsWith('#/')) { Stop-Loop 'SCHEMA_REFERENCE_UNSUPPORTED' "仅支持本地 JSON Pointer：$Pointer" }
  $node = $Root
  foreach ($part in $Pointer.Substring(2).Split('/')) {
    $name = $part.Replace('~1','/').Replace('~0','~')
    if (-not (Test-Field $node $name)) { Stop-Loop 'SCHEMA_REFERENCE_INVALID' "JSON Schema 引用不存在：$Pointer" }
    $node = $node.PSObject.Properties[$name].Value
  }
  return $node
}

function Get-JsonValueType($Value) {
  if ($null -eq $Value) { return 'null' }
  if ($Value -is [DateTime] -or $Value -is [DateTimeOffset]) { return 'string' }
  if ($Value -is [bool]) { return 'boolean' }
  if ($Value -is [string]) { return 'string' }
  if ($Value -is [byte] -or $Value -is [int16] -or $Value -is [int32] -or $Value -is [int64] -or $Value -is [uint16] -or $Value -is [uint32] -or $Value -is [uint64]) { return 'integer' }
  if ($Value -is [single] -or $Value -is [double] -or $Value -is [decimal]) { return 'number' }
  if ($Value -is [System.Collections.IList] -or $Value -is [array]) { return 'array' }
  return 'object'
}

function Assert-JsonSchemaNode($Value, $Schema, $RootSchema, [string]$Path) {
  if (Test-Field $Schema '$ref') { $Schema = Resolve-JsonPointer $RootSchema ([string]$Schema.'$ref') }
  if (Test-Field $Schema 'type') {
    $actualType = Get-JsonValueType $Value
    $allowedTypes = @(ConvertTo-Array $Schema.type | ForEach-Object { [string]$_ })
    if ($allowedTypes -notcontains $actualType -and -not ($actualType -eq 'integer' -and $allowedTypes -contains 'number')) {
      Stop-Loop 'SCHEMA_VALIDATION_FAILED' "$Path 类型应为 [$($allowedTypes -join ',')]，实际为 $actualType"
    }
  }
  if (Test-Field $Schema 'const') {
    if (($Value | ConvertTo-Json -Compress -Depth 20) -ne ($Schema.const | ConvertTo-Json -Compress -Depth 20)) { Stop-Loop 'SCHEMA_VALIDATION_FAILED' "$Path 不符合 const" }
  }
  if (Test-Field $Schema 'enum') {
    $serialized = $Value | ConvertTo-Json -Compress -Depth 20
    if (@(ConvertTo-Array $Schema.enum | Where-Object { ($_ | ConvertTo-Json -Compress -Depth 20) -eq $serialized }).Count -eq 0) { Stop-Loop 'SCHEMA_VALIDATION_FAILED' "$Path 不在 enum 中" }
  }
  $kind = Get-JsonValueType $Value
  if ($kind -eq 'string') {
    $textValue = if ($Value -is [DateTime]) { $Value.ToString('o') } elseif ($Value -is [DateTimeOffset]) { $Value.ToString('o') } else { [string]$Value }
    if ((Test-Field $Schema 'minLength') -and $textValue.Length -lt [int]$Schema.minLength) { Stop-Loop 'SCHEMA_VALIDATION_FAILED' "$Path 长度不足" }
    if ((Test-Field $Schema 'pattern') -and $textValue -notmatch [string]$Schema.pattern) { Stop-Loop 'SCHEMA_VALIDATION_FAILED' "$Path 不匹配 pattern" }
    if ((Test-Field $Schema 'format') -and $Schema.format -eq 'date-time') { try { [DateTimeOffset]::Parse($textValue) | Out-Null } catch { Stop-Loop 'SCHEMA_VALIDATION_FAILED' "$Path 不是合法 date-time" } }
  } elseif ($kind -eq 'integer' -or $kind -eq 'number') {
    if ((Test-Field $Schema 'minimum') -and [decimal]$Value -lt [decimal]$Schema.minimum) { Stop-Loop 'SCHEMA_VALIDATION_FAILED' "$Path 小于 minimum" }
  } elseif ($kind -eq 'array') {
    $items = @(ConvertTo-Array $Value)
    if ((Test-Field $Schema 'minItems') -and $items.Count -lt [int]$Schema.minItems) { Stop-Loop 'SCHEMA_VALIDATION_FAILED' "$Path 元素不足" }
    if ((Test-Field $Schema 'uniqueItems') -and $Schema.uniqueItems -eq $true) {
      $encoded = @($items | ForEach-Object { $_ | ConvertTo-Json -Compress -Depth 20 })
      if (@($encoded | Group-Object | Where-Object Count -gt 1).Count -gt 0) { Stop-Loop 'SCHEMA_VALIDATION_FAILED' "$Path 元素不唯一" }
    }
    if (Test-Field $Schema 'items') { for ($i = 0; $i -lt $items.Count; $i++) { Assert-JsonSchemaNode $items[$i] $Schema.items $RootSchema "$Path[$i]" } }
  } elseif ($kind -eq 'object') {
    $names = @($Value.PSObject.Properties.Name | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    foreach ($required in ConvertTo-Array $Schema.required) { if ($names -notcontains [string]$required) { Stop-Loop 'SCHEMA_VALIDATION_FAILED' "$Path 缺少字段 '$required'" } }
    $known = if (Test-Field $Schema 'properties') { @($Schema.properties.PSObject.Properties.Name) } else { @() }
    foreach ($name in $names) {
      $propertyValue = $Value.PSObject.Properties[$name].Value
      if ($known -contains $name) { Assert-JsonSchemaNode $propertyValue ($Schema.properties.PSObject.Properties[$name].Value) $RootSchema ("{0}.{1}" -f $Path,$name); continue }
      if ((Test-Field $Schema 'additionalProperties') -and $Schema.additionalProperties -eq $false) { Stop-Loop 'SCHEMA_VALIDATION_FAILED' "$Path 包含额外字段 '$name'" }
      if ((Test-Field $Schema 'additionalProperties') -and (Get-JsonValueType $Schema.additionalProperties) -eq 'object') { Assert-JsonSchemaNode $propertyValue $Schema.additionalProperties $RootSchema ("{0}.{1}" -f $Path,$name) }
    }
  }
}

function Assert-JsonSchemaDocument($Document, [string]$SchemaPath, [string]$Code = 'SCHEMA_VALIDATION_FAILED') {
  try {
    $schema = Read-Document $SchemaPath
    Assert-JsonSchemaNode $Document $schema $schema '$'
  } catch {
    if ($_.Exception.Message -match '^\[[A-Z_]+\]') {
      if ($Code -ne 'SCHEMA_VALIDATION_FAILED') { Stop-Loop $Code $_.Exception.Message }
      throw
    }
    Stop-Loop $Code $_.Exception.Message
  }
}

function Resolve-EvidencePath([string]$Reference) {
  $path = if ([IO.Path]::IsPathRooted($Reference)) { [IO.Path]::GetFullPath($Reference) } else { [IO.Path]::GetFullPath((Join-Path $script:RepoRoot $Reference)) }
  $rootPrefix = [IO.Path]::GetFullPath($script:RepoRoot).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
  if (-not $path.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) { Stop-Loop 'EVIDENCE_PATH_ESCAPE' "证据必须位于仓库内：$Reference" }
  $current = $path
  while ($current.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    if (Test-Path -LiteralPath $current) {
      $item = Get-Item -LiteralPath $current -Force
      if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { Stop-Loop 'EVIDENCE_REPARSE_POINT' "证据路径不得经过重解析点：$Reference" }
    }
    $parent = [IO.Path]::GetDirectoryName($current)
    if ([string]::IsNullOrWhiteSpace($parent) -or $parent -eq $current) { break }
    $current = $parent
  }
  return $path
}

function Assert-EvidenceExists([string]$Reference, [string]$Code = 'EVIDENCE_NOT_FOUND') {
  $path = Resolve-EvidencePath $Reference
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { Stop-Loop $Code "证据文件不存在：$Reference" }
}

function Assert-RepoRelativeReference([string]$Reference, [string]$Field) {
  if ([string]::IsNullOrWhiteSpace($Reference)) { Stop-Loop 'DISPATCH_PATH_INVALID' "$Field 不能为空" }
  $normalized = $Reference.Replace('\','/')
  if ([IO.Path]::IsPathRooted($Reference) -or (($normalized -split '/') -contains '..')) { Stop-Loop 'DISPATCH_PATH_INVALID' "$Field 必须是仓库内相对路径：$Reference" }
}

function Assert-SkillReference([string]$Reference) {
  if ([string]::IsNullOrWhiteSpace($Reference)) { Stop-Loop 'DISPATCH_PATH_INVALID' 'skillRefs 不能包含空引用' }
  if ($Reference -eq '-') { return }
  $normalized = $Reference.Replace('\','/')
  if ([IO.Path]::IsPathRooted($Reference) -or (($normalized -split '/') -contains '..') -or $Reference -match '[\r\n]') {
    Stop-Loop 'DISPATCH_PATH_INVALID' "skillRefs 必须是运行时注册表标识：$Reference"
  }
}

function Split-InlineList([string]$Text) {
  if ([string]::IsNullOrWhiteSpace($Text)) { return @() }
  return @($Text -split ',' | ForEach-Object { $_.Trim().Trim('"').Trim("'") } | Where-Object { $_ })
}

function Read-Definition([string]$Path) {
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { Stop-Loop 'DEFINITION_NOT_FOUND' "定义文件不存在：$Path" }
  $result = [ordered]@{ version = 0; scales = [ordered]@{}; catalog = [ordered]@{}; producers = @(); verifiers = @() }
  $section = ''; $scaleName = ''; $criterion = $null; $catalogName = ''
  foreach ($raw in Get-Content -LiteralPath $Path -Encoding UTF8) {
    $line = $raw -replace "`t", '    '
    if ($line -match '^version:\s*(\d+)') { $result.version = [int]$Matches[1]; continue }
    if ($line -match '^scales:\s*$') { $section = 'scales'; continue }
    if ($line -match '^catalog:\s*$') { $section = 'catalog'; continue }
    if ($line -match '^separationOfDuty:\s*$') { $section = 'sod'; continue }
    if ($line -match '^[A-Za-z]') { $section = 'other'; continue }
    if ($section -eq 'scales') {
      if ($line -match '^  (small|medium|large):\s*$') {
        $scaleName = $Matches[1]; $result.scales[$scaleName] = @(); $criterion = $null; continue
      }
      if ($scaleName -and $line -match '^      - id:\s*([A-Z][A-Z_]*)') {
        $criterion = [pscustomobject][ordered]@{ id = $Matches[1]; dependsOn = @(); skippable = $false; conditional = $false }
        $result.scales[$scaleName] += $criterion; continue
      }
      if ($criterion -and $line -match '^        dependsOn:\s*\[(.*)\]') { $criterion.dependsOn = @(Split-InlineList $Matches[1]); continue }
      if ($criterion -and $line -match '^        skippable:\s*(true|false)') { $criterion.skippable = $Matches[1] -eq 'true'; continue }
      if ($criterion -and $line -match '^        conditional:\s*(true|false)') { $criterion.conditional = $Matches[1] -eq 'true'; continue }
    }
    if ($section -eq 'catalog') {
      if ($line -match '^  ([A-Z][A-Z_]*):\s*$') {
        $catalogName = $Matches[1]; $result.catalog[$catalogName] = [ordered]@{ userConfirm = $false; conditional = $false; owner = ''; taskType = ''; artifacts = @(); conditionalArtifacts = @() }; continue
      }
      if ($catalogName -and $line -match '^    userConfirm:\s*(true|false)') { $result.catalog[$catalogName].userConfirm = $Matches[1] -eq 'true'; continue }
      if ($catalogName -and $line -match '^    conditional:\s*(true|false)') { $result.catalog[$catalogName].conditional = $Matches[1] -eq 'true'; continue }
      if ($catalogName -and $line -match '^    artifacts:\s*\[(.*)\]') { $result.catalog[$catalogName].artifacts = @(Split-InlineList $Matches[1]); continue }
      if ($catalogName -and $line -match '^    conditionalArtifacts:\s*\[(.*)\]') { $result.catalog[$catalogName].conditionalArtifacts = @(Split-InlineList $Matches[1]); continue }
      if ($catalogName -and $line -match '^    owner:\s*(\S+)') { $result.catalog[$catalogName].owner = $Matches[1].Trim('"',"'"); continue }
      if ($catalogName -and $line -match '^    taskType:\s*(\S+)') { $result.catalog[$catalogName].taskType = $Matches[1].Trim('"',"'"); continue }
    }
    if ($section -eq 'sod') {
      if ($line -match '^  producers:\s*\[(.*)\]') { $result.producers = @(Split-InlineList $Matches[1]); continue }
      if ($line -match '^  verifiers:\s*\[(.*)\]') { $result.verifiers = @(Split-InlineList $Matches[1]); continue }
    }
  }
  if ($result.version -ne 2) { Stop-Loop 'DEFINITION_VERSION_MISMATCH' "仅支持 definition version 2，实际为 $($result.version)" }
  return $result
}

function Get-PropertyMap($Items, [string]$Key = 'id') {
  $map = [ordered]@{}
  foreach ($item in ConvertTo-Array $Items) {
    if (-not (Test-Field $item $Key)) { continue }
    $map[[string]$item.$Key] = $item
  }
  return $map
}

function Test-SameSet($Left, $Right) {
  $a = @(ConvertTo-Array $Left | ForEach-Object { [string]$_ } | Sort-Object -Unique)
  $b = @(ConvertTo-Array $Right | ForEach-Object { [string]$_ } | Sort-Object -Unique)
  return $a.Count -eq $b.Count -and (@(Compare-Object $a $b).Count -eq 0)
}

function Test-ConfirmationRequired($Definition, $Criterion) {
  $id = [string]$Criterion.id
  if (-not $Definition.catalog.Contains($id) -or -not $Definition.catalog[$id].userConfirm) { return $false }
  if ($Definition.catalog[$id].conditional -eq $true) {
    $hasFlag = Test-Field $Criterion 'confirmationRequired'
    return $hasFlag -and ([bool]$Criterion.confirmationRequired)
  }
  return $true
}

# 条件标准默认适用；只有编排器明确写入 applicable=false 才能走跳过路径。
function Test-CriterionApplicable($Criterion) {
  if (-not (Test-Field $Criterion 'applicable')) { return $true }
  return [bool]$Criterion.applicable
}

function Test-CriterionSkipAllowed($State, $Canonical, $Criterion) {
  if (-not $Canonical.skippable) { return $false }
  if ($Canonical.conditional) { return -not (Test-CriterionApplicable $Criterion) }
  return $State.scale -eq 'medium' -and [string]$Criterion.id -eq 'SECURITY_REVIEW'
}

# 完成门禁按 catalog 逐项核对 State，并拒绝同一 ref 重复充数；每个必需产物都必须匹配 catalog 指定文件名。
function Assert-CriterionArtifacts($State, $Definition, $Criterion) {
  if ([string]$Criterion.status -ne 'done') { return }
  if (-not $Definition.catalog.Contains([string]$Criterion.id)) { return }
  $expected = @(ConvertTo-Array $Definition.catalog[[string]$Criterion.id].artifacts)
  if ([string]$Criterion.id -eq 'REQ_ANALYSIS') {
    $uiActive = @($State.exitCriteria | Where-Object { [string]$_.id -in @('EXP_DESIGN','EXP_ACCEPT') -and (Test-CriterionApplicable $_) }).Count -gt 0
    if ($uiActive) { $expected += @(ConvertTo-Array $Definition.catalog[[string]$Criterion.id].conditionalArtifacts) }
  }
  if ($expected.Count -eq 0) { return }
  $candidates = @()
  foreach ($property in $State.artifacts.PSObject.Properties) {
    $artifact = $property.Value
    if (-not (Test-Field $artifact 'status') -or [string]$artifact.status -notin @('done','ready')) { continue }
    $refText = if (Test-Field $artifact 'ref') { [string]$artifact.ref } else { '' }
    $fileName = if (-not [string]::IsNullOrWhiteSpace($refText)) { [IO.Path]::GetFileName($refText.Replace('\','/')) } else { '' }
    $refKey = if ([string]::IsNullOrWhiteSpace($refText)) { 'property:' + [string]$property.Name } else {
      $refPath = if ([IO.Path]::IsPathRooted($refText)) { $refText } else { Join-Path $script:RepoRoot $refText }
      ([IO.Path]::GetFullPath($refPath)).ToLowerInvariant()
    }
    $candidates += [pscustomobject]@{ property = [string]$property.Name; artifact = $artifact; fileName = $fileName; refKey = $refKey }
  }
  $usedRefs = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
  $missing = @()
  foreach ($requirement in $expected) {
    $requiredName = [IO.Path]::GetFileName(([string]$requirement).Replace('\','/'))
    $selected = @($candidates | Where-Object { -not $usedRefs.Contains([string]$_.refKey) -and $_.fileName -eq $requiredName } | Select-Object -First 1)
    if ($selected.Count -eq 0) { $missing += [string]$requirement; continue }
    $chosen = $selected[0]
    Require-Text $chosen.artifact 'ref' 'ARTIFACT_REF_MISSING'
    Assert-EvidenceExists ([string]$chosen.artifact.ref) 'ARTIFACT_NOT_FOUND'
    [void]$usedRefs.Add([string]$chosen.refKey)
  }
  if ($missing.Count -gt 0) {
    Stop-Loop 'CATALOG_ARTIFACT_MISSING' "$($Criterion.id) 缺少必需产物：$($missing -join ', ')"
  }
}

function Get-ExpectedRevision($State, [string]$CriterionId) {
  $codeBound = @('IMPLEMENTED','CODE_REVIEW','SECURITY_REVIEW','EXP_ACCEPT','TEST_PASS','VERIFIED','KNOWLEDGE','ACCEPT')
  if ($codeBound -contains $CriterionId -and -not [string]::IsNullOrWhiteSpace([string]$State.revision.code)) { return [string]$State.revision.code }
  return [string]$State.revision.design
}

function Assert-Dispatch($Envelope) {
  Assert-JsonSchemaDocument $Envelope $script:DispatchSchemaPath 'DISPATCH_SCHEMA_INVALID'
  foreach ($name in @('schemaVersion','dispatchId','taskId','idempotencyKey','role','taskType','objective','taskRefs','stateRef','scope','acceptance','validation','forbidSpawn')) {
    if (-not (Test-Field $Envelope $name)) { Stop-Loop 'DISPATCH_SCHEMA_INVALID' "缺少字段 '$name'" }
  }
  if ([int]$Envelope.schemaVersion -ne 2) { Stop-Loop 'DISPATCH_SCHEMA_INVALID' 'schemaVersion 必须为 2' }
  foreach ($name in @('dispatchId','taskId','idempotencyKey','taskType','objective','stateRef')) { Require-Text $Envelope $name 'DISPATCH_SCHEMA_INVALID' }
  if (@('executor','reviewer','tester') -notcontains [string]$Envelope.role) { Stop-Loop 'DISPATCH_SCHEMA_INVALID' "role 非法：$($Envelope.role)" }
  if (@(ConvertTo-Array $Envelope.taskRefs).Count -eq 0) { Stop-Loop 'DISPATCH_SCHEMA_INVALID' 'taskRefs 不能为空' }
  if (@(ConvertTo-Array $Envelope.scope.include).Count -eq 0) { Stop-Loop 'DISPATCH_SCHEMA_INVALID' 'scope.include 不能为空' }
  if (@(ConvertTo-Array $Envelope.acceptance).Count -eq 0 -or @(ConvertTo-Array $Envelope.validation).Count -eq 0) { Stop-Loop 'DISPATCH_SCHEMA_INVALID' 'acceptance/validation 不能为空' }
  if ($Envelope.forbidSpawn -ne $true) { Stop-Loop 'DISPATCH_SCHEMA_INVALID' 'forbidSpawn 必须为 true' }
  foreach ($ref in ConvertTo-Array $Envelope.taskRefs) { Assert-RepoRelativeReference ([string]$ref) 'taskRefs' }
  Assert-RepoRelativeReference ([string]$Envelope.stateRef) 'stateRef'
  if (Test-Field $Envelope 'artifactRefs') { foreach ($ref in ConvertTo-Array $Envelope.artifactRefs) { Assert-RepoRelativeReference ([string]$ref) 'artifactRefs' } }
  if (Test-Field $Envelope 'skillRefs') { foreach ($ref in ConvertTo-Array $Envelope.skillRefs) { Assert-SkillReference ([string]$ref) } }
}

function Assert-State($State, $Definition, [switch]$ForCompletion) {
  Assert-JsonSchemaDocument $State $script:StateSchemaPath
  foreach ($name in @('schemaVersion','definitionVersion','taskId','scale','status','goal','revision','exitCriteria','artifacts','blockers','acceptanceEvidence','dispatchLedger','history')) {
    if (-not (Test-Field $State $name)) { Stop-Loop 'SCHEMA_VALIDATION_FAILED' "缺少字段 '$name'" }
  }
  if ([int]$State.schemaVersion -ne 2) { Stop-Loop 'SCHEMA_VERSION_UNSUPPORTED' "schemaVersion 必须为 2" }
  if ([int]$State.definitionVersion -ne $Definition.version) { Stop-Loop 'DEFINITION_VERSION_MISMATCH' "State=$($State.definitionVersion)，定义=$($Definition.version)" }
  if ((Test-Field $State 'legacy') -and $State.legacy -eq $true) {
    Stop-Loop 'LEGACY_STATE_REQUIRES_REINIT' '历史 State 仅作审计依据；请按当前流程重新建立 State、TASK 和产物'
  }
  if (-not $Definition.scales.Contains([string]$State.scale)) { Stop-Loop 'SCALE_INVALID' "未知 scale：$($State.scale)" }
  Require-Text $State 'taskId'
  Require-Text $State.goal 'objective'
  if (@(ConvertTo-Array $State.goal.completionCriteria).Count -eq 0) { Stop-Loop 'SCHEMA_VALIDATION_FAILED' 'goal.completionCriteria 不能为空' }

  $actual = @(ConvertTo-Array $State.exitCriteria)
  $ids = @($actual | ForEach-Object { [string]$_.id })
  $duplicates = @($ids | Group-Object | Where-Object Count -gt 1 | ForEach-Object Name)
  if ($duplicates.Count -gt 0) { Stop-Loop 'DUPLICATE_CRITERION_ID' "重复 ID：$($duplicates -join ', ')" }
  $expected = @(ConvertTo-Array $Definition.scales[[string]$State.scale])
  $expectedIds = @($expected | ForEach-Object id)
  if (-not (Test-SameSet $ids $expectedIds)) { Stop-Loop 'CRITERIA_SET_MISMATCH' "期望 [$($expectedIds -join ', ')]，实际 [$($ids -join ', ')]" }

  $actualMap = Get-PropertyMap $actual
  $expectedMap = Get-PropertyMap $expected
  $allowedCriterionStatus = @('pending','in_progress','done','stale','blocked','skipped')
  foreach ($id in $expectedIds) {
    $criterion = $actualMap[$id]; $canonical = $expectedMap[$id]
    if (-not (Test-SameSet (ConvertTo-Array $criterion.dependsOn) (ConvertTo-Array $canonical.dependsOn))) {
      Stop-Loop 'DEPENDENCY_MISMATCH' "$id dependsOn 应为 [$($canonical.dependsOn -join ', ')]，实际为 [$($criterion.dependsOn -join ', ')]"
    }
    if ($allowedCriterionStatus -notcontains [string]$criterion.status) { Stop-Loop 'CRITERION_STATUS_INVALID' "$id status=$($criterion.status)" }
    if ($canonical.conditional -and (Test-Field $criterion 'applicable') -and -not (Test-CriterionApplicable $criterion) -and $criterion.status -eq 'done') {
      Stop-Loop 'CRITERION_NOT_APPLICABLE' "$id 已标记为不适用，不得标记 done"
    }
    if ($criterion.status -eq 'skipped') {
      if (-not (Test-CriterionSkipAllowed $State $canonical $criterion)) { Stop-Loop 'SKIP_NOT_ALLOWED' "$($State.scale) 的 $id 不允许 skipped" }
      foreach ($field in @('skipReason','approvedBy','evidenceRef')) { Require-Text $criterion $field 'SKIP_EVIDENCE_MISSING' }
      Assert-EvidenceExists ([string]$criterion.evidenceRef)
    }
    if ($criterion.status -eq 'done') {
      foreach ($field in @('by','dispatchId','evidenceRef','validatedRevision','completedAt')) { Require-Text $criterion $field 'DONE_EVIDENCE_MISSING' }
      Assert-EvidenceExists ([string]$criterion.evidenceRef)
      foreach ($dependency in ConvertTo-Array $criterion.dependsOn) {
        if (@('done','skipped') -notcontains [string]$actualMap[[string]$dependency].status) {
          Stop-Loop 'DEPENDENCY_NOT_SATISFIED' "$id 的依赖 $dependency 状态为 $($actualMap[[string]$dependency].status)"
        }
      }
      $expectedRevision = Get-ExpectedRevision $State $id
      if (-not [string]::IsNullOrWhiteSpace($expectedRevision) -and [string]$criterion.validatedRevision -ne $expectedRevision) {
        Stop-Loop 'REVISION_MISMATCH' "$id 期望 revision '$expectedRevision'，证据为 '$($criterion.validatedRevision)'"
      }
      if (Test-ConfirmationRequired $Definition $criterion) {
        foreach ($field in @('userConfirmedAt','confirmedBy','confirmationArtifact')) { Require-Text $criterion $field 'CONFIRMATION_EVIDENCE_MISSING' }
        Assert-EvidenceExists ([string]$criterion.confirmationArtifact) 'CONFIRMATION_ARTIFACT_NOT_FOUND'
      }
      if ($ForCompletion -or $State.status -eq 'done') { Assert-CriterionArtifacts $State $Definition $criterion }
    }
  }

  $dispatches = @(ConvertTo-Array $State.dispatchLedger)
  $dispatchIds = @($dispatches | ForEach-Object { [string]$_.dispatchId })
  if (@($dispatchIds | Group-Object | Where-Object Count -gt 1).Count -gt 0) { Stop-Loop 'DISPATCH_LEDGER_INVALID' 'dispatchLedger.dispatchId 必须唯一' }
  foreach ($attempt in $dispatches) {
    foreach ($field in @('dispatchId','taskId','idempotencyKey','role','taskType','criterionId','childId','status')) { Require-Text $attempt $field 'DISPATCH_LEDGER_INVALID' }
    if (@('executor','reviewer','tester') -notcontains [string]$attempt.role) { Stop-Loop 'DISPATCH_LEDGER_INVALID' "非法 role：$($attempt.role)" }
    if (@('planned','spawned','acked','running','returned','evaluated','failed') -notcontains [string]$attempt.status) { Stop-Loop 'DISPATCH_LEDGER_INVALID' "非法 status：$($attempt.status)" }
    if ($expectedIds -notcontains [string]$attempt.criterionId) { Stop-Loop 'DISPATCH_LEDGER_INVALID' "criterionId 不属于当前 State：$($attempt.criterionId)" }
    if ($attempt.status -in @('returned','evaluated')) {
      Require-Text $attempt 'resultRef' 'DISPATCH_LEDGER_INVALID'
      if (-not (Test-Field $attempt 'scopeVerified') -or $attempt.scopeVerified -ne $true) { Stop-Loop 'DISPATCH_SCOPE_UNVERIFIED' "$($attempt.dispatchId) 缺 scopeVerified=true" }
      Assert-EvidenceExists ([string]$attempt.resultRef)
    }
  }

  $producerActors = @($Definition.producers | ForEach-Object { if ($actualMap.Contains($_) -and $actualMap[$_].status -eq 'done') { [string]$actualMap[$_].by } } | Where-Object { $_ })
  $verifierActors = @($Definition.verifiers | ForEach-Object { if ($actualMap.Contains($_) -and $actualMap[$_].status -in @('done','skipped')) { [string]$(if ($actualMap[$_].status -eq 'skipped') { $actualMap[$_].approvedBy } else { $actualMap[$_].by }) } } | Where-Object { $_ })
  $overlap = @($producerActors | Where-Object { $verifierActors -contains $_ } | Sort-Object -Unique)
  if ($overlap.Count -gt 0) { Stop-Loop 'ROLE_SEPARATION_VIOLATION' "执行与验收身份重叠：$($overlap -join ', ')" }

  $activeBlockers = @(ConvertTo-Array $State.blockers | Where-Object { $_.status -in @('open','escalated') })
  if ($activeBlockers.Count -gt 0 -and ($ForCompletion -or $State.status -eq 'done')) { Stop-Loop 'OPEN_BLOCKER' "存在 $($activeBlockers.Count) 个 open/escalated blocker" }
  foreach ($property in $State.artifacts.PSObject.Properties) {
    $artifact = $property.Value
    if ((Test-Field $artifact 'status') -and [string]$artifact.status -eq 'done') {
      Require-Text $artifact 'ref' 'ARTIFACT_REF_MISSING'
      Assert-EvidenceExists ([string]$artifact.ref) 'ARTIFACT_NOT_FOUND'
    }
  }
  if ($ForCompletion -or $State.status -eq 'done') {
    $unfinishedDispatches = @(ConvertTo-Array $State.dispatchLedger | Where-Object { $_.status -in @('planned','spawned','acked','running','returned') })
    if ($unfinishedDispatches.Count -gt 0) { Stop-Loop 'DISPATCH_NOT_RECONCILED' "存在 $($unfinishedDispatches.Count) 个未结束 dispatch" }
  }
  if ($ForCompletion -or $actualMap['ACCEPT'].status -eq 'done') {
    $missing = @()
    foreach ($criterionText in ConvertTo-Array $State.goal.completionCriteria) {
      $found = @(ConvertTo-Array $State.acceptanceEvidence | Where-Object { $_.criterion -eq $criterionText -and -not [string]::IsNullOrWhiteSpace([string]$_.evidenceRef) })
      if ($found.Count -eq 0) { $missing += [string]$criterionText }
      else {
        Assert-EvidenceExists ([string]$found[0].evidenceRef)
        if ([string]$found[0].validatedRevision -ne [string]$State.revision.code) { Stop-Loop 'ACCEPTANCE_REVISION_MISMATCH' "$criterionText 的验收证据不是当前 code revision" }
      }
    }
    if ($missing.Count -gt 0) { Stop-Loop 'ACCEPTANCE_EVIDENCE_INCOMPLETE' "缺少逐项验收证据：$($missing -join ' | ')" }
  }
  if ($State.status -eq 'done') {
    $notComplete = @($actual | Where-Object { $_.status -notin @('done','skipped') } | ForEach-Object id)
    if ($notComplete.Count -gt 0) { Stop-Loop 'STATE_NOT_COMPLETE' "未完成标准：$($notComplete -join ', ')" }
  }
}

function New-HistoryEvent([string]$Action, $Before, $After, [string]$Id) {
  if ([string]::IsNullOrWhiteSpace($Id)) { $Id = [guid]::NewGuid().ToString() }
  return [pscustomobject][ordered]@{
    eventId = $Id; action = $Action; before = $Before; after = $After; actor = $Actor
    occurredAt = [DateTime]::UtcNow.ToString('o'); revision = [pscustomobject]@{ design = $After.revision.design; code = $After.revision.code }
  }
}

function Save-State([string]$Path, $State, [switch]$WhatIfOnly) {
  $json = $State | ConvertTo-Json -Depth 100
  if ($WhatIfOnly) { Write-Output $json; return }
  $full = [IO.Path]::GetFullPath($Path); $directory = Split-Path $full -Parent
  if (-not (Test-Path -LiteralPath $directory)) { [IO.Directory]::CreateDirectory($directory) | Out-Null }
  $temp = Join-Path $directory ('.loopctl-' + [guid]::NewGuid().ToString('N') + '.tmp')
  [IO.File]::WriteAllText($temp, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
  try { Move-Item -LiteralPath $temp -Destination $full -Force }
  finally { if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force } }
}

function Add-History($State, $Event) {
  $events = @(ConvertTo-Array $State.history)
  if (@($events | Where-Object eventId -eq $Event.eventId).Count -eq 0) { $State.history = @($events + $Event) }
}

function Get-Descendants($Definition, [string]$ScaleName, [string[]]$Roots) {
  $all = @(ConvertTo-Array $Definition.scales[$ScaleName]); $selected = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
  foreach ($root in $Roots) { [void]$selected.Add($root) }
  do {
    $changed = $false
    foreach ($item in $all) {
      if ($selected.Contains($item.id)) { continue }
      if (@(ConvertTo-Array $item.dependsOn | Where-Object { $selected.Contains([string]$_) }).Count -gt 0) { [void]$selected.Add($item.id); $changed = $true }
    }
  } while ($changed)
  return @($all | Where-Object { $selected.Contains($_.id) } | ForEach-Object id)
}

try {
  $definition = Read-Definition $DefinitionPath
  if ($Command -eq 'validate-dispatch') { Assert-Dispatch (Read-Document $DispatchPath); Write-Output 'PASS'; exit 0 }

  if ($Command -eq 'init') {
    if (Test-Path -LiteralPath $StatePath) { Assert-State (Read-Document $StatePath) $definition; Write-Output 'UNCHANGED'; exit 0 }
    if (-not $TaskId -or -not $Scale -or -not $Objective -or @($CompletionCriteria).Count -eq 0) { Stop-Loop 'INIT_ARGUMENT_MISSING' 'init 需要 StatePath/TaskId/Scale/Objective/CompletionCriteria' }
    $criteria = @($definition.scales[$Scale] | ForEach-Object { [pscustomobject][ordered]@{ id = $_.id; status = 'pending'; dependsOn = @($_.dependsOn) } })
    $state = [pscustomobject][ordered]@{
      schemaVersion = 2; definitionVersion = 2; taskId = $TaskId; scale = $Scale; status = 'running'
      goal = [pscustomobject][ordered]@{ objective = $Objective; scope = $Scope; completionCriteria = @($CompletionCriteria) }
      revision = [pscustomobject]@{ design = $null; code = $null }; exitCriteria = $criteria; artifacts = [pscustomobject]@{}
      blockers = @(); acceptanceEvidence = @(); dispatchLedger = @(); proposals = @(); history = @()
    }
    $event = New-HistoryEvent 'init' $null $state $EventId; Add-History $state $event
    Assert-State $state $definition; Save-State $StatePath $state -WhatIfOnly:$DryRun; Write-Output $(if ($DryRun) { 'DRY_RUN' } else { 'CREATED' }); exit 0
  }

  $state = Read-Document $StatePath
  Assert-State $state $definition
  if ($Command -eq 'validate') { Write-Output 'PASS'; exit 0 }

  if ($Command -eq 'propose') {
    $proposal = Read-Document $ProposalPath
    $body = if (Test-Field $proposal 'criterionProposal') { $proposal.criterionProposal } else { $proposal }
    foreach ($field in @('id','outcome','by','dispatchId','evidenceRef','validatedRevision')) { Require-Text $body $field 'PROPOSAL_INVALID' }
    if (@('pass','fail','blocked','skip') -notcontains [string]$body.outcome) { Stop-Loop 'PROPOSAL_INVALID' 'outcome 仅允许 pass/fail/blocked/skip' }
    $key = if (Test-Field $proposal 'eventId') { [string]$proposal.eventId } else { "$($body.dispatchId):$($body.id):$($body.outcome)" }
    if (@(ConvertTo-Array $state.proposals | Where-Object eventId -eq $key).Count -eq 0) {
      $state.proposals = @((ConvertTo-Array $state.proposals) + [pscustomobject]@{ eventId = $key; criterionProposal = $body })
      Add-History $state (New-HistoryEvent 'propose' $null $state $key)
    }
    Save-State $StatePath $state -WhatIfOnly:$DryRun; Write-Output $(if ($DryRun) { 'DRY_RUN' } else { 'PROPOSED' }); exit 0
  }

  if ($Command -eq 'evaluate') {
    $proposal = Read-Document $ProposalPath
    $body = if (Test-Field $proposal 'criterionProposal') { $proposal.criterionProposal } else { $proposal }
    foreach ($field in @('id','outcome','by','dispatchId','evidenceRef','validatedRevision')) { Require-Text $body $field 'PROPOSAL_INVALID' }
    foreach ($field in @('taskId','dispatchId')) { Require-Text $proposal $field 'PROPOSAL_INVALID' }
    if (@('pass','fail','blocked','skip') -notcontains [string]$body.outcome) { Stop-Loop 'PROPOSAL_INVALID' 'outcome 仅允许 pass/fail/blocked/skip' }
    $attempts = @(ConvertTo-Array $state.dispatchLedger)
    $matching = @($attempts | Where-Object { [string]$_.dispatchId -eq [string]$body.dispatchId })
    if ($matching.Count -ne 1) { Stop-Loop 'DISPATCH_ATTEMPT_INVALID' "dispatchId 必须唯一匹配一个 attempt：$($body.dispatchId)" }
    $attempt = $matching[0]
    if ([string]$attempt.status -notin @('returned','evaluated')) { Stop-Loop 'DISPATCH_NOT_RETURNED' "attempt 状态必须为 returned：$($attempt.status)" }
    if ([string]$attempt.status -eq 'evaluated') { Write-Output 'UNCHANGED'; exit 0 }
    if ([string]$proposal.taskId -ne [string]$attempt.taskId) { Stop-Loop 'DISPATCH_TASK_MISMATCH' "结果 taskId 与 ledger 不一致" }
    if ([string]$proposal.dispatchId -ne [string]$body.dispatchId) { Stop-Loop 'DISPATCH_ID_MISMATCH' '结果与 criterionProposal 的 dispatchId 不一致' }
    if ([string]$attempt.criterionId -ne [string]$body.id) { Stop-Loop 'DISPATCH_CRITERION_MISMATCH' 'attempt 未获授权评估该 criterion' }
    if ([string]$attempt.role -ne [string]$definition.catalog[[string]$body.id].owner) { Stop-Loop 'DISPATCH_ROLE_MISMATCH' 'attempt role 与 criterion owner 不一致' }
    if ([string]$attempt.taskType -ne [string]$definition.catalog[[string]$body.id].taskType) { Stop-Loop 'DISPATCH_TASKTYPE_MISMATCH' 'attempt taskType 与 criterion 不一致' }
    if ([string]$body.by -ne [string]$attempt.childId) { Stop-Loop 'DISPATCH_CHILD_MISMATCH' 'proposal.by 与 childId 不一致' }
    $groupKey = if ((Test-Field $attempt 'idempotencyKey') -and -not [string]::IsNullOrWhiteSpace([string]$attempt.idempotencyKey)) { [string]$attempt.idempotencyKey } else { [string]$attempt.taskId }
    $sameTask = @($attempts | Where-Object { $candidateKey = if ((Test-Field $_ 'idempotencyKey') -and -not [string]::IsNullOrWhiteSpace([string]$_.idempotencyKey)) { [string]$_.idempotencyKey } else { [string]$_.taskId }; $candidateKey -eq $groupKey })
    if ($sameTask.Count -gt 0 -and [string]$sameTask[-1].dispatchId -ne [string]$body.dispatchId) { Stop-Loop 'DISPATCH_ATTEMPT_STALE' '旧 attempt 的结果不得覆盖当前 attempt' }
    Assert-EvidenceExists ([string]$body.evidenceRef)
    if ([string]$attempt.resultRef -ne [string]$body.evidenceRef) { Stop-Loop 'DISPATCH_RESULT_MISMATCH' 'criterionProposal.evidenceRef 必须等于当前 attempt.resultRef' }
    $map = Get-PropertyMap $state.exitCriteria
    if (-not $map.Contains([string]$body.id)) { Stop-Loop 'CRITERION_UNKNOWN' "$($body.id) 不在当前 scale" }
    $criterion = $map[[string]$body.id]; $beforeStatus = [string]$criterion.status
    $expectedRevision = Get-ExpectedRevision $state $criterion.id
    if (-not [string]::IsNullOrWhiteSpace($expectedRevision) -and [string]$body.validatedRevision -ne $expectedRevision) { Stop-Loop 'REVISION_MISMATCH' "期望 '$expectedRevision'，实际 '$($body.validatedRevision)'" }
    foreach ($dependency in ConvertTo-Array $criterion.dependsOn) { if (@('done','skipped') -notcontains [string]$map[[string]$dependency].status) { Stop-Loop 'DEPENDENCY_NOT_SATISFIED' "$($criterion.id) 的依赖 $dependency 未完成" } }
    $eventKey = if (Test-Field $proposal 'eventId') { [string]$proposal.eventId } else { "$($body.dispatchId):$($body.id):evaluate" }
    if (@(ConvertTo-Array $state.history | Where-Object eventId -eq $eventKey).Count -gt 0) { Write-Output 'UNCHANGED'; exit 0 }
    if ($body.outcome -in @('fail','blocked')) {
      $criterion.status = 'blocked'
      foreach ($field in @('by','dispatchId','evidenceRef','validatedRevision')) { $criterion | Add-Member $field $body.$field -Force }
    } elseif ($body.outcome -eq 'skip') {
      $canonical = (Get-PropertyMap $definition.scales[[string]$state.scale])[[string]$criterion.id]
      if (-not (Test-CriterionSkipAllowed $state $canonical $criterion)) { Stop-Loop 'SKIP_NOT_ALLOWED' "$($criterion.id) 不允许跳过，或未标记为不适用" }
      foreach ($field in @('skipReason','approvedBy')) { Require-Text $body $field 'SKIP_EVIDENCE_MISSING' }
      $criterion.status = 'skipped'; $criterion | Add-Member skipReason $body.skipReason -Force; $criterion | Add-Member approvedBy $body.approvedBy -Force; $criterion | Add-Member evidenceRef $body.evidenceRef -Force
    } else {
      $criterion.status = 'done'
      foreach ($field in @('by','dispatchId','evidenceRef','validatedRevision')) { $criterion | Add-Member $field $body.$field -Force }
      $criterion | Add-Member completedAt $(if (Test-Field $body 'completedAt') { $body.completedAt } else { [DateTime]::UtcNow.ToString('o') }) -Force
      if (Test-ConfirmationRequired $definition $criterion) { foreach ($field in @('userConfirmedAt','confirmedBy','confirmationArtifact')) { Require-Text $body $field 'CONFIRMATION_EVIDENCE_MISSING'; $criterion | Add-Member $field $body.$field -Force } }
      if ($criterion.id -eq 'ACCEPT') {
        if (-not (Test-Field $proposal 'acceptanceEvidence')) { Stop-Loop 'ACCEPTANCE_EVIDENCE_INCOMPLETE' 'ACCEPT proposal 缺 acceptanceEvidence' }
        $state.acceptanceEvidence = @(ConvertTo-Array $proposal.acceptanceEvidence)
      }
    }
    $attempt.status = 'evaluated'
    $attempt | Add-Member evaluatedAt ([DateTime]::UtcNow.ToString('o')) -Force
    Add-History $state (New-HistoryEvent 'evaluate' ([pscustomobject]@{ id = $criterion.id; status = $beforeStatus }) ([pscustomobject]@{ id = $criterion.id; status = $criterion.status; revision = $state.revision }) $eventKey)
    Assert-State $state $definition
    Save-State $StatePath $state -WhatIfOnly:$DryRun; Write-Output $(if ($DryRun) { 'DRY_RUN' } else { 'EVALUATED' }); exit 0
  }

  if ($Command -eq 'stale') {
    if (-not $RevisionKind) { Stop-Loop 'STALE_ARGUMENT_MISSING' 'stale 需要 RevisionKind' }
    $roots = @()
    if ($RevisionKind -eq 'code') {
      if ([string]$state.revision.code -eq $RevisionValue) { Write-Output 'UNCHANGED'; exit 0 }
      $state.revision.code = $RevisionValue; $roots = @('CODE_REVIEW','SECURITY_REVIEW','EXP_ACCEPT','TEST_PASS','VERIFIED','KNOWLEDGE','ACCEPT')
    } elseif ($RevisionKind -eq 'design') {
      if ([string]$state.revision.design -eq $RevisionValue) { Write-Output 'UNCHANGED'; exit 0 }
      $state.revision.design = $RevisionValue; $roots = if ($state.scale -eq 'large') { @('TECH_DESIGN') } elseif ($state.scale -eq 'medium') { @('DESIGN') } else { @('IMPLEMENTED') }
      $roots = @(Get-Descendants $definition $state.scale $roots | Where-Object { $_ -notin @('TECH_DESIGN','DESIGN') })
    } else {
      if (-not (Test-Field $state.artifacts $ArtifactId)) { Stop-Loop 'ARTIFACT_UNKNOWN' "未知 artifact：$ArtifactId" }
      $artifact = $state.artifacts.$ArtifactId; $artifact.status = 'missing'; $roots = @(ConvertTo-Array $artifact.provides)
    }
    $validRoots = @($roots | Where-Object { (Get-PropertyMap $state.exitCriteria).Contains($_) })
    $affected = Get-Descendants $definition $state.scale $validRoots
    $canonicalMap = Get-PropertyMap $definition.scales[[string]$state.scale]
    # 不适用的条件标准不因无关 code revision 反复 stale，避免重复派发和等待。
    foreach ($criterion in ConvertTo-Array $state.exitCriteria) {
      if ($affected -notcontains $criterion.id -or $criterion.status -notin @('done','skipped')) { continue }
      $canonical = $canonicalMap[[string]$criterion.id]
      if ($RevisionKind -eq 'code' -and $criterion.status -eq 'skipped' -and $canonical.conditional -and -not (Test-CriterionApplicable $criterion)) { continue }
      $criterion.status = 'stale'
    }
    if ($affected.Count -gt 0 -and $state.status -eq 'done') { $state.status = 'running' }
    $key = if ($EventId) { $EventId } else { "stale:${RevisionKind}:$RevisionValue$ArtifactId" }
    Add-History $state (New-HistoryEvent 'stale-cascade' ([pscustomobject]@{ roots = $validRoots }) ([pscustomobject]@{ affected = $affected; revision = $state.revision }) $key)
    Save-State $StatePath $state -WhatIfOnly:$DryRun; Write-Output $(if ($DryRun) { 'DRY_RUN' } else { "STALE: $($affected -join ',')" }); exit 0
  }

  if ($Command -eq 'transition') {
    if ($state.status -ne 'incomplete' -or $Status -notin @('running','abandoned')) { Stop-Loop 'INVALID_STATE_TRANSITION' "仅允许 incomplete -> running|abandoned，实际 $($state.status) -> $Status" }
    $before = $state.status; $state.status = $Status; $key = if ($EventId) { $EventId } else { "transition:${before}:$Status" }
    Add-History $state (New-HistoryEvent 'transition' ([pscustomobject]@{ status = $before }) ([pscustomobject]@{ status = $Status; revision = $state.revision }) $key)
    Save-State $StatePath $state -WhatIfOnly:$DryRun; Write-Output $(if ($DryRun) { 'DRY_RUN' } else { 'TRANSITIONED' }); exit 0
  }

  if ($Command -eq 'reconcile') {
    $unfinished = @(ConvertTo-Array $state.dispatchLedger | Where-Object { $_.status -in @('planned','spawned','acked','running','returned') })
    if ($unfinished.Count -gt 0) { Stop-Loop 'DISPATCH_NOT_RECONCILED' "存在 $($unfinished.Count) 个未评估 dispatch" }
    Write-Output 'PASS'; exit 0
  }

  if ($Command -eq 'dispatch-transition') {
    if ([string]::IsNullOrWhiteSpace($DispatchId) -or [string]::IsNullOrWhiteSpace($DispatchStatus)) { Stop-Loop 'DISPATCH_ARGUMENT_MISSING' 'dispatch-transition 需要 DispatchId/DispatchStatus' }
    $attempts = @(ConvertTo-Array $state.dispatchLedger); $matching = @($attempts | Where-Object dispatchId -eq $DispatchId)
    if ($matching.Count -ne 1) { Stop-Loop 'DISPATCH_ATTEMPT_INVALID' "dispatchId 必须唯一匹配：$DispatchId" }
    $attempt = $matching[0]; $before = [string]$attempt.status
    $next = @{ planned=@('spawned','failed'); spawned=@('acked','failed'); acked=@('running','failed'); running=@('returned','failed'); returned=@('evaluated','failed'); evaluated=@(); failed=@() }
    if ($next[$before] -notcontains $DispatchStatus) { Stop-Loop 'DISPATCH_TRANSITION_INVALID' "$before -> $DispatchStatus 非法" }
    $attempt.status = $DispatchStatus; $attempt | Add-Member updatedAt ([DateTime]::UtcNow.ToString('o')) -Force
    Add-History $state (New-HistoryEvent 'dispatch-transition' ([pscustomobject]@{ dispatchId=$DispatchId; status=$before }) ([pscustomobject]@{ dispatchId=$DispatchId; status=$DispatchStatus; revision=$state.revision }) $EventId)
    Assert-State $state $definition; Save-State $StatePath $state -WhatIfOnly:$DryRun; Write-Output $(if ($DryRun) { 'DRY_RUN' } else { 'DISPATCH_TRANSITIONED' }); exit 0
  }

  if ($Command -eq 'report-blocker') {
    if ([string]::IsNullOrWhiteSpace($Fingerprint)) { Stop-Loop 'BLOCKER_ARGUMENT_MISSING' 'report-blocker 需要 Fingerprint' }
    $existing = @(ConvertTo-Array $state.blockers | Where-Object fingerprint -eq $Fingerprint)
    if ($existing.Count -eq 0) { $state.blockers = @((ConvertTo-Array $state.blockers) + [pscustomobject]@{ fingerprint=$Fingerprint; status='open'; repairAttempts=@() }) }
    Add-History $state (New-HistoryEvent 'report-blocker' $null ([pscustomobject]@{ fingerprint=$Fingerprint; revision=$state.revision }) $EventId)
    Assert-State $state $definition; Save-State $StatePath $state -WhatIfOnly:$DryRun; Write-Output $(if ($DryRun) { 'DRY_RUN' } else { 'BLOCKER_RECORDED' }); exit 0
  }

  if ($Command -eq 'repair-blocker') {
    if ([string]::IsNullOrWhiteSpace($Fingerprint) -or [string]::IsNullOrWhiteSpace($RepairEvidence) -or [string]::IsNullOrWhiteSpace($RepairOutcome)) { Stop-Loop 'BLOCKER_ARGUMENT_MISSING' 'repair-blocker 需要 Fingerprint/RepairEvidence/RepairOutcome' }
    Assert-EvidenceExists $RepairEvidence
    $matching = @(ConvertTo-Array $state.blockers | Where-Object fingerprint -eq $Fingerprint)
    if ($matching.Count -ne 1) { Stop-Loop 'BLOCKER_NOT_FOUND' "blocker 不存在或重复：$Fingerprint" }
    $blocker = $matching[0]; if ($blocker.status -notin @('open','escalated')) { Stop-Loop 'BLOCKER_ALREADY_RESOLVED' "$Fingerprint 已解决" }
    $repairId = if ($EventId) { $EventId } else { "repair:${Fingerprint}:$RepairEvidence" }
    if (@(ConvertTo-Array $blocker.repairAttempts | Where-Object eventId -eq $repairId).Count -gt 0) { Write-Output 'UNCHANGED'; exit 0 }
    $previousRepairs = @(ConvertTo-Array $blocker.repairAttempts)
    $blocker.repairAttempts = @($previousRepairs) + @([pscustomobject]@{ eventId=$repairId; evidenceRef=$RepairEvidence; outcome=$RepairOutcome; actor=$Actor; occurredAt=[DateTime]::UtcNow.ToString('o') })
    if ($RepairOutcome -eq 'resolved') { $blocker.status = 'resolved' } elseif (@($blocker.repairAttempts).Count -ge 3) { $blocker.status = 'escalated'; $state.status = 'blocked_escalation' } else { $blocker.status = 'open' }
    Add-History $state (New-HistoryEvent 'repair-blocker' $null ([pscustomobject]@{ fingerprint=$Fingerprint; status=$blocker.status; revision=$state.revision }) $repairId)
    Assert-State $state $definition; Save-State $StatePath $state -WhatIfOnly:$DryRun; Write-Output $(if ($DryRun) { 'DRY_RUN' } else { 'BLOCKER_UPDATED' }); exit 0
  }

  if ($Command -eq 'complete') {
    Assert-State $state $definition -ForCompletion
    $notComplete = @(ConvertTo-Array $state.exitCriteria | Where-Object { $_.status -notin @('done','skipped') })
    if ($notComplete.Count -gt 0) { Stop-Loop 'STATE_NOT_COMPLETE' "未完成标准：$(($notComplete | ForEach-Object id) -join ', ')" }
    $before = $state.status; $state.status = 'done'; $key = if ($EventId) { $EventId } else { 'complete' }
    Add-History $state (New-HistoryEvent 'complete' ([pscustomobject]@{ status = $before }) ([pscustomobject]@{ status = 'done'; revision = $state.revision }) $key)
    Assert-State $state $definition
    Save-State $StatePath $state -WhatIfOnly:$DryRun; Write-Output $(if ($DryRun) { 'DRY_RUN' } else { 'COMPLETED' }); exit 0
  }
}
catch {
  Write-Output $_.Exception.Message
  exit 1
}
