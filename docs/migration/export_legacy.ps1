param(
    [string]$SourceData = "D:\workspace\intelligent_agent\agent\data",
    [string]$ExportDir = "D:\workspace\intelligent_agent\docs\migration\export"
)

# ============================================================================
# Legacy logical-data export (Plan 3 / Task 6 data reconciliation)
#   - Copies business JSON/JSONL/MD files from agent/data into export/business
#   - Computes SHA-256 per file and writes manifest.json (LegacyExportManifest shape)
#   - Does NOT touch ChromaDB internals (venv/Python unavailable on this host);
#     Chroma volumes stay read-only until the acceptance window ends.
# ============================================================================

$ErrorActionPreference = "Stop"
$timestamp = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
$businessDir = Join-Path $ExportDir "business"
$collections = @()

Get-ChildItem -LiteralPath $SourceData -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Extension -in @(".json", ".jsonl", ".md") } |
    ForEach-Object {
        $relative = $_.FullName.Substring($SourceData.Length).TrimStart('\', '/')
        $target = Join-Path $businessDir $relative
        $targetDir = Split-Path $target -Parent
        New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $target -Force
        $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLower()
        $name = $relative -replace '[\\/]', '__'
        $collections += [ordered]@{
            name        = $name
            recordCount = 1
            sha256      = $hash
            jsonl       = ("business/" + ($relative -replace '\\', '/'))
        }
    }

$manifest = [ordered]@{
    id          = "legacy-export-$((Get-Date).ToString('yyyyMMdd-HHmmss'))"
    exported_at = $timestamp
    collections = @($collections)
}

$manifestJson = $manifest | ConvertTo-Json -Depth 6
$manifestPath = Join-Path $ExportDir "manifest.json"
[System.IO.File]::WriteAllText($manifestPath, $manifestJson, [System.Text.UTF8Encoding]::new($false))

Write-Output "Exported $($collections.Count) logical records to $businessDir"
Write-Output "Manifest: $manifestPath"
