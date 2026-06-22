# scripts/start-demo.ps1
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

Write-Host "Iniciando nodos..." -ForegroundColor Cyan
powershell -ExecutionPolicy Bypass -File "$PSScriptRoot\start-nodes.ps1"

Write-Host "Esperando 8 segundos para que haya PEER_HELLO, HEARTBEAT y Bully..." -ForegroundColor Yellow
Start-Sleep -Seconds 8

Write-Host "Iniciando clientes..." -ForegroundColor Cyan
powershell -ExecutionPolicy Bypass -File "$PSScriptRoot\start-clients.ps1"

Write-Host "Demo levantada. Revisa las 6 consolas." -ForegroundColor Green
