# scripts/analyze-coordination.ps1
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$Jar = Join-Path $Root "target\Aplicacion-WhatsApp-1.0-SNAPSHOT.jar"
$OutDir = Join-Path $Root "loadtest-results"
$OutFile = Join-Path $OutDir "coordination-analysis.txt"

if (!(Test-Path $Jar)) {
    Write-Host "ERROR: No existe el JAR:" $Jar -ForegroundColor Red
    Write-Host "Compila primero con: mvn clean package -DskipTests"
    pause
    exit 1
}

New-Item -ItemType Directory -Force $OutDir | Out-Null

# Preferir EventLogger porque tiene formato estable y eventos ordenados por Lamport.
$LogFiles = @(
    "logs\events-node1.log",
    "logs\events-node2.log",
    "logs\events-node3.log"
)

# Fallback a consola si aún no se escribieron los events-nodeX.log.
if (!(Test-Path $LogFiles[0])) {
    $LogFiles = @(
        "logs\node1-console.log",
        "logs\node2-console.log",
        "logs\node3-console.log"
    )
}

Write-Host "Analizando logs:" -ForegroundColor Cyan
$LogFiles | ForEach-Object { Write-Host " - $_" }

& java -cp "$Jar" whatsapp.loadtest.CoordinationLogAnalyzer $LogFiles 2>&1 | Tee-Object -FilePath "$OutFile"

Write-Host ""
Write-Host "Análisis guardado en $OutFile" -ForegroundColor Green
