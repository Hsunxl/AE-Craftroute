# 从本地 Thunderbolt-Core 检出复制参考规划器源码到测试目录。
# 需要显式提供来源与目标路径，不在脚本中固定任何本机目录。
param(
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$Destination
)

$ErrorActionPreference = 'Stop'
$src = $Source
$dst = $Destination

if (-not (Test-Path $src)) { throw "Source path does not exist: $src" }

$plannerFiles = @(
  'CraftGraph.java', 'CraftPattern.java', 'CraftInput.java', 'CraftOutput.java',
  'CraftPlan.java', 'ReusableStockKey.java', 'ReusableStockRouteKey.java',
  'ReusableStockSource.java', 'ReusableStockUsageKey.java', 'Sat.java',
  'PlanningCancellation.java'
)
$refFiles = @(
  'ReferenceCapability.java', 'ReferenceMaterialMode.java', 'ReferenceRunResult.java',
  'ReferenceSupportStatus.java', 'ReferencePlanner.java', 'ReferenceScenario.java',
  'ReferenceCapabilityRunner.java', 'ThunderboltReferenceScenarios.java'
)

$plannerDst = Join-Path $dst 'com\moakiee\thunderbolt\core\planner'
$refDst = Join-Path $plannerDst 'reference'
New-Item -ItemType Directory -Force -Path $plannerDst, $refDst | Out-Null

foreach ($f in $plannerFiles) {
  Copy-Item -Path (Join-Path "$src\main\java\com\moakiee\thunderbolt\core\planner" $f) -Destination $plannerDst -Force
}
foreach ($f in $refFiles) {
  Copy-Item -Path (Join-Path "$src\test\java\com\moakiee\thunderbolt\core\planner\reference" $f) -Destination $refDst -Force
}

$count = (Get-ChildItem $plannerDst -Recurse -Filter *.java).Count
Write-Host "COPIED $count java files"
