$env:JAVA_HOME = "C:\_ETL\programs\java-21-openjdk-21.0.7.0.6-1.win.jdk.x86_64"
$env:PATH = $env:JAVA_HOME + "\bin;" + $env:JAVA_HOME

$webRequest = [Net.WebRequest]::Create("https://almbinaryrepo.corp.ubp.ch/")
try { $webRequest.GetResponse() } catch {}
$cert = $webRequest.ServicePoint.Certificate
$bytes = $cert.Export([Security.Cryptography.X509Certificates.X509ContentType]::Cert)
set-content -value $bytes -encoding byte -path "$pwd\site.cert"
keytool -delete -cacerts -alias almbinaryrepo
keytool -importcert -file .\site.cert -cacerts -alias almbinaryrepo

$webRequest = [Net.WebRequest]::Create("https://almbinaryinternet.corp.ubp.ch/")
try { $webRequest.GetResponse() } catch {}
$cert = $webRequest.ServicePoint.Certificate
$bytes = $cert.Export([Security.Cryptography.X509Certificates.X509ContentType]::Cert)
set-content -value $bytes -encoding byte -path "$pwd\site.cert"
keytool -delete -cacerts -alias almbinaryinternet
keytool -importcert -file .\site.cert -cacerts -alias almbinaryinternet
