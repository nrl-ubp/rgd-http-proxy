$env:JAVA_HOME = "D:\Programs\jdk-21.0.6"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

$version = "1.5.1"
$logDir = '.\logs'

New-Item -ItemType Directory -Path $logDir -Force | Out-Null

$ts  = (Get-Date).ToString('yyyy-MM-dd_HH-mm-ss')
$log = Join-Path $logDir "rgd-http-proxy-$ts.log"

java --add-opens=java.base/java.nio=ALL-UNNAMED -jar rgd-http-proxy-$version-runner.jar *>&1 | Tee-Object -FilePath $log
