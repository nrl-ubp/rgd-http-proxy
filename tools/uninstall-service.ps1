# PowerShell script to uninstall the RGD HTTP Proxy Windows Service.
# It is recommended to stop the service before uninstalling.

param (
    [string]$serviceName = "RGDHttpProxy"
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$prunsrv = Join-Path $scriptDir "procrun\prunsrv.exe"

if (-not (Test-Path $prunsrv)) {
    Write-Host "ERROR: prunsrv.exe not found in $scriptDir\procrun\"
    exit 1
}

Write-Host "Uninstalling service '$serviceName'..."

& $prunsrv "//DS//$serviceName"

if ($LASTEXITCODE -eq 0) {
    Write-Host "Service '$serviceName' uninstalled successfully."
} else {
    Write-Host "ERROR: Failed to uninstall service. Exit code: $LASTEXITCODE"
}