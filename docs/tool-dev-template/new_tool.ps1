param(
    [Parameter(Mandatory = $true)]
    [string]$ToolName
)

<#
  R-15 工具脚手架：把 DiceTool 模板复制为 <ToolName>.java / <ToolName>Test.java。
  用法（在仓库根目录）：
    powershell -ExecutionPolicy Bypass -File docs/tool-dev-template/new_tool.ps1 -ToolName MyTool
#>

$ErrorActionPreference = "Stop"
$templateDir = Join-Path $PSScriptRoot
$web = Join-Path $PSScriptRoot "../../backend/web"
$mainDir = Join-Path $web "src/main/java/com/intelligent/agent/web/ai/tool/builtin"
$testDir = Join-Path $web "src/test/java/com/intelligent/agent/web/ai/tool/builtin"

if (-not (Test-Path $mainDir)) { throw "找不到 backend/web 主源码目录: $mainDir" }

$mainFile = Join-Path $mainDir "$ToolName.java"
$testFile = Join-Path $testDir "${ToolName}Test.java"

if ((Test-Path $mainFile) -or (Test-Path $testFile)) { throw "目标文件已存在: $ToolName" }

Copy-Item (Join-Path $templateDir "DiceTool.java") $mainFile
Copy-Item (Join-Path $templateDir "DiceToolTest.java") $testFile

Write-Host "已生成:"
Write-Host "  $mainFile"
Write-Host "  $testFile"
Write-Host "下一步：把类名/DiceTool/roll_dice 替换为 $ToolName，然后按 docs/tool-development.md 注册 Bean 并跑测试。"
