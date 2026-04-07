param(
  [string]$BaseUrl = "http://localhost:8090",
  [string]$ClientId = "JMETER-RUNNER",
  [int]$Threads = 120,
  [int]$RampSeconds = 30,
  [int]$Loops = 10,
  [int]$ThinkMs = 50,
  [string]$Plan = ".\order-load-100plus.jmx"
)

$ErrorActionPreference = "Stop"

$jmeter = Get-Command jmeter -ErrorAction SilentlyContinue
if (-not $jmeter) {
  $jmeterBat = Get-Command jmeter.bat -ErrorAction SilentlyContinue
  if ($jmeterBat) { $jmeter = $jmeterBat }
}

if (-not $jmeter) {
  Write-Host "JMeter executable not found in PATH. Install Apache JMeter and add bin to PATH." -ForegroundColor Red
  exit 1
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$planPath = Join-Path $scriptDir $Plan
$resultsDir = Join-Path $scriptDir "results"
if (-not (Test-Path $resultsDir)) { New-Item -ItemType Directory -Path $resultsDir | Out-Null }

$baseUri = [Uri]$BaseUrl
$protocol = $baseUri.Scheme
$domain = $baseUri.Host
$port = if ($baseUri.IsDefaultPort) {
  if ($protocol -eq "https") { 443 } else { 80 }
} else {
  $baseUri.Port
}

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$jtl = Join-Path $resultsDir "jmeter-100plus-$stamp.jtl"
$report = Join-Path $resultsDir "html-report-$stamp"

Write-Host "Running JMeter 100+ load test..." -ForegroundColor Cyan
Write-Host "Plan: $planPath"
Write-Host "Users: $Threads | Ramp: $RampSeconds s | Loops: $Loops | Think: $ThinkMs ms"

& $jmeter.Source -n `
  -t $planPath `
  -l $jtl `
  -e -o $report `
  -Jthreads=$Threads `
  -Jramp=$RampSeconds `
  -Jloops=$Loops `
  -JthinkMs=$ThinkMs `
  -Jprotocol=$protocol `
  -Jdomain=$domain `
  -Jport=$port `
  -JbaseUrl=$BaseUrl `
  -JclientId=$ClientId

if ($LASTEXITCODE -ne 0) {
  Write-Host "JMeter execution failed." -ForegroundColor Red
  exit $LASTEXITCODE
}

Write-Host "JMeter run completed." -ForegroundColor Green
Write-Host "JTL: $jtl"
Write-Host "HTML Report: $report\index.html"
