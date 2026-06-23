
Write-Host "Use this script to generate certs and private key for HTTPS in local DEVELOPMENT mode."
Write-Host "DO NOT USE IN PRODUCTION !"

Write-Host "Call set-pava-path.ps1 before executing this script."
Invoke-Expression ".\set-java-path.ps1"

Write-Host "Now using maven to execute key and cert generator..."
mvn dependency:resolve
mvn exec:java