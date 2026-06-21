# scripts/generate-charts.ps1
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$Jar = Join-Path $Root "target\Aplicacion-WhatsApp-1.0-SNAPSHOT.jar"

if (!(Test-Path $Jar)) {
    Write-Host "ERROR: No existe el JAR:" $Jar -ForegroundColor Red
    pause
    exit
}

$csv = Get-ChildItem "loadtest-results" -Filter "loadtest_*.csv" |
       Sort-Object LastWriteTime -Descending |
       Select-Object -First 1

if ($null -eq $csv) {
    Write-Host "ERROR: No se encontró CSV en loadtest-results/." -ForegroundColor Red
    pause
    exit
}

Write-Host "Usando CSV:" $csv.FullName -ForegroundColor Cyan

java -cp "$Jar" whatsapp.loadtest.MetricsChartGenerator "$($csv.FullName)"

New-Item -ItemType Directory -Force "loadtest-results\charts" | Out-Null

Move-Item -Force "throughput.png" "loadtest-results\charts\throughput.png"
Move-Item -Force "latencia.png" "loadtest-results\charts\latencia.png"
Move-Item -Force "errores.png" "loadtest-results\charts\errores.png"

Write-Host "Gráficos generados en loadtest-results/charts/." -ForegroundColor Green
