# scripts/start-clients.ps1
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$Jar = Join-Path $Root "target\Aplicacion-WhatsApp-1.0-SNAPSHOT.jar"
$Logs = Join-Path $Root "logs"

if (!(Test-Path $Jar)) {
    Write-Host "ERROR: No existe el JAR:" $Jar -ForegroundColor Red
    Write-Host "Compila primero desde NetBeans con Clean and Build."
    pause
    exit
}

New-Item -ItemType Directory -Force $Logs | Out-Null

function Start-Client($title, $port, $logFile) {
    $cmd = @"
cd '$Root'
java -cp '$Jar' whatsapp.client.ClienteNodo localhost $port 2>&1 | Tee-Object -FilePath '$logFile'
"@

    Start-Process powershell -ArgumentList "-NoExit", "-Command", $cmd -WindowStyle Normal
}

Start-Client "clienteA-node1" 5001 "logs\clienteA.log"
Start-Sleep -Milliseconds 500

Start-Client "clienteB-node2" 5002 "logs\clienteB.log"
Start-Sleep -Milliseconds 500

Start-Client "clienteC-node3" 5003 "logs\clienteC.log"

Write-Host "Clientes iniciados." -ForegroundColor Green
