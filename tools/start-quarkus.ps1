# Starts the proxy. -FlightSql starts the runner that contains the Flight SQL server
# (rgd-http-proxy-flightsql); it is only listening when proxy.flight-sql.enabled=true.
param(
    [switch]$FlightSql
)

$env:JAVA_HOME = "D:\Programs\jdk-21.0.6"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

$version = "1.5.1"
$logDir = '.\logs'
$runner = if ($FlightSql) { "rgd-http-proxy-flightsql-$version-runner.jar" } else { "rgd-http-proxy-$version-runner.jar" }

New-Item -ItemType Directory -Path $logDir -Force | Out-Null

$ts  = (Get-Date).ToString('yyyy-MM-dd_HH-mm-ss')
$log = Join-Path $logDir "rgd-http-proxy-$ts.log"

java --add-opens=java.base/java.nio=ALL-UNNAMED -jar $runner *>&1 | Tee-Object -FilePath $log
