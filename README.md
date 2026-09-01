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

## Flight SQL server

The proxy can also expose an **Apache Arrow Flight SQL** server that proxies a whole JDBC datasource.
Every statement is executed on the proxied database, and each value of the result sets matching an
RPS token (`RG{...}`) — or any pattern declared in the mapping file — is detokenized through the
RegData engine before being streamed back to the client. A token can be resolved from its column,
from a configured regex, or from the mapping index it carries in its own value.

Configuration (see `config/application.properties`):

| Property | Description |
| --- | --- |
| `proxy.flight-sql.enabled` | Enables the Flight SQL server (disabled by default). |
| `proxy.flight-sql.host` / `proxy.flight-sql.port` | Listening address of the gRPC server (default `0.0.0.0:32010`). |
| `proxy.flight-sql.jdbc-url` | JDBC URL of the proxied database. |
| `proxy.flight-sql.batch-size` | Rows per Arrow record batch, i.e. per detokenization call. |
| `proxy.flight-sql.mapping-config-file` | Column and data to RPS class/property mapping file. |

Clients authenticate with **basic credentials that are forwarded to the proxied database**: the
credentials are validated by opening a real connection, then every query runs under the caller's own
database identity.

Result sets carry no RPS metadata, so the RPS class and property names of each column are resolved
from `config/flight_sql_mapping_config.json`. That file also holds the right-context and
processing-context evidences sent to the engine:

```json
{
  "right-context": { "Target": "WDX1", "Module": "RoseGarden", "Right": "Transform" },
  "processing-context": { "Action": "Unprotect", "Target": "WDX1" },
  "data-mappings": [
    { "regex": "RG\\{[A-Z2-7x]{2}[a-zA-Z0-9\\-]{8}[a-zA-Z0-9]+\\}@example\\.com",
      "rps-class-name": "Person", "rps-property-name": "email" }
  ],
  "column-mappings": [
    { "table": "PERSON", "column": "FIRST_NAME",
      "rps-class-name": "Person", "rps-property-name": "shortString" },
    { "table": "*", "column": "EMAIL",
      "rps-class-name": "Person", "rps-property-name": "email" }
  ]
}
```

### Resolution order

Each string column of a result set is resolved in this order:

1. **`column-mappings` — explicit detokenization.** When the table and column of the result-set
   column are mapped, every `RG{...}` token of its values is detokenized with that RPS class and
   property. A `table` set to `*` (or omitted) makes the mapping apply to any table exposing that
   column, and an exact match always wins over a `*` one. Data mappings are **never** applied to a
   column that has a column mapping.
2. **`data-mappings` — implicit detokenization.** For the columns without a column mapping, each
   regex locates **its own** segments inside the values and supplies their RPS class and property.
   This makes it possible to detokenize columns that were never declared, and to recognize formats
   that are not plain `RG{...}` tokens.
3. **Token mapping index — last resort.** The `RG{...}` tokens that no data mapping claimed are
   resolved from the **mapping index they carry in their own value**, so a token can be detokenized
   even when nothing at all was configured for it.

The segment sent to the engine is the **whole match**, not a capturing group, so a regex may use
capturing or named groups freely for readability.

Data mappings are applied in **declaration order, which is their priority order**: a match
overlapping a segment already kept by an earlier mapping is discarded. Order the array from the most
specific pattern to the most generic one.

A value that no tier resolves is returned untouched, and a column is only rewritten when at least one
of its values holds a resolved segment.

### Token mapping index

The first two characters of a token value, right after `RG{`, carry its **mapping index**: a fixed
width of 2 characters holding an uppercase base26 number, left-aligned and padded with a lowercase
`x` filler.

| Symbol | Index | Rule |
| --- | --- | --- |
| `Ax` … `Zx` | 0 … 25 | one letter followed by the `x` filler |
| `ZA` … `ZZ` | 26 … 51 | `Z` is an escape prefix, the second letter carries the value |

The filler being lowercase, `Zx` (25) and `ZA` (26) never collide. Any other shape — `BA`, `A3`,
`2x` — resolves to nothing.

The index → `ClassName.PropertyName` table is **hardcoded** in
`FlightSqlTokenIndexResolver`, since it describes the tokens themselves rather than a deployment. Add
the missing indexes to its `INDEX_MAPPINGS` block; an entry may be declared padded (`"Bx"`) or not
(`"B"`), both being the same index. A token whose index is not declared is returned untouched and
logs a warning, once per unknown symbol.

> Implicit mode runs every data-mapping regex over every value of every unmapped string column, so
> keeping the list short and the patterns anchored matters on large result sets. A regex that fails
> to compile is logged and ignored, the rest of the configuration stays active.

Apache Arrow needs access to the `java.nio` internals of the JDK, so the server **must** be started
with `--add-opens=java.base/java.nio=ALL-UNNAMED` (see the launch commands below).

## Useful commands

Launch proxy in dev mode :

`mvn quarkus:dev -Djvm.args="--add-opens=java.base/java.nio=ALL-UNNAMED"`

Package with native profile :

`mvn clean package -Pnative`

Launch server:

`java --add-opens=java.base/java.nio=ALL-UNNAMED -jar ./target/rgd-http-proxy-1.0.0.jar`

Change version accordingly.

Test with curl (option -k to ignore self signed cert validation)

`curl -k https://localhost:8443/proxy/`

Use current user credentials to test Kerberos support in a powershell window:

`Invoke-WebRequest -uri "https://tokenization-secure-gvz-e01.corp.ubp.ch/proxy/me" -UseDefaultCredentials`

See *.sh and *.ps1 scripts for more cool scripts.