# PowerShell script to start the RGD HTTP Proxy Windows Service.

param (
    [string]$serviceName = "RGDHttpProxy"
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$prunsrv = Join-Path $scriptDir "procrun\prunsrv.exe"

if (-not (Test-Path $prunsrv)) {
    Write-Host "ERROR: prunsrv.exe not found in $scriptDir\procrun\"
    exit 1
}

Write-Host "Starting service '$serviceName'..."

& $prunsrv "//ES//$serviceName"

if ($LASTEXITCODE -eq 0) {
    Write-Host "Service '$serviceName' started successfully."
} else {
    Write-Host "ERROR: Failed to start service. Exit code: $LASTEXITCODE"
}