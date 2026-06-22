# scripts/run-loadtest.ps1
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$Jar = Join-Path $Root "target\Aplicacion-WhatsApp-1.0-SNAPSHOT.jar"
$Logs = Join-Path $Root "logs"

if (!(Test-Path $Jar)) {
    Write-Host "ERROR: No existe el JAR:" $Jar -ForegroundColor Red
    Write-Host "Compila primero con: mvn clean package -DskipTests"
    pause
    exit
}

New-Item -ItemType Directory -Force $Logs | Out-Null
New-Item -ItemType Directory -Force "loadtest-results" | Out-Null

$cmd = @"
cd '$Root'
Write-Host 'Ejecutando LoadGenerator con 50 clientes durante 65 segundos...' -ForegroundColor Cyan
Write-Host 'Durante la corrida: mata node3 alrededor del segundo 30 y presiona ENTER aquí para marcar la falla.' -ForegroundColor Yellow
java -cp '$Jar' whatsapp.loadtest.LoadGenerator 50 65 2>&1 | Tee-Object -FilePath 'logs\loadgenerator.log'
"@

Start-Process powershell -ArgumentList "-NoExit", "-Command", $cmd -WindowStyle Normal
