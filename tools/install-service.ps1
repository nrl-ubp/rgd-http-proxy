# PowerShell script to install the RGD HTTP Proxy as a Windows Service using Apache Commons Procrun.
#
# Prerequisites:
# - The application must be built (mvn package) to produce the uber-jar in target/.
# - Java must be installed and JAVA_HOME set, or adjust the $javaPath variable below.
# - This script must be run as an Administrator.

param (
    [string]$serviceName = "RGDHttpProxy",
    [string]$serviceDisplayName = "RGD HTTP Proxy",
    [string]$serviceDescription = "HTTP Proxy with Kerberos support for RegData.",
    [string]$javaPath = "C:\_ETL\programs\jdk-17.0.2\bin\java.exe"
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
$prunsrv = Join-Path $scriptDir "procrun\prunsrv.exe"
$jarPath = Join-Path $projectRoot "target\rgd-http-proxy-1.1.0-runner.jar"

# Use the standard 64-bit prunsrv.exe
if (Test-Path (Join-Path $scriptDir "procrun\prunsrv.exe")) {
    $prunsrv = Join-Path $scriptDir "procrun\prunsrv.exe"
} else {
    Write-Host "ERROR: prunsrv.exe not found in $scriptDir\procrun\"
    exit 1
}

if (-not (Test-Path $jarPath)) {
    Write-Host "ERROR: Application JAR not found at $jarPath"
    Write-Host "Please build the project first using: mvn package"
    exit 1
}

if (-not (Test-Path $javaPath)) {
    Write-Host "ERROR: Java executable not found at $javaPath"
    Write-Host "Please adjust the -javaPath parameter or install Java."
    exit 1
}

Write-Host "Installing service '$serviceName'..."
Write-Host "  Executable: $prunsrv"
Write-Host "  JAR:        $jarPath"
Write-Host "  Java:       $javaPath"

& $prunsrv "//IS//$serviceName" `
    --DisplayName="$serviceDisplayName" `
    --Description="$serviceDescription" `
    --Install="$prunsrv" `
    --Startup=auto `
    --StartMode=jvm `
    --StopMode=jvm `
    --Classpath="$jarPath" `
    --StartClass="com.ubp.rgd.proxy.tools.WindowsServiceWrapper" `
    --StartMethod="main" `
    --StartParams="start" `
    --StopClass="com.ubp.rgd.proxy.tools.WindowsServiceWrapper" `
    --StopMethod="main" `
    --StopParams="stop" `
    --JavaHome="$javaPath" `
    --LogPath="$projectRoot\logs" `
    --StdOutput=auto `
    --StdError=auto `
    --LogPrefix="$serviceName"

if ($LASTEXITCODE -eq 0) {
    Write-Host "Service '$serviceName' installed successfully."
    Write-Host "You can start it using: .\start-service.ps1"
} else {
    Write-Host "ERROR: Failed to install service. Exit code: $LASTEXITCODE"
}