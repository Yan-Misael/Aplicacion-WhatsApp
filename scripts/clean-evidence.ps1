# scripts/clean-evidence.ps1
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

Remove-Item .\*.log -Force -ErrorAction SilentlyContinue
Remove-Item .\logs -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item .\loadtest-results -Recurse -Force -ErrorAction SilentlyContinue

New-Item -ItemType Directory -Force logs | Out-Null
New-Item -ItemType Directory -Force loadtest-results\charts | Out-Null

Write-Host "Evidencia anterior eliminada. Carpetas logs/ y loadtest-results/charts/ recreadas." -ForegroundColor Green
