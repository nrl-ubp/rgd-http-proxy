#!/bin/bash

servername="localhost"
serverport="3443"

# import certs of TARGETED websites into trust store of proxy server.
openssl s_client -showcerts -connect "$servername:$serverport" </dev/null 2>/dev/null | openssl x509 -outform PEM > $servername.pem

# import into cacerts directly to avoid PKIX path validation exceptions
keytool -import -cacerts -alias ${servername} -file ${servername}.pem
