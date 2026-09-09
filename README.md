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

### JSON paths in the transform configuration

The `json-path` of an attribute transform config is evaluated with
[JsonPath](https://github.com/json-path/JsonPath), and both definite paths (`$.customer.name`) and
indefinite ones (`$.persons[*].name`, `$..name`) are supported. Two behaviours are worth knowing:

- **A path absent from the payload is simply skipped** (logged at `debug`), so a single transform
  configuration can serve several payload shapes and optional fields cost nothing. The same applies
  to a `null` value, which holds nothing to protect.
- **A JSON number stays a JSON number.** The engine returns a number when it tokenizes a number, so
  `{"accountNumber": 42}` becomes `{"accountNumber": 8371}` and not `{"accountNumber": "8371"}`. The
  value is rebuilt from the exact digits of the token, so neither precision nor width is lost,
  whatever the size of the number. The same applies when unprotecting. Booleans, on the other hand,
  are written back as strings.

Two limits are worth knowing when mapping a numeric field:

- **A token starting with a zero loses it.** JSON numbers cannot carry a leading zero, so a
  format-preserving token such as `007` is written as `7` and will no longer detokenize. Avoid
  mapping numeric fields to an RPS property whose tokens may start with a zero.
- **A non-numeric token is written as a string.** If the engine returns something that is not a
  number for a numeric field, the value is kept as text rather than lost, a warning is logged, and
  the JSON type of that field changes.

Each match is written back to the exact location it was read from, so wildcards over arrays whose
elements do not all carry the mapped field are handled correctly.

### Word by word tokenization with `extract-regex`

An attribute transform config may carry an `extract-regex` — generated from the `x-cid-extractregex`
annotation of the swagger by `SwaggerTransformConfigGenerator`. When it is present, the value is
**not** tokenized as a whole: every substring the regex matches is tokenized on its own, and the
original formatting is rebuilt around the tokens.

With the regex `(?:\w(?<!_)|~)+` carried by most WDX1 name properties:

| Direction | Value | Result |
|---|---|---|
| Protect | `Jean-Claude DUSSE` | `RG{t1}-RG{t2} RG{t3}` |
| Protect | `O'Brien` | `RG{t1}'RG{t2}` |
| Protect | `  spaced  out  ` | `  RG{t1}  RG{t2}  ` |
| Unprotect | `RG{aaa}-RG{bbb} RG{ccc}` | `Jean-Claude DUSSE` |

Separators, apostrophes and leading or trailing whitespace are preserved exactly, and a value the
regex does not match at all is left untouched. This is the same behaviour as the `/transform`
endpoint, which shares the implementation (`com.ubp.rgd.proxy.transform.ValuePlan`).

The direction is read from the `Action` evidence of the endpoint's `processing-context`:

- **`Protect` with an `extract-regex`** — tokenized word by word.
- **`Protect` without an `extract-regex`** — the whole value is tokenized, as before.
- **`Unprotect`** — every `RG{...}` token found in the value is detokenized, for **every** attribute,
  whether or not it declares an `extract-regex`. A value that carries no token is therefore left
  untouched and is **no longer sent to the engine**, which is a change from the previous behaviour
  where the whole value was always sent.
- **No `Action` evidence, or any other action** — the whole value is transformed, as before.

Two things to keep in mind:

- **Numbers are never split.** A JSON number can carry neither a separator nor an `RG{...}` token, so
  numeric values are always transformed as a whole, in both directions. This is what keeps the
  numeric detokenization described above working, since the engine returns a plain number — `8371`,
  not `RG{...}` — for a tokenized number.
- **An invalid `extract-regex` fails the transformation** with an `RPSTransformException`. In the
  proxy this fails the request; in the file transformer the file is moved to the error directory.

The same rules apply to `FileTransformService`. Headers and query parameters have no `extract-regex`
and are unaffected.

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

The index → `ClassName.PropertyName` table depends on the RPS client the proxy is configured for, so
it is **built at startup** by `FlightSqlDetokenizeService.initTokenIndexMappings(clientId)` from
`proxy.transform.client-id` — the same API key used to reach the engine. That method declares each
index with `FlightSqlTokenIndexResolver.register(symbol, "ClassName.PropertyName")`; a symbol may be
given padded (`"Bx"`) or not (`"B"`), both being the same index.

The table can be rebuilt at any time with `FlightSqlDetokenizeService.loadTokenIndexMappings()`, which
clears it first so a reload never leaves a stale index behind. If the initialization fails, the error
is logged and the table is left **empty** rather than preventing the proxy from starting — this is
only the last resolution tier, so the affected tokens are simply returned untouched.

A token whose index is not declared is returned untouched and logs a warning, once per unknown symbol.

> Implicit mode runs every data-mapping regex over every value of every unmapped string column, so
> keeping the list short and the patterns anchored matters on large result sets. A regex that fails
> to compile is logged and ignored, the rest of the configuration stays active.

Apache Arrow needs access to the `java.nio` internals of the JDK, so the server **must** be started
with `--add-opens=java.base/java.nio=ALL-UNNAMED` (see the launch commands below).

### Prepared statements

The Flight SQL server supports **parameterized statements**, both queries and updates. The parameter
schema is derived from the proxied driver's `ParameterMetaData` and advertised to the client, the
Arrow batches sent by the client are bound onto the JDBC statement, and a prepared batch is executed
in a single round trip returning the summed update count.

If the proxied JDBC driver refuses to describe its parameters, the server falls back to an empty
parameter schema: statements without parameters keep working unchanged.

> **Known client limitation.** `Statement.addBatch()` / `executeBatch()` on a *plain* statement fails
> inside the Arrow Flight JDBC driver itself (a `NullPointerException` in its Avatica layer) and
> cannot be fixed on the server side. Use `PreparedStatement.addBatch()`, which works.

### Loading test data

`src/test/java/com/ubp/rgd/tools/PersonFlightSqlLoader.java` generates random persons and writes them
into a `PERSON` table **through the proxy**, using the Arrow Flight SQL JDBC driver and plain SQL (no
JPA). It then reads the rows back and reports how many values came back detokenized.

Start the proxy with `proxy.flight-sql.enabled=true`, then:

```
mvn -Pflight-sql-loader test-compile exec:exec \
    -Dloader.args="--user sa --password secret --rows 1000 --create-table"
```

The profile uses `exec:exec` so the forked JVM gets the required `--add-opens` flag.

| Switch | Default | Meaning |
| --- | --- | --- |
| `--host` / `--port` | `localhost` / `32010` | Flight SQL endpoint of the proxy. |
| `--user` / `--password` | `sa` / empty | **Database** credentials: the proxy validates them by opening a real connection to the proxied datasource. |
| `--table` | `PERSON` | Target table name. |
| `--rows` | `10000` | Number of persons to generate. |
| `--locale` | `de-CH` | Faker locale for the generated data. |
| `--data-mode` | `tokens` | `tokens` emits token-shaped values, `clear` emits plain faker data. |
| `--insert-mode` | `prepared` | `prepared` uses parameter binding, `literal` uses quote-escaped literals. |
| `--batch-size` | `1000` | Rows per batch, prepared mode only. |
| `--create-table` | off | Drops and recreates the table first, with portable DDL (H2 and SQL Server). |
| `--no-read-back` | off | Skips the read-back phase. |
| `--read-back-rows` | `10` | Number of rows printed during read-back. |
| `--help` | | Prints the usage. |

The generated columns are chosen to exercise **all three** resolution tiers at once:

| Column | Resolved through |
| --- | --- |
| `FIRST_NAME`, `LAST_NAME`, `BIRTH_DATE` | column mapping on `PERSON` |
| `EMAIL` | column mapping on `*` |
| `CITY` | data-mappings regex |
| `NOTES` | token mapping index, tokens embedded in free text |

All token-bearing columns are `VARCHAR`, including `BIRTH_DATE`: a tokenized date does not fit a
`DATE` column.

> **Caveat.** `--data-mode tokens` produces *synthetic* tokens. Their shape is valid, so they fully
> exercise recognition and mapping resolution, but a real RPS engine has never issued them and will
> not return meaningful clear values. The Flight SQL proxy only ever detokenizes, so real tokens have
> to be obtained beforehand from the `/transform` endpoint.

`PersonFlightSqlLoaderTest` runs this same `main()` end to end against an in-memory H2 database
fronted by a real Flight SQL server, which is the reproducible version of the above.

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