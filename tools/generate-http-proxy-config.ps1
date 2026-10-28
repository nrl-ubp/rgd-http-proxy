# Starts the proxy. -FlightSql starts the runner that contains the Flight SQL server
# (rgd-http-proxy-flightsql); it is only listening when proxy.flight-sql.enabled=true.
param(
    [switch]$wdx-swagger
)

$env:JAVA_HOME = "D:\Programs\jdk-21.0.6"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

$version = "2.0.0"
$logDir = '.\logs'


$runner =  "rgd-http-proxy-$version-runner.jar"

New-Item -ItemType Directory -Path $logDir -Force | Out-Null

$ts  = (Get-Date).ToString('yyyy-MM-dd_HH-mm-ss')
$log = Join-Path $logDir "rgd-http-proxy-$ts.log"

$mainclass = "com.ubp.rgd.proxy.tools.SwaggerTransformConfigGenerator"

$progargs = "--swagger-file ./config/wdx1-swagger-r13.3.json --output-file ./config/wdx1-transform-config-r13.3.json --path-prefix /openapi"

java --add-opens=java.base/java.nio=ALL-UNNAMED -cp $runner $mainclass $progargs
