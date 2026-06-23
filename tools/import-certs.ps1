$webRequest = [Net.WebRequest]::Create("https://almbinaryrepo.corp.ubp.ch/")
try { $webRequest.GetResponse() } catch {}
$cert = $webRequest.ServicePoint.Certificate
$bytes = $cert.Export([Security.Cryptography.X509Certificates.X509ContentType]::Cert)
set-content -value $bytes -encoding byte -path "$pwd\site.cert"
 
 
$env:JAVA_HOME = "C:\_ETL\programs\jdk-17.0.2"
$env:PATH = $env:JAVA_HOME + "\bin;" + $env:JAVA_HOME
keytool -importcert -file .\site.cert -cacerts -alias almbinaryrepo