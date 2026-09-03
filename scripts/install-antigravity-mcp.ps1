param(
    [string]$OfficeStudioRoot = ""
)

$ErrorActionPreference = "Stop"
if (-not $OfficeStudioRoot) {
    $candidate = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
    if (Test-Path (Join-Path $PSScriptRoot "python\mcp_server.py")) {
        $candidate = $PSScriptRoot
    }
    $OfficeStudioRoot = $candidate
}
$OfficeStudioRoot = (Resolve-Path $OfficeStudioRoot).Path
$serverScript = Join-Path $OfficeStudioRoot "python\mcp_server.py"
if (-not (Test-Path $serverScript)) { throw "MCP server not found: $serverScript" }

$configPaths = @(
    (Join-Path $env:USERPROFILE ".gemini\antigravity-ide\mcp_config.json"),
    (Join-Path $env:USERPROFILE ".gemini\config\mcp_config.json")
)

foreach ($configPath in $configPaths) {
    $parent = Split-Path $configPath -Parent
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    if (Test-Path $configPath) {
        Copy-Item -LiteralPath $configPath -Destination ($configPath + ".office-studio-backup") -Force
        $config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    } else {
        $config = [PSCustomObject]@{}
    }
    if (-not $config.mcpServers) {
        $config | Add-Member -MemberType NoteProperty -Name mcpServers -Value ([PSCustomObject]@{}) -Force
    }
    $entry = [PSCustomObject]@{
        serverUrl = "http://127.0.0.1:8765/mcp"
        headers = [PSCustomObject]@{}
    }
    $config.mcpServers | Add-Member -MemberType NoteProperty -Name "office-studio-ai" -Value $entry -Force
    $jsonText = $config | ConvertTo-Json -Depth 20
    [System.IO.File]::WriteAllText($configPath, $jsonText, (New-Object System.Text.UTF8Encoding($false)))
    Write-Output "Updated: $configPath"
}

Write-Output "Office Studio AI MCP installed. Restart MCP servers in Antigravity."
