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
The application can be configured using files in the `rgd-proxy-hub` folder (see
[Project structure](#project-structure)). The application reads them as `./config/...`, relative to
its working directory: start it, and run the Maven commands below, from `rgd-proxy-hub`.
- application.properties : main configuration file for the application. **See the SAMPLE application.properties to configure the applicaiton.**
- krb5.ini and login.conf are configuration file for the Kerberos configuration and jass login config  used by GSSAPI.
- certs folder contains the rgd.keytab file and the HTTPS ssl certificate and key. See main application.properties file to configure the app.
  To get keytab file for each environment must be obtained from cyberark. Certs for SSL HTTPS connection must be obtained from windows team.
  For development, you can generate self signed certs using the maven command `mvn exec:java`, run from `rgd-proxy-hub`.

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
- **An invalid `extract-regex` fails the transformation** with a `TransformException`. In the
  proxy this fails the request; in the file transformer the file is moved to the error directory.

The same rules apply to `FileTransformService`. Headers and query parameters have no `extract-regex`
and are unaffected.

## Choosing the transformer

The transformer is pluggable. Everything that does not depend on the tokenizer — reading the
configuration, locating the values in the payload, the headers and the query parameters, and writing
the results back — lives in `AbstractEndPointTransformer`; an implementation only provides
`transformData()`.

| `proxy.transform.impl` | Implementation | What it does |
| --- | --- | --- |
| `RPS` (default) | `RPSEndPointTransformer` | Tokenizes through the RegData RPS engine. |
| `FPE` | `FPEEndPointTransformer` | Encrypts locally with Format Preserving Encryption, no engine needed. |

Each implementation is shipped by its own module (`rgd-proxy-rps`, `rgd-proxy-fpe`, see
[Project structure](#project-structure)). `EndPointTransformerProducer` produces the configured one.
Each implementation carries a `@TransformerImpl` qualifier, so application code simply injects
`EndPointTransformer` and gets the selected one. An **unknown value, or the name of a module the
application is not built with, stops the application at startup** rather than falling back to a
default: running with another tokenizer than the intended one would protect the data the wrong way.
The error lists the implementations present.

A transformation failure, whatever the implementation, is reported to HTTP clients as
`Transform error: ...` (it was `RPS Transform error: ...` before version 2.0.0).

The property drives **every transformation of the application** — the proxy filters, the
`/transform` endpoint, the file transformation, the Flight SQL detokenization and the WDX1 concat
utility — which all share the same transformer instance. A value protected by one is therefore
readable by the others, and no class calls a tokenizer directly any more.

What a token looks like is asked to the transformer rather than hardcoded, which matters for Flight
SQL: result sets carry no transformation configuration, so the tokens have to be recognised by their
shape. An RPS token has a fixed layout (a two character mapping index, an eight character identifier,
then the value), while an FPE token is `RG{` + a two character header + the ciphertext, and may carry
separators inside it. The two patterns have nothing in common.

> Values protected by one implementation **cannot** be unprotected by the other. Do not change this
> property on an environment that already holds protected data.
>
> The **token mapping index** tier of the Flight SQL detokenization (`FlightSqlTokenIndexResolver`) is
> **RPS only**. It reads a mapping index from the first two characters of a token, which the RegData
> engine writes but FPE does not: those two characters are the radix marker and the padding length.
> The tier is therefore skipped under FPE, and a column resolved by neither a column mapping nor a
> data mapping is returned as is.
>
> `WDX1UtilsService` detokenizes the tokens of the **upstream WDX1 system**. If those were produced by
> another tokenizer than the configured one, they cannot be read back, and the service now **fails
> explicitly** rather than building its concatenation key from the raw token text.
>
> The `birthDateToken` of a concat request is detokenized like any other token; the **clear** date it
> yields is expected in the `dateFormat` declared by the request (default `yyyy-MM-dd`) and is then
> rendered as the `YYYYMMDD` component of the key. A clear value that is not a valid date in that
> format is rejected.

### Format Preserving Encryption

`FPEEndPointTransformer` encrypts with **FF1** (NIST SP 800-38G), provided by BouncyCastle. There is
no engine to reach and no token vault: protecting and unprotecting are the two directions of the same
deterministic cipher.

```properties
proxy.transform.impl=FPE
# hex encoded AES key: 32, 48 or 64 characters for 128, 192 or 256 bits
proxy.transform.fpe.key=2b7e151628aed2a6abf7158809cf4f3c
```

The key committed in `config/application.properties` is a **development key**. On a real environment
provide it through the `PROXY_TRANSFORM_FPE_KEY` environment variable, which Quarkus reads natively,
and never commit the real one. It is decoded at startup, so a wrong key fails immediately instead of
on the first payload carrying sensitive data — and only when `FPE` is actually selected.

#### Token format

The ciphertext is wrapped like an RPS token, because `RG{...}` is how `ValuePlan` locates the values
to unprotect. Inside the braces comes a two character header:

```
RG{ <radix marker> <padding length> <ciphertext> }
```

| Marker | Radix | Alphabet | Minimum length |
| --- | --- | --- | --- |
| `N` | 10 | `0123456789` | 6 |
| `A` | 62 | `0-9A-Za-z` | 4 |

The **radix marker** is needed because the alphabet is picked from the clear value when protecting,
but has to be recovered from the ciphertext when unprotecting. A value made of digits keeps the
numeric alphabet, so its ciphertext stays digits.

Only the characters of the alphabet are encrypted: separators, punctuation and accented letters stay
exactly where they were, which is what makes the transformation reversible.

```
"12-34"  ->  "RG{N483-9204}"          the dash never moved
"Jean-Claude DUSSE"                   with extract-regex, each word gets its own token
```

#### Short values are padded, not skipped

FF1 refuses any input with fewer than a million possible values, that is 6 digits or 4 alphanumeric
characters. Skipping shorter values would leave most numbers in the clear, so a short value is padded
with `0` up to the minimum, encrypted, and the number of padding characters is written into the token
header. Unprotecting decrypts and drops the filler:

```
"42"  ->  padded to "420000"  ->  RG{N4839204}
                                      ^ four padding characters were added
```

`0` is index 0 in both alphabets, so one filler serves both. Padding is deterministic, so equal values
still give equal tokens, and leading zeros survive: `007` comes back as `007`.

#### Behaviour worth knowing

- **Encryption is deterministic.** The same value always gives the same token within a property. That
  is a property of FPE, not a defect: it is what lets encrypted values be compared and joined.
- **Each property has its own tweak**, derived from `SHA-256("ClassName.PropertyName")`. The same
  value encrypts differently in two properties, so a token cannot be correlated across fields — and a
  token can only be read back with the tweak of the property it belongs to.
- **Numeric JSON fields become strings.** A tokenized number normally stays a JSON number, but
  `RG{...}` is not one, so such a field is written back as a string and the existing warning is
  logged. This is inherent to the wrapped token format.
- **A value carrying `{` or `}` is left unchanged**, with a warning: a brace kept in place inside the
  token would close it early and the value would come back truncated.
- **Values that cannot be transformed are left unchanged** rather than failing the request: an empty
  value, a value holding no character of the alphabet (`---`), a value that is not a token when
  unprotecting, and an RPS token, whose header is not an FPE one. Mixed payloads are therefore never
  corrupted.

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

## Selecting the RPS secrets manager

The RPS engine can protect each request with a different secrets manager, typically one per
jurisdiction. The caller names the jurisdiction:

- on `/proxy` and `/utils/wdx1/concat`, with a request header:

  ```
  X-Proxy-Jurisdiction: LU
  ```

  whose name is `proxy.transform.http-secrets-manager-header`;
- on `/transform`, with the `jurisdiction` property of each set of the payload. Each set is
  transformed with its own secrets manager, and the header is **ignored** by this endpoint.

The jurisdiction is looked up in the JSON file named by
`proxy.transform.http-secrets-manager-mapping-file`:

```json
{
  "default-mapping": "LU",
  "mappings": [
    {"CH": "16ec8462-e8d5-4a2c-b8df-f253e09bd274"},
    {"LU": "b9f72aef-6c1b-4556-bf65-9813f122cf8b"}
  ]
}
```

- Each mapping associates a jurisdiction with the **UUID** of an RPS secrets manager. Values are
  compared **ignoring case** (`lu` selects `LU`).
- A request **without the header**, or a `/transform` set **without `jurisdiction`**, uses the
  `default-mapping` entry.
- A jurisdiction **absent from the file** is rejected with a **400**: nothing is transformed nor
  forwarded, since tokens produced with another secrets manager could not be read back. On
  `/transform`, every set is checked before the first one is transformed, so one unknown
  jurisdiction rejects the whole request, and the message names the set
  (`Unknown jurisdiction of set #2 value: MC`).
- On `/proxy`, the request and the response transformations use the same secrets manager, and the
  header is **stripped** before the request reaches the proxied service.
- On `/utils/wdx1/concat`, the same secrets manager reads the input tokens and tokenizes the
  result. The Swagger UI (`/swagger-ui`) shows the header as an optional free-text field of this
  operation, under its configured name, with the accepted values and the default in its
  description. Leave it empty to use the default.
- The Flight SQL server and the file transformation do not use it: the secrets manager of each token
  they detokenize is resolved from the token itself, see below. When they protect values (the
  Flight SQL `transform()` extensions), the engine uses its default secrets manager.
- The file is read at startup, and the application **refuses to start** when it is missing or
  unreadable, when an id is not a UUID, when a value is mapped twice, or when `default-mapping` is
  absent or is not one of the mappings.
- The FPE transformer has a single key and ignores the secrets manager.

> The ids shipped in `config/secrets_manager_mapping.json` are placeholders: replace them with the ids
> of your RPS secrets managers.

### Resolving the secrets manager of a token

The Flight SQL result sets and the transformed files carry tokens that may come from different
jurisdictions, and nothing in the call says which. So when the RPS transformer **unprotects** values
**without an explicit secrets manager**, it resolves the secrets manager that created each token
from the token itself (`TokenSecretsManagerResolver.secretsManagerIdResolve(token, className, propertyName)`).

At startup, the resolver loads from SQL Server the configuration of the client
`proxy.transform.client-id`: its RPS properties (class, property, property id, configuration id)
and the association of each secrets manager configuration with the client's secrets manager.
Each token is then resolved as follows:

1. the RPS class and property of the value must belong to the client;
2. **MongoDB** is queried, in the collection of the token format, for the token of that
   configuration and property:

   | Token format | Collection | Searched by |
   |---|---|---|
   | `RPSDOC+...+RPSDOC` | `SecretManagerTransformations` | MD5 of the value (`ValueHash`) |
   | `-888...8888` | `SequenceToken` | the value between the wrappers |
   | `RG{...}@example.com` | `EmailUidTokens` | the token without `RG{` and `}` |
   | `yyyy-MM-dd` | `DateTokenV2s` | the date at midnight UTC |
   | `RG{xxVALUE-AB}` | `ShiftedOPEShortTokens` | `VALUE` and the number in the letters after the last dash (`AA`=0 … `ZZ`=675, `AAA`=676, …) |

3. the document found either carries a `SecretsManagerId`, which is mapped to the secrets manager
   associated with the client, or a `SecretVersionId`, which **SQL Server** turns into the secrets
   manager id.

The values are then grouped by secrets manager and the engine is called once per group. Every
token is resolved before the first engine call, and any failure fails the whole detokenization
with a `SecretsManagerNotFoundException`: a property the client does not have, an unsupported token
format (such as an `RG{...}` token without a dash), a token MongoDB does not know, a secrets manager
configuration without association with the client, or a secret version SQL Server does not know.
The file is then moved to the error directory, and the Flight SQL client gets an error. Values that
are not tokens are sent along with the first group.

- `/proxy`, `/transform` and `/utils/wdx1/concat` always name the secrets manager, so they never
  query the databases; neither does protecting a value. The file transformation passes the default
  secrets manager of the mapping file when it protects, and none when it unprotects.
- Successful resolutions are cached in memory (cache `token-secrets-manager`, 10 000 entries,
  1 hour by default), so a token repeated in a result set or a file is looked up once. Failures
  are not cached.
- The connections are configured in `application.properties`: `quarkus.mongodb.connection-string`
  plus `proxy.secrets-manager-lookup.mongodb.database`, and the `secrets-manager-lookup` datasource
  (`quarkus.datasource."secrets-manager-lookup".*`). See `config/sample.application.properties`.
  When the datasource cannot be reached at startup, the error is logged and the proxy starts
  anyway; every resolution then fails.
- The FPE transformer is not concerned.

## Monitoring 
This anonymization proxy uses micrometer and prometheus endpoint is available.
- Health endpoint : https://your-server-hostname/health
- Prometheus metrics: https://your-server-hostname/q/metrics

## Project structure

The project is a Maven multi-module build (version 2.0.0): a common base, one module per feature, and
the application, which assembles them into a **single uber-jar**,
`rgd-proxy-hub`.

| Module | Artifact | Content |
| --- | --- | --- |
| `rgd-proxy-spi` | `rgd-proxy-spi` | The common base: the transformer contract shared by the application and the feature modules (`EndPointTransformer`, `AbstractEndPointTransformer`, `EndPointTransformerProducer`, `DefaultSecretsManagerProvider`, implemented by the application), the transform configuration (`transform.config`) and a neutral data model (`transform.model`: `MappedValue`, `ValueMapping`, `RightContext`, `ProcessingContext`, `Evidence`, `TransformException`). It does not depend on the RegData client. |
| `rgd-proxy-rps` | `rgd-proxy-rps` | RPS transformer (`com.ubp.rgd.proxy.rps`): `RPSEndPointTransformer`, the RegData engine client and the resolution of the secrets manager of a token (MongoDB, SQL Server, cache). The only module depending on `rps-engine-api-client`. Its defaults (datasource kind, cache sizing) are in `META-INF/microprofile-config.properties`. |
| `rgd-proxy-fpe` | `rgd-proxy-fpe` | FPE transformer (`com.ubp.rgd.proxy.fpe`): `FPEEndPointTransformer` (BouncyCastle). |
| `rgd-proxy-filetransform` | `rgd-proxy-filetransform` | File transformation (`com.ubp.rgd.proxy.filetransform`): `FileTransformService`, the `/api/file-transform` endpoints, the configuration (`filetransform.config`) and the `FileTransformTriggerApp` batch tool (`filetransform.tools`). See [File transformation](#file-transformation). |
| `rgd-proxy-flightsql` | `rgd-proxy-flightsql` | Flight SQL server (`com.ubp.rgd.proxy.flightsql`): the Arrow Flight SQL server, the `transform()` SQL extensions, the result set detokenization and its mapping configuration (`flightsql.config`). The only module depending on Apache Arrow. Also holds the ODBC client package and the `PersonFlightSqlLoader` tool. |
| `rgd-proxy-queue` | `rgd-proxy-queue` | IBM MQ queue proxy (`com.ubp.rgd.proxy.queue`): the reactive pipelines of the routes, the IBM MQ connections (`IbmMqConnectionFactoryProvider`), the `/api/queue/routes` endpoints, the readiness check and the route configuration (`queue.config`). The only module depending on the IBM MQ client. See [IBM MQ queue proxy](#ibm-mq-queue-proxy). |
| `rgd-proxy-hub` | `rgd-proxy-hub` | The application: proxy, `/transform`, WDX1, security. It depends on every feature module and builds the uber-jar. Its configuration is in `rgd-proxy-hub`; its tests include the Quarkus wiring tests of the features (`com.ubp.rgd.proxy.wiring`). |

The feature modules are plain jars carrying a Jandex index (`jandex-maven-plugin`), which is all
Quarkus needs to discover their beans, REST endpoints and OpenAPI annotations: there is no Quarkus
extension and no build step of our own. Everything is in the uber-jar; what runs is a matter of
configuration:

| Feature | Switch |
| --- | --- |
| Transformer | `proxy.transform.impl` (`RPS` or `FPE`) |
| File transformation | `proxy.file-transform.enabled` (default `false`) |
| Flight SQL server | `proxy.flight-sql.enabled` (default `false`) |
| IBM MQ queue proxy | `proxy.queue.enabled` (default `false`) |

The application talks to the transformers only through the SPI: it never uses a RegData or an FPE
class. `proxy.transform.impl` selects, at runtime, one of the transformers present.

- **Removing a transformer**: drop its dependency (`rgd-proxy-rps` or `rgd-proxy-fpe`) from
  `rgd-proxy-hub`. No code change is needed; `proxy.transform.impl` must then name the
  remaining one. Note that `FlightSqlDetokenizeService` still reads `proxy.transform.client-id`.
- **Adding a transformer**: create a `rgd-proxy-<name>` module on the model of `rgd-proxy-fpe`: a
  jar with a Jandex index holding an `AbstractEndPointTransformer` subclass annotated
  `@TransformerImpl("<NAME>")`, then add it to the root `pom.xml` and to the application's
  dependencies.

## Building and testing

Before to build the server, you need to provide credentials of the RegData API. 
The following system environment variables must be set prior top launch JUnit tests:
- proxy.transform.client-id : id for RPS client. Contact Anonymization team if you require one for your environment.
- proxy.transform.client-secret : client secret for the RegData API
- proxy.transform.engine-url : url of the transformation engine. Shenzo: https://engine-rgd-poc.ubp.ch
- proxy.transform.identity-url : url of the identity provider for RegData. Shenzo: https://identity-rgd-poc-corp-ubp.ch

To build and test the server you use standard maven command, from the repository root: `mvn install`.
The application tests run from `rgd-proxy-hub` (Quarkus requires it) and read `rgd-proxy-hub`.
The unit tests of the feature modules are self-contained and need neither the credentials nor a
database; the queue proxy tests replace the queue managers with embedded ActiveMQ Artemis brokers. The
Quarkus wiring tests of the features (`rgd-proxy-hub`)
run in the application, the Flight SQL one in front of an in-memory H2 database.

The build-time configuration (Swagger UI and OpenAPI paths, etc.) is read from
`rgd-proxy-hub` whatever the directory Maven is started from: the
`quarkus-maven-plugin` of the application sets `quarkus.config.locations` to that file.

Be sure to adjust test config if required. The default configuration should be ok for basic testing.

For the native executable and its container image, see [Native build and container image](#native-build-and-container-image).

There is one integration test that goes with full steps of the transformation
A mocked server is launched pr the test runs. This basic http server exposes one endpoitn that serve 
person identification data. 

The test is doing 2 calls and compare clear and protected data.
1. GET a person, forward to person server and protect data before responding
2. POST a protected person data, un protect and forward to person server.

## File transformation

The proxy transforms the JSON files dropped in source directories, with the transformer selected by
`proxy.transform.impl` and the same `entity-transform-configs` as the REST payloads. The feature is the
`rgd-proxy-filetransform` module.

| Property | Description |
| --- | --- |
| `proxy.file-transform.enabled` | Loads the configurations (default `false`). |
| `proxy.file-transform.config-file` | The configurations, default `./config/file_transform_config.json`. |

Each configuration names its source, target and error directories, the file pattern, the right and
processing contexts and the JSON paths to transform. While it is transformed, a file carries the
`work-in-progress-suffix`; it is then written to the target directory (in the same sub-directory with
`preserve-directory-structure`), or moved to the error directory when it fails.

- `scan-interval-seconds` greater than 0: the server scans the source directory at that interval, from
  startup on.
- `scan-interval-seconds` = 0: the configuration only runs on demand, through
  `POST /api/file-transform/trigger/{configName}`. The call returns once every file has been dealt
  with, with a 500 as soon as one failed. `GET /api/file-transform/configurations` and
  `GET /api/file-transform/configuration/{configName}` describe the loaded configurations.

Protecting uses the default secrets manager of `proxy.transform.http-secrets-manager-mapping-file`;
unprotecting lets the transformer resolve the secrets manager of each token.

### Triggering from a batch scheduler

`FileTransformTriggerApp` calls the trigger endpoint of a running server, over HTTP, and turns the
outcome into an exit code: 0 when every file was transformed; 1 unknown configuration; 2 invalid
arguments; 3 server unreachable or timed out; 4 every file failed; 5 some files failed; 6 no file
matched; 7 the configuration is scheduled and cannot be triggered. It is in the runner jar:

```
java -cp rgd-http-proxy-2.0.0-runner.jar com.ubp.rgd.proxy.filetransform.tools.FileTransformTriggerApp <config-name> [host:port]
```

`host:port` defaults to `localhost:8080`; `-Dproxy.trigger.timeout-minutes=<n>` (default 10) bounds
the wait.

> **The class name changed** when the file transformation became a module of its own: it was
> `com.ubp.rgd.proxy.tools.FileTransformTriggerApp`. Update the scheduler jobs accordingly.

## IBM MQ queue proxy

The proxy transforms the messages flowing between IBM MQ queues: it listens on input queues, transforms
the JSON payload of each message with the transformer selected by `proxy.transform.impl` and the same
`entity-transform-configs` as the REST payloads, and puts the result on an output queue. The queues of
a route may be on different hosts and queue managers. The feature is the `rgd-proxy-queue` module.

| Property | Description |
| --- | --- |
| `proxy.queue.enabled` | Starts the routes (default `false`). |
| `proxy.queue.config-file` | The routes, default `./config/mq_transform_config.json`. |
| `proxy.queue.credentials.<alias>.user` / `.password` | The user and password of a credentials alias named by the routes. Set the password through the environment, e.g. `PROXY_QUEUE_CREDENTIALS_GVA_PASSWORD`. |
| `proxy.queue.receive-timeout` | How long a consumer waits for a message before polling again (default `1s`); also how long stopping may take. |
| `proxy.queue.reconnect.initial-delay` / `.max-delay` | Backoff of a route that lost a queue manager or the engine (default `1s`, doubling up to `60s`). |
| `proxy.queue.application-name` | The application name the queue managers show for the connections (default `rgd-proxy-queue`). |
| `proxy.queue.client-log` | Where the IBM MQ client writes its own log (default `./logs/mqjms.log`, rotated as `mqjms.log.0`...). |

### Routes

`mq_transform_config.json` lists the routes (sample: `rgd-proxy-hub/config/mq_transform_config.json`):

```json
[
  {
    "name": "protect_clients",
    "concurrency": 1,
    "input":  { "host": "mq-gva", "port": 1414, "queue-manager": "QMGVA01", "channel": "RGD.SVRCONN",
                "queue": "RGD.CLIENTS.CLEAR", "credentials": "gva", "cipher-suite": "TLS_AES_256_GCM_SHA384" },
    "output": { "host": "mq-lux", "port": 1414, "queue-manager": "QMLUX01", "channel": "RGD.SVRCONN",
                "queue": "RGD.CLIENTS.PROTECTED", "credentials": "lux", "cipher-suite": "TLS_AES_256_GCM_SHA384" },
    "error":  { "host": "mq-gva", "port": 1414, "queue-manager": "QMGVA01", "channel": "RGD.SVRCONN",
                "queue": "RGD.CLIENTS.ERROR", "credentials": "gva", "cipher-suite": "TLS_AES_256_GCM_SHA384" },
    "right-context":      { "Target": "WDX1", "Module": "QueueProxy", "Right": "Transform" },
    "processing-context": { "Target": "WDX1", "Module": "QueueProxy", "Action": "Protect" },
    "entity-transform-configs": [
      { "json-path": "$.client.lastName", "rps-class-name": "Person", "rps-property-name": "ShortString" }
    ]
  }
]
```

| Route field | Description |
| --- | --- |
| `name` | Unique name, used in the logs, the metrics and `/api/queue/routes`. |
| `concurrency` | Consumers of the input queue (default 1). With 1 the messages keep their order; more consumers trade the order for throughput. |
| `input`, `output` | The queue read, the queue written. |
| `error` | Optional: where the messages that cannot be transformed go, see below. |
| `right-context`, `processing-context` | The evidences sent to the transformer. `processing-context.Action` (`Protect` or `Unprotect`) is mandatory. |
| `entity-transform-configs` | What to transform in the payload, as for the REST payloads. |

| Endpoint field | Description |
| --- | --- |
| `host`, `port` | The listener of the queue manager (port default 1414). |
| `queue-manager`, `channel` | The queue manager and its server-connection channel. |
| `queue` | The queue name, or an IBM MQ queue URI: `queue:///RGD.OUT?targetClient=1` sends the messages without JMS header (no MQRFH2) to a non-JMS application, which drops the message properties. |
| `credentials` | Optional alias of the `proxy.queue.credentials.<alias>.*` properties; without it, no user is sent (channel authentication by MCAUSER or TLS certificate). |
| `cipher-suite` | Optional TLS cipher suite of the channel, see [TLS](#tls-of-the-queue-managers). |

Endpoints naming the same host, port, queue manager, channel, credentials and cipher suite share one
connection, whatever the number of routes and consumers. The file is validated at startup when the
queue proxy is enabled: a missing field, an unknown credentials alias, a duplicate name, an invalid
`Action` or a route reading and writing the same queue stops the application, with every defect listed.

### How a message is processed

Each consumer of a route is a reactive pipeline (Mutiny) on its own thread, running indefinitely:

1. **receive** a message from the input queue (`QueueListener`), in a transacted session;
2. **transform** its payload with the injected `EndPointTransformer` (`QueueMessageTransformer`);
3. **send** the result to the output queue (`QueueProducer`);
4. **commit**, which removes the message from the input queue.

A consumer only receives the next message once the previous one is committed: the pace is set by the
slowest of the queue managers and the transformer, and nothing piles up in the proxy.

- The payload is the text of a text message, or the UTF-8 content of a bytes message, and must be a
  JSON object or array.
- The transformed message has the type of the received one and keeps its correlation id, JMS type, user
  properties, persistence, priority and remaining time to live. The reply-to queue is not copied.
- Protecting uses the default secrets manager of `proxy.transform.http-secrets-manager-mapping-file`;
  unprotecting lets the transformer resolve the secrets manager of each token, as for the files.

The queue proxy is built on the IBM MQ Jakarta Messaging client rather than on SmallRye Reactive
Messaging: the routes are read from a file at startup, whereas Reactive Messaging channels must all be
declared at build time.

### Delivery and failures

- When the output queue is on the queue manager of the input queue, receiving and sending are one
  transaction: each message is transformed **exactly once**. Across queue managers, the output is
  committed before the input: a crash between the two commits sends the message again on restart
  (**at-least-once**); the consumers of the output queue must tolerate duplicates.
- A message that **cannot be transformed** (not JSON, not a text or bytes message, a value the
  transformer rejects) goes, unchanged, to the error queue with these properties:

  | Property | Content |
  | --- | --- |
  | `RgdRoute` | The route. |
  | `RgdErrorReason` | What went wrong, `ExceptionClass: message`. |
  | `RgdErrorClass` | The class of the root cause. |
  | `RgdErrorTime` | When, ISO-8601. |
  | `RgdOriginalMessageId` | The message id of the received message. |

  Without an error queue, the message is rolled back and received again after the backoff delay. Give
  the input queue a backout threshold and queue (`BOTHRESH`, `BOQNAME`): IBM MQ then moves the message
  aside after that many attempts, otherwise the route retries it forever.
- When the **infrastructure** fails (a queue manager, the transformation engine or a database
  unreachable: I/O errors, timeouts, messaging errors, 5xx responses), the message is rolled back, the
  consumer disconnects and connects again after the backoff, indefinitely; the route shows
  `RECONNECTING` meanwhile. No message is lost nor sent to the error queue for such failures.

The user of each channel needs `+connect +inq` on its queue manager, `+get +browse +inq` on the input
queue and `+put` on the output and error queues.

### TLS of the queue managers

With a `cipher-suite`, the connection is secured with TLS, using the key and trust stores of the JVM:

```
-Djavax.net.ssl.trustStore=./config/certs/mq-truststore.p12 -Djavax.net.ssl.trustStorePassword=...
-Djavax.net.ssl.keyStore=./config/certs/mq-keystore.p12 -Djavax.net.ssl.keyStorePassword=...   # mutual TLS only
-Dcom.ibm.mq.cfg.useIBMCipherMappings=false
```

`useIBMCipherMappings=false` is needed on the non-IBM JVMs, so that `cipher-suite` takes the standard
names (`TLS_AES_256_GCM_SHA384`, `TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384`...) matching the `SSLCIPH` of
the channel.

### Monitoring the routes

- `GET /api/queue/routes` and `GET /api/queue/routes/{name}`: the state of each route (`STARTING`,
  `RUNNING`, `RECONNECTING`, `STOPPED`), its connected consumers, its queues, the messages transformed,
  sent to the error queue and rolled back since startup, and the last error. No credentials.
- The readiness check `rgd-queue-routes` (`/health/ready`) is up when the queue proxy is disabled or
  every consumer of every route is connected, and gives the state of each route. A queue manager outage
  therefore makes the whole proxy not ready.
- Metrics, tagged with `route`:

  | Metric | Content |
  | --- | --- |
  | `ubp_proxy_queue_messages_total{outcome}` | Messages `transformed`, sent to the `error_queue`, `rolled_back`. |
  | `ubp_proxy_queue_transform_seconds` | Duration of the transformations. |
  | `ubp_proxy_queue_route_up` | 1 while every consumer of the route is connected. |

The queue proxy also runs in the native executable (see [Native build](#native-build-and-container-image)),
although IBM does not support its client in a native image: the configuration it needs is part of the
`rgd-proxy-queue` module, verified against IBM MQ 10.0.1.

## Flight SQL server

The proxy can also expose an **Apache Arrow Flight SQL** server that proxies a whole JDBC datasource.
It is part of the `rgd-proxy-flightsql` module, and disabled by default: `proxy.flight-sql.enabled=true`
starts it.
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
| `proxy.flight-sql.tls.enabled` | Serves Flight SQL over TLS (default `false`, plaintext). See [TLS](#tls). |
| `proxy.flight-sql.tls.key-store-file` / `.key-store-password` / `.key-store-alias` | PKCS12 keystore holding the server key and certificate chain. |
| `proxy.flight-sql.tls.cert-chain-file` / `.private-key-file` | Or a PEM certificate chain and an unencrypted PKCS#8 PEM key. |

Clients authenticate with **basic credentials that are forwarded to the proxied database**: the
credentials are validated by opening a real connection, then every query runs under the caller's own
database identity.

### TLS

The Flight SQL server is plaintext by default. Since clients send their database password at login,
**enable TLS for anything but local tests**, which is also what the ODBC driver expects by default
(see [Using Flight SQL through ODBC on Windows](#using-flight-sql-through-odbc-on-windows)):

```properties
proxy.flight-sql.tls.enabled=true
# Either a PKCS12 keystore, possibly the very one used for HTTPS...
proxy.flight-sql.tls.key-store-file=./config/certs/serverkeystore.p12
proxy.flight-sql.tls.key-store-password=changeit
#proxy.flight-sql.tls.key-store-alias=flightsql   # only when the keystore holds several keys
# ...or a PEM pair (the key must be PKCS#8: "BEGIN PRIVATE KEY")
#proxy.flight-sql.tls.cert-chain-file=./config/certs/flightsql-chain.pem
#proxy.flight-sql.tls.private-key-file=./config/certs/flightsql-key.pem
```

- A keystore is converted in memory: no unencrypted key is ever written to disk.
- **A TLS configuration that cannot be used fails startup** rather than falling back to plaintext:
  neither or both sources given, a missing file, a wrong password, several keys without an alias, an
  encrypted or PKCS#1 PEM key (convert it with `openssl pkcs8 -topk8 -nocrypt -in key.pem -out
  key-pkcs8.pem`).
- An expired or not yet valid certificate only logs a warning.
- The startup log states the transport: `Flight SQL server listening on 0.0.0.0:32010 (TLS, CN=...)`
  or `(plaintext)`, plus a warning while TLS is disabled.
- The certificate must name the host clients connect to (subject alternative name), e.g.
  `tokenization-secure-gvz-e01.corp.ubp.ch`, and be issued by a CA the clients trust. The
  certificates produced by `tools/generate-certs.*` are self-signed for `localhost`: tests only.

Result sets carry no RPS metadata, so the RPS class and property names of each column are resolved
from `config/flight_sql_mapping_config.json`. That file also holds the right-context and
processing-context evidences sent to the engine, grouped by **phase** of the exchange:

```json
{
  "before": {
    "active": true,
    "right-context": { "Target": "WDX1", "Module": "RoseGarden", "Right": "Transform" },
    "processing-context": { "Action": "Protect", "Target": "WDX1" }
  },
  "after": {
    "active": true,
    "right-context": { "Target": "WDX1", "Module": "RoseGarden", "Right": "Transform" },
    "processing-context": { "Action": "Unprotect", "Target": "WDX1" }
  },
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

### Phases

A query crosses the proxy twice, and the two directions do not use the same contexts:

| Phase | Applies to | Typical action | Status |
|---|---|---|---|
| `before` | the statement on its way to the database server | `Protect` | used by the `transform()` and `transform_search()` extensions |
| `after` | the result set on its way back, i.e. the detokenization | `Unprotect` | in use |

`active` defaults to **true**, so a phase that does not mention it is performed. Setting
`"active": false` on `after` turns the detokenization off: result sets are handed back exactly as
the database server returned them, tokens included. Setting it on `before` **rejects** any query
using `transform()` or `transform_search()`, rather than letting clear data reach the database.

The action of each phase is taken from its own `processing-context`. When none is declared, the
phase falls back to the only action that makes sense for its direction: `Unprotect` for `after`,
which only ever reads data back, and `Protect` for `before`, which only ever writes a value into a
query.

> **A mapping file that exists but cannot be used fails startup** when `proxy.flight-sql.enabled` is
> `true` — a malformed file, or one with an explicitly null `after` section. This is on purpose: the
> alternative is a proxy that silently hands tokens back to its clients. A **missing** file only logs
> a warning, since that is the normal state of a deployment that does not use Flight SQL. Unknown
> properties are ignored, so adding a key to the file can never wipe the rest of the configuration.

### The `transform()` SQL extensions

A client holding a **clear** value cannot compare it with a column that stores tokens. It wraps the
value in a `transform()` call, and the proxy replaces the call by the corresponding token **before**
the statement leaves for the database server:

```sql
SELECT * FROM PERSON WHERE FIRST_NAME = transform('Person', 'shortString', 'CH', 'Jean-Claude')
```

reaches the database as:

```sql
SELECT * FROM PERSON WHERE FIRST_NAME = 'RG{AB12345678aa}'
```

The arguments are, in order:

| # | Argument | Meaning |
|---|---|---|
| 1 | `className` | the RPS class name |
| 2 | `propertyName` | the RPS property name |
| 3 | `jurisdiction` | **parsed and validated, but ignored for now** |
| 4 | `text` | the clear value to transform |

It works in a query, in a prepared statement and in an update, so a value can be written tokenized
and found back through the same call. The contexts are those of the **`before`** phase, and **all
the calls of one statement are transformed in a single engine round trip**.

#### `transform_search()`, for a `LIKE` comparison

`transform_search()` takes **exactly the same four arguments** and obeys exactly the same rules, but
produces a `LIKE` **pattern** instead of the token: the token is truncated to its **first 9
characters** and a `%` is appended.

```sql
SELECT * FROM PERSON WHERE FIRST_NAME LIKE transform_search('Person', 'shortString', 'CH', 'Jean')
```

reaches the database as:

```sql
SELECT * FROM PERSON WHERE FIRST_NAME LIKE 'RG{AB1234%'
```

It exists for the cases where only the **head** of the token is stable, so that an equality on the
whole token would miss the rows a prefix match finds. Use it with `LIKE`, not with `=`: written
after an `=`, it would compare the column with the literal text `RG{AB1234%`.

- a token **shorter** than 9 characters is used whole, still followed by the `%`;
- a value the engine could not transform yields `NULL`, and `LIKE NULL` matches nothing;
- the two functions can be **mixed in the same statement**, and still cost one engine round trip for
  the whole of it.

##### Wildcards inside the token

A `%` or a `_` **of the token itself** would act as a wildcard and widen the search, so it is escaped
with a backslash and the pattern is followed by an `ESCAPE` clause:

```sql
WHERE OWNER LIKE 'RG{10\%20%' ESCAPE '\'
```

An RPS token never contains either, but a format-preserving one (`proxy.transform.impl=FPE`) can,
since it keeps the shape of the source value. A backslash of the token is escaped too, but only once
the clause is emitted — without it, a backslash is an ordinary character and escaping it would change
what the pattern matches.

> **The clause is appended only when the prefix really holds a wildcard.** `ESCAPE` is valid after
> the pattern of a `LIKE` predicate and **nowhere else**, so a `transform_search()` written somewhere
> else — in a `SELECT` list, to see what it produces — keeps working as long as its token is free of
> wildcards, and becomes a syntax error when it is not.

Details that matter in practice, for both functions:

- the name is matched in any case, and `transform (...)` with a space is accepted; a column or a
  longer identifier such as `my_transform(...)`, `transformed` or `transform_searches(...)` is left
  alone;
- a call written inside a **string literal, a quoted identifier or a comment** is *not* rewritten,
  so an existing query cannot be corrupted by the feature;
- the four arguments must be **single-quoted texts**, `''` being an escaped quote. A `?` parameter
  placeholder is rejected: it has no value yet when the query is rewritten;
- the token, or the pattern, is substituted as a quoted SQL literal, so the call can sit anywhere a
  value is expected;
- a malformed call **rejects the whole query** with `INVALID_ARGUMENT` and an explanatory message
  naming the function actually written, rather than passing meaningless SQL to the database. An
  engine failure returns `INTERNAL`;
- a query containing neither function is passed through untouched and never reaches the engine.

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
   even when nothing at all was configured for it. This tier is **RPS only** and is skipped when
   `proxy.transform.impl=FPE`, whose tokens carry no mapping index.

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

`rgd-proxy-flightsql/src/test/java/com/ubp/rgd/tools/PersonFlightSqlLoader.java` generates random persons and writes them
into a `PERSON` table **through the proxy**, using the Arrow Flight SQL JDBC driver and plain SQL (no
JPA). It then reads the rows back and reports how many values came back detokenized.

By default it does not fabricate tokens: it generates clear values and wraps each of them in a
[`transform()` call](#the-transform-sql-extensions), so the proxy tokenizes them for real on the way
in. The round trip is therefore a genuine one — tokenized by the engine on the way in, detokenized on
the way out.

Start the proxy with `proxy.flight-sql.enabled=true`, then, from the repository root
(after a `mvn install -DskipTests`):

```
mvn -Pflight-sql-loader -pl rgd-proxy-flightsql test-compile exec:exec \
    -Dloader.args="--user sa --password secret --rows 1000 --create-table --insert-mode literal"
```

The profile uses `exec:exec` so the forked JVM gets the required `--add-opens` flag.

| Switch | Default | Meaning |
| --- | --- | --- |
| `--host` / `--port` | `localhost` / `32010` | Flight SQL endpoint of the proxy. |
| `--user` / `--password` | `sa` / empty | **Database** credentials: the proxy validates them by opening a real connection to the proxied datasource. |
| `--table` | `PERSON` | Target table name. |
| `--rows` | `10000` | Number of persons to generate. |
| `--locale` | `de-CH` | Faker locale for the generated data. |
| `--data-mode` | `transform` | `transform` wraps every value in a `transform()` call so the proxy tokenizes it; `clear` emits plain faker data. |
| `--insert-mode` | `prepared` | `prepared` uses parameter binding, `literal` uses quote-escaped literals. **`--data-mode transform` requires `literal`.** |
| `--transform-class` | `Person` | RPS class name of the generated `transform()` calls. |
| `--transform-property` | `ShortString` | RPS property name of the generated `transform()` calls. |
| `--transform-jurisdiction` | `LU` | Jurisdiction argument, ignored by the proxy for now. |
| `--batch-size` | `1000` | Rows per batch, prepared mode only. |
| `--create-table` | off | Drops and recreates the table first, with portable DDL (H2 and SQL Server). |
| `--no-read-back` | off | Skips the read-back phase. |
| `--read-back-rows` | `10` | Number of rows printed during read-back. |
| `--help` | | Prints the usage. |

Every sensitive column is written with the **same** class and property, since a `transform()` call
states its mapping explicitly; the column mappings of the configuration only serve the *implicit*
detokenization of the columns that declare nothing.

All token-bearing columns are `VARCHAR`, including `BIRTH_DATE`: a tokenized date does not fit a
`DATE` column.

> **Reading back is a different matter.** The read-back resolves each column through the configured
> **column mappings**, so `BIRTH_DATE` and `EMAIL`, mapped to `Person.birthDate` and `Person.email`,
> come back detokenized with a different mapping than the one they were tokenized with. Align
> `--transform-property` with the column mappings, or expect those two columns not to round-trip.

> **`--data-mode tokens` no longer exists.** It fabricated synthetic tokens no engine had ever
> issued, so the read-back could only ever report them as still tokenized. The loader refuses the
> value and points at `transform`.

`PersonFlightSqlLoaderTest` runs this same program end to end against an in-memory H2 database
fronted by a real Flight SQL server, which is the reproducible version of the above.

## Using Flight SQL through ODBC on Windows

Windows tools (64-bit Excel, Power BI, Access, any ODBC application) reach the Flight SQL server
through the official, generic **Apache Arrow Flight SQL ODBC Driver** (Apache License 2.0). Its login
is our server's: the `UID`/`PWD` of the connection are the database credentials, sent in the standard
Flight handshake.

### The client package

```bash
mvn -Podbc-package -DskipTests package -pl rgd-proxy-flightsql -am
# -> rgd-proxy-flightsql/target/rgd-flightsql-odbc-<version>.zip
```

The `odbc-package` profile downloads the driver MSI from the Apache Arrow GitHub release (with Arrow's
`LICENSE.txt` and `NOTICE.txt`), **fails the build if its SHA-256 differs** from the pinned one, and
zips it with the scripts of `rgd-proxy-flightsql/src/odbc/`. It is kept out of the default build so that it does not
depend on GitHub. Properties:

| Property | Default |
|---|---|
| `odbc.dsn.name` | `RGD Flight SQL` |
| `odbc.dsn.host` / `odbc.dsn.port` | `tokenization-secure-gvz-e01.corp.ubp.ch` / `32010` |
| `odbc.dsn.use-encryption` | `true` |
| `odbc.driver.version` / `odbc.driver.sha256` | `25.0.1` / the checksum published with the release asset |
| `odbc.driver.url` | the GitHub release asset; point it to a mirror (Artifactory) when GitHub is not reachable |

e.g. `mvn -Podbc-package -DskipTests package -pl rgd-proxy-flightsql -am -Dodbc.dsn.host=flightsql-test.example.com`. When
upgrading the driver, update `odbc.driver.version` and `odbc.driver.sha256` together.

The zip holds:

| File | Role |
|---|---|
| `install.cmd`, `Install-RgdFlightSqlDsn.ps1` | Installs the driver if missing or older (UAC prompt for that step only), then creates the DSN. |
| `test.cmd`, `Test-RgdFlightSqlDsn.ps1` | Asks for a user name and password, connects, runs `SELECT 1`, reads the data types and tables. |
| `uninstall.cmd`, `Uninstall-RgdFlightSqlDsn.ps1` | Removes the DSN; `-RemoveDriver` also uninstalls the driver. |
| `datasource.psd1` | The defaults above, editable by the client before installing. |
| `README.txt` | Client instructions. |
| `driver\` | The MSI, `LICENSE.txt`, `NOTICE.txt`. |

The client unzips it and double-clicks `install.cmd`, then `test.cmd`. Highlights:

- **User DSN by default** (`-DsnType System` for every user of the machine, from an elevated prompt).
  Only the `msiexec` step is elevated, so the DSN lands in the calling user's registry, not the
  administrator's.
- **No credentials are stored in the DSN**: each application supplies `UID`/`PWD`.
- Idempotent: running it again changes nothing; a DSN with other settings is only replaced with
  `-Force`.
- `-TrustedCerts <pem>` for a private CA (the file is copied under `%LOCALAPPDATA%`/`%ProgramData%`,
  and the Windows store is then turned off, since the driver ignores `trustedCerts` otherwise);
  `-DisableCertificateVerification` for troubleshooting only.
- Exit codes for deployment tools: `0` OK, `1` failure, `2` invalid settings, `3` administrator rights
  required, `4` driver installation failed, `5` DSN exists with other settings. A transcript of every
  run goes to `logs\`.

A DSN-less connection string works too:

```text
Driver={Apache Arrow Flight SQL ODBC Driver};HOST=tokenization-secure-gvz-e01.corp.ubp.ch;PORT=32010;useEncryption=true;UID=<user>;PWD=<password>
```

### Points to know

- **64-bit only**: Apache publishes no 32-bit driver, so **32-bit Office cannot use it**.
- **The DSN uses TLS by default, the server does not**: enable [TLS](#tls) on the server, or build the
  package with `-Dodbc.dsn.use-encryption=false` for a test environment. A mismatch shows as a failed
  or hanging connection; `test.cmd` lists the likely causes.
- **The server certificate must name the DSN host**, and its CA must be trusted by the Windows
  certificate store of the client machines (normally the case for a corporate CA), or be given with
  `-TrustedCerts`.

### Metadata

ODBC tools browse the database through catalog calls. The Flight SQL server answers, under the
caller's own database identity:

| ODBC call | Flight SQL command | Source |
|---|---|---|
| `SQLGetInfo` | `GetSqlInfo` | server and database properties |
| `SQLTables`, `SQLColumns`, ... | `GetCatalogs`, `GetDbSchemas`, `GetTables`, `GetTableTypes` | `DatabaseMetaData` |
| `SQLGetTypeInfo` | `GetXdbcTypeInfo` | `DatabaseMetaData.getTypeInfo()`, translated to ODBC type codes |
| `SQLPrimaryKeys` | `GetPrimaryKeys` | `getPrimaryKeys()`, in key order |
| `SQLForeignKeys` | `GetImportedKeys`, `GetExportedKeys`, `GetCrossReference` | `getImportedKeys()`, `getExportedKeys()`, `getCrossReference()` |

JDBC types ODBC cannot represent (`ARRAY`, `STRUCT`, `OTHER`, vendor codes...) are left out of the
type list rather than mislabelled; notably JDBC `ROWID` (-8), whose code means `WCHAR` in ODBC.

## Native build and container image

The profile `native` of `rgd-proxy-hub` compiles the proxy, with every feature, to a Linux native
executable (GraalVM / Mandrel), which `rgd-proxy-hub/src/main/docker/Dockerfile.native-micro` packages
in a container image for `linux/amd64` and `linux/arm64`. The JVM uber-jar is unchanged.

| | JVM uber-jar | Native executable (arm64, all features enabled) |
| --- | --- | --- |
| Size | ~114 MB jar, plus a JVM | ~166 MB executable, ~184 MB image |
| Startup | ~0.6 s | ~0.1 s |
| Memory at rest (RSS) | ~320 MB | ~35 MB |

### Building the executable

Prerequisites: podman or docker, with ~12 GB of memory for its VM on macOS/Windows. No local GraalVM:
the executable is built in the Mandrel builder container (`quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21`),
so it is a Linux executable whatever the host. To build with a GraalVM installed on the host instead,
see [Building with a local GraalVM](#building-with-a-local-graalvm).

```
mvn install -DskipTests                               # the modules, once
mvn package -Dnative -DskipTests -pl rgd-proxy-hub    # ~2 min, ~5 GB of memory
# -> rgd-proxy-hub/target/rgd-proxy-hub-<version>-runner-linux-<arch>
```

- The architecture is the one of the host (`amd64` or `arm64`). **GraalVM cannot cross-compile**: build
  each architecture on a host of that architecture, e.g. an amd64 Linux build agent and an arm64 host.
  `-Dnative.arch=amd64|arm64` forces the other one by running the builder container under emulation
  (QEMU): possible, but slow and not supported by GraalVM.
- As for the JVM build, the build-time settings are read from `rgd-proxy-hub/config/application.properties`.
- The executable targets any x86-64 or ARMv8 server (`quarkus.native.march=compatibility`) and uses
  the `en-US` locale whatever the build host. For a build dedicated to known hardware, add
  `-Dquarkus.native.march=native`.

### Building with a local GraalVM

Give the home of a GraalVM for JDK 21 (Oracle GraalVM, GraalVM Community or Mandrel) and the profile
`native-local` builds with its `native-image`, without any container:

```
mvn package -Dnative -DskipTests -pl rgd-proxy-hub \
    -Dnative.graalvm-home=/Library/Java/JavaVirtualMachines/graalvm-jdk-21.0.11+9.1/Contents/Home
# -> rgd-proxy-hub/target/rgd-proxy-hub-<version>-runner-<os>-<arch>
```

GraalVM builds for the operating system **and** the architecture it runs on:

| Build host | Executable | Use |
| --- | --- | --- |
| Linux amd64 / arm64 | `-runner-linux-amd64` / `-runner-linux-arm64` | Same as the container build: goes in the image (`tools/build-native-image.sh`). The build host needs no podman or docker. |
| macOS (Apple silicon or Intel) | `-runner-macos-arm64` / `-runner-macos-amd64` | Runs on the Mac only, e.g. for development; never packaged in the image. |
| Windows | `-runner-windows-amd64` | Not tested. |

- `-Dnative.arch` has no effect: there is no emulation outside the container.
- The host needs the C toolchain `native-image` links with: on macOS the Xcode command line tools
  (`xcode-select --install`); on Linux `gcc`, `glibc-devel` and `zlib-devel` (RHEL names; `build-essential`
  and `zlib1g-dev` on Debian/Ubuntu); on Windows the Visual Studio C++ build tools.
- Run a macOS executable from the directory holding `config/`, like the jar:
  `cd rgd-proxy-hub && ./target/rgd-proxy-hub-2.0.0-runner-macos-arm64`.
- Everything else (native configuration of the modules, `march`, locale) is shared with the container build.
- Verified: a macOS arm64 executable built by Oracle GraalVM 21.0.11 (~90 s, ~183 MB, ~0.1 s startup) passes
  the same tests as the container build, IBM MQ and Flight SQL included; a Linux arm64 executable built
  by the GraalVM (Mandrel) of a Linux host without container build goes in the image and passes them too.

### Building the image

Gather the executables in `rgd-proxy-hub/target`, then:

```
tools/build-native-image.sh -t <registry>/rgd-proxy-hub:<version> [--push] [--runtime podman|docker]
```

With one executable the script builds a single-architecture image; with both `amd64` and `arm64` a
multi-architecture image (manifest list), which docker can only push (`--push`), podman also keep
locally. The Dockerfile has no `RUN` instruction, so assembling the other architecture needs no
emulation. The base image is `quay.io/quarkus/ubi9-quarkus-micro-image:2.0` (UBI 9 micro with glibc).

### Running the container

```
podman run -p 8080:8080 -p 8443:8443 -p 32010:32010 \
    -v ./config:/work/config:ro -v ./data:/work/data \
    -e PROXY_QUEUE_CREDENTIALS_GVA_PASSWORD=... \
    <registry>/rgd-proxy-hub:<version> [-D<property>=<value>...]
```

- The configuration directory of the environment (`application.properties`, certificates, keytab,
  `krb5.ini`, `login.conf`, the transform, file, queue and Flight SQL configurations) is mounted on
  `/work/config`, the working directory being `/work`; mount the directories of the file
  transformation as well. Secrets are better given as environment variables.
- The process runs as user 1001 and cannot listen below port 1024: keep `quarkus.http.ssl-port=8443`
  (not the `%prod` 443 of the Windows service) and map the host port, e.g. `-p 443:8443`.
- The arguments after the image name go to the executable, e.g. the trust store of the IBM MQ TLS
  channels (`-Djavax.net.ssl.trustStore=/work/config/certs/mq-truststore.p12 ...`). The
  `--add-opens` flag of the JVM is built into the executable.
- The command-line tools (`FileTransformTriggerApp`, `KeyAndCertificateGenerator`, `PersonFlightSqlLoader`,
  `SwaggerTransformConfigGenerator`) are not in the executable: use the JVM jar, or `curl` for the
  trigger endpoint.

### Native configuration of the modules

Libraries found by reflection, resources and class initialization need declaring for a native image.
Each module carries its own, in
`src/main/resources/META-INF/native-image/com.ubp.rgd.proxy/<module>/` (`native-image.properties`,
`reflect-config.json`, `resource-config.json`), and the JSON configuration classes carry
`@RegisterForReflection` (the SPI ones are in its `reflect-config.json`, the RegData client models in
`RpsClientNativeReflection`):

| Module | What the native image needs |
| --- | --- |
| `rgd-proxy-spi`, `rgd-proxy-rps` | The configuration classes and the RegData API models read and written by Jackson. |
| `rgd-proxy-hub` | ICU4J transliteration data (WDX1 names), the Kerberos (JGSS), JAAS, JNDI LDAP and SASL GSSAPI classes and security providers. |
| `rgd-proxy-flightsql` | Arrow, grpc-netty and protobuf initialized at run time, `java.nio` opened to Arrow, the Flight SQL protobuf messages, the Arrow schema classes; the Netty allocation manager is selected explicitly (`FlightSqlServerService`). |
| `rgd-proxy-queue` | The IBM MQ client initialized at run time, the classes it loads by name, its message catalogs, CCSID and charset data. |

When a new class is read from JSON, annotate it with `@RegisterForReflection`. When upgrading Apache
Arrow, the IBM MQ client or ICU4J, check the native executable again: the configuration was collected
with the GraalVM tracing agent and the native image tested against these flows (arm64):

- REST endpoints, Swagger UI, health, metrics; FPE `/transform`; WDX1 concat with ICU transliteration of
  12 scripts (identical to the JVM); file transformation, scheduled and triggered.
- Flight SQL in plaintext and TLS: login, `transform()`, `transform_search()`, detokenized results,
  prepared statements, metadata (against H2 in a test build; with SQL Server, up to the connection).
- IBM MQ queue proxy against IBM MQ 10.0.1: transformation with headers kept, error queue, reconnection
  after a queue manager restart, wrong password.
- Kerberos and LDAP up to the network only (keytab/password login, SPNEGO acceptor, LDAP SASL GSSAPI
  bind): **verify them in the target environment**, with its KDC and Active Directory.
- Not exercised: the RPS engine and the MongoDB / SQL Server lookups of the secrets managers (Quarkus
  extensions, and the RegData client models are registered).

## Useful commands

Launch proxy in dev mode, from the repository root (after a `mvn install -DskipTests`; live reload
covers the application module only, changes to the SPI or the feature modules need a new `mvn install`):

`mvn -pl rgd-http-proxy quarkus:dev -Djvm.args="--add-opens=java.base/java.nio=ALL-UNNAMED"`


Native executable and container image: see [Native build and container image](#native-build-and-container-image).

Launch server:

`cd rgd-http-proxy && java --add-opens=java.base/java.nio=ALL-UNNAMED -jar ./target/rgd-http-proxy-2.0.0-runner.jar`

The `--add-opens` flag is required by Apache Arrow, i.e. by the Flight SQL server; the proxy starts
without it while `proxy.flight-sql.enabled` is false.

Change version accordingly.

Test with curl (option -k to ignore self signed cert validation)

`curl -k https://localhost:8443/proxy/`

Use current user credentials to test Kerberos support in a powershell window:

`Invoke-WebRequest -uri "https://tokenization-secure-gvz-e01.corp.ubp.ch/proxy/me" -UseDefaultCredentials`

See *.sh and *.ps1 scripts for more cool scripts.