# Rose Garden's HTTP Proxy

## What is rgd-http-proxy ?

This program is an anonymization proxy server : an HTTP(s) server which passes request to the anonymization engine (RegData)
to protect and un-protect sensitive data through APIs. It has build-in support for Kerberos in the UBP IT landscape.
Once the caller's profile is loaded, the request is passed to the proxified server. 
Protection or un-protection occurs depending of the caller and the API being invoked. 

## Authentication and authorizations
To use the proxy http server, the caller MUST be authenticated. Authorized accounts can protect or unprotect data.
The proxy server supports plain Kerberos and basic authentication over Kerberos. 
It has an hybrid authentication support for maximum usage flexibility. 
Use Basic auth over HTTPS support for dev activities while full Kerberos support is used in production.
Both auth schemes are using Kerberos login Module to authenticate and load authorization AD groups. 

## Configuration 
The application can be configured using files in the ./config folder:
- application.properties : main configuration file for the application. **See the SAMPLE application.properties to configure the applicaiton.**
- krb5.ini and login.conf are configuration file for the Kerberos configuration and jass login config  used by GSSAPI.
- certs folder contains the rgd.keytab file and the HTTPS ssl certificate and key. See main application.properties file to configure the app.
To get keytab file for each environment must be obtained from cyberark. Certs for SSL HTTPS connection must be obtained from windows team.
For development, you can generate self signed certs using the maven command: `mvn exec:java`.

The `com.ubp.rgd.proxy.tools.ConfigGenerator` program can be used as a sample configuration generator.  

## Bypassing the transformation

A caller can ask the proxy to forward a request **without any RPS transformation**, whatever the
endpoint transform configuration says, by sending:

```
X-Proxy-Ignore-Transform: true
```

The value is compared ignoring case and surrounding whitespace: only `true` activates the bypass, any
other value — and a missing header — keeps the configured behaviour. The header is honoured by the
pre and post filters, and it is **stripped** before the request reaches the proxied service, like the
other `X-Proxy-*` control headers. It has no effect on the `/transform` endpoint nor on the Flight SQL
server.

When the bypass is honoured, the response echoes `X-Proxy-Ignore-Transform: true`, so a caller can
tell that the payload was left untransformed — even when nothing was configured for that endpoint.

The feature is gated by `proxy.transform.allow-ignore-header` (default `true`). Set it to `false` to
refuse the bypass: a request carrying the header is then rejected with a **403**, rather than being
transformed silently.

> ⚠️ This is a plain request header, so while the property is enabled **any authenticated caller can
> retrieve untransformed payloads**. It bypasses the tokenization, not the authentication or the
> authorization.

## Monitoring 
This anonymization proxy uses micrometer and prometheus endpoint is available.
- Health endpoint : https://your-server-hostname/health
- Prometheus metrics: https://your-server-hostname/q/metrics

## Building and testing

Before to build the server, you need to provide credentials of the RegData API. 
The following system environment variables must be set prior top launch JUnit tests:
- proxy.transform.client-id : id for RPS client. Contact Anonymization team if you require one for your environment.
- proxy.transform.client-secret : client secret for the RegData API
- proxy.transform.engine-url : url of the transformation engine. Shenzo: https://engine-rgd-poc.ubp.ch
- proxy.transform.identity-url : url of the identity provider for RegData. Shenzo: https://identity-rgd-poc-corp-ubp.ch

To build and test the server you use standard maven command: `mvn install`

Be sure to adjust test config if required. The default configuration should be ok for basic testing.

There is one integration test that goes with full steps of the transformation
A mocked server is launched pr the test runs. This basic http server exposes one endpoitn that serve 
person identification data. 

The test is doing 2 calls and compare clear and protected data.
1. GET a person, forward to person server and protect data before responding
2. POST a protected person data, un protect and forward to person server.

## Useful commands

Launch proxy in dev mode :

`mvn quarkus:dev`

Package with native profile :

`mvn clean package -Pnative`

Launch server:

`java -jar ./target/rgd-http-proxy-1.0.0.jar`

Change version accordingly.

Test with curl (option -k to ignore self signed cert validation)

`curl -k https://localhost:8443/proxy/`

Use current user credentials to test Kerberos support in a powershell window:

`Invoke-WebRequest -uri "https://tokenization-secure-gvz-e01.corp.ubp.ch/proxy/me" -UseDefaultCredentials`

See *.sh and *.ps1 scripts for more cool scripts.