# scripts/analyze-coordination.ps1
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$Jar = Join-Path $Root "target\Aplicacion-WhatsApp-1.0-SNAPSHOT.jar"
$OutDir = Join-Path $Root "loadtest-results"
$OutFile = Join-Path $OutDir "coordination-analysis.txt"

if (!(Test-Path $Jar)) {
    Write-Host "ERROR: No existe el JAR:" $Jar -ForegroundColor Red
    Write-Host "Compila primero desde NetBeans con Clean and Build."
    pause
    exit 1
}

New-Item -ItemType Directory -Force $OutDir | Out-Null

# Ajusta estos nombres según los logs que tengas.
$LogFiles = @(
    "logs\node1-console.log",
    "logs\node2-console.log",
    "logs\node3-console.log"
)

# Si no existen dentro de logs/, usa los logs antiguos de la raíz.
if (!(Test-Path $LogFiles[0])) {
    $LogFiles = @(
        "n1.log",
        "n2.log",
        "n3.log"
    )
}

Write-Host "Analizando logs:" -ForegroundColor Cyan
$LogFiles | ForEach-Object { Write-Host " - $_" }

& java -cp "$Jar" whatsapp.loadtest.CoordinationLogAnalyzer $LogFiles 2>&1 | Tee-Object -FilePath "$OutFile"

Write-Host ""
Write-Host "Análisis guardado en $OutFile" -ForegroundColor Green