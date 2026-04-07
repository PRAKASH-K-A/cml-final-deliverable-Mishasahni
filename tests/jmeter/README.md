# JMeter 100+ User Load Test

This folder contains a non-GUI Apache JMeter test plan that emulates **100+ concurrent users** against the exchange API.

## Files
- `order-load-100plus.jmx` - JMeter test plan
- `run-jmeter-100plus.ps1` - PowerShell runner
- `results/` - JTL and HTML reports

## Default Profile
- Threads (virtual users): `120`
- Ramp-up: `30` seconds
- Loops per user: `10`
- Think time: `50ms`
- Endpoint: `POST /api/orders/orchestrated`

## Run
From this folder:

```powershell
.\run-jmeter-100plus.ps1
```

Custom run example:

```powershell
.\run-jmeter-100plus.ps1 -BaseUrl "http://localhost:8090" -Threads 150 -RampSeconds 45 -Loops 12 -ThinkMs 25
```

## Prerequisites
1. Exchange backend running on `http://localhost:8090`
2. Apache JMeter installed and `jmeter` (or `jmeter.bat`) available in `PATH`

## Output
- Raw results: `results/jmeter-100plus-<timestamp>.jtl`
- HTML report: `results/html-report-<timestamp>/index.html`
