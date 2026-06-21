# scripts/start-nodes.ps1
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

function Start-Node($title, $config, $logFile) {
    $cmd = @"
cd '$Root'
java -cp '$Jar' whatsapp.server.core.ServerNode '$config' 2>&1 | Tee-Object -FilePath '$logFile'
"@

    Start-Process powershell -ArgumentList "-NoExit", "-Command", $cmd -WindowStyle Normal
}

# Recomendado: partir node3 y node2 antes que node1 para evitar Connection refused inicial
Start-Node "node3" "config\node3.properties" "logs\node3-console.log"
Start-Sleep -Seconds 2

Start-Node "node2" "config\node2.properties" "logs\node2-console.log"
Start-Sleep -Seconds 2

Start-Node "node1" "config\node1.properties" "logs\node1-console.log"

Write-Host "Nodos iniciados. Espera unos segundos y revisa logs/." -ForegroundColor Green
