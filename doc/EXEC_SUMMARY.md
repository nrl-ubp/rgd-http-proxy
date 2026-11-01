# Executive Summary — Rose Garden HTTP Proxy (`rgd-http-proxy` v2.0.0)

## Summary for management

rgd-http-proxy is a data-anonymization gateway that sits between our applications and their back-end services, so that sensitive personal data is protected automatically while it travels through APIs, files, SQL queries and message queues. It replaces confidential values (names, account numbers, emails…) with realistic, reversible tokens and restores the original values only for authorized callers, which lets teams work with data that is safe to handle yet still functional. One central service enforces enterprise authentication (Kerberos / Active Directory), monitoring and jurisdiction-specific key management, so protection does not have to be rebuilt in every application.

## What the application does

The application is a Java 21 / Quarkus 3.25 service. It passes data to an anonymization engine (RegData RPS), or encrypts it locally, to **protect** or **unprotect** sensitive fields. Which fields are transformed, and in which direction, depends on the caller and on the API being invoked. It exposes four data flows:

| Flow | Description |
| --- | --- |
| **HTTP(S) proxy** (`/proxy/**`) | Transparent reverse proxy. Pre/post filters transform requests and responses of a proxied service according to a JSON configuration (JSON paths, headers, URL paths, query parameters). |
| **Transform API** (`/transform`) | Standalone REST endpoint to protect/unprotect sets of values, each with its own jurisdiction. |
| **Flight SQL server** | BI and ODBC clients query a tokenized database; results are detokenized on the way back, and `transform()` / `transform_search()` SQL extensions let users search with clear values. |
| **IBM MQ queue proxy** | Reads JSON messages from input queues, transforms them and writes them to output queues (with error queues). |
| **File Transformation** | File transformation processes JSON files in configured directories, either on a scan interval or through a REST/batch trigger. |

## Architecture overview

- **Maven multi-module build, single uber-jar.** Feature modules are plain jars with a Jandex index. Everything ships in `rgd-proxy-hub`, and what actually runs is decided by configuration.
- **Pluggable transformer.** `proxy.transform.impl` selects `RPS` (RegData tokenization, default) or `FPE` (local Format-Preserving Encryption). An unknown value stops startup rather than falling back silently. Data protected by one transformer cannot be unprotected by the other.
- **Feature switches.** `proxy.file-transform.enabled`, `proxy.flight-sql.enabled` and `proxy.queue.enabled` (all default `false`).
- **Security.** Mandatory authentication: Kerberos in production, Basic over Kerberos for development. AD groups and SPNs drive authorization. Flight SQL forwards the caller's own database credentials. TLS is supported for HTTPS, Flight SQL and MQ channels.
- **Multi-jurisdiction key management.** An `X-Proxy-Jurisdiction` header (or a per-set `jurisdiction`) selects the RPS secrets manager. Unknown jurisdictions are rejected with HTTP 400.
- **Operations.** Health endpoints, Prometheus/Micrometer metrics, JSON logging, native-image and container build support, Windows service wrapper.

## Module analysis

| Module | Role |
| --- | --- |
| `lib/rps-engine-client-java` | Vendored RegData RPS engine API client library (HTTP client, models, engine context). Used only by `rgd-proxy-rps`. |
| `rgd-proxy-spi` | Common, engine-neutral base: the transformer contract (`EndPointTransformer`, `AbstractEndPointTransformer`, producer), the transform configuration model, and the neutral data model (`MappedValue`, `RightContext`, `ProcessingContext`, `Evidence`…). Does not depend on the RegData client. |
| `rgd-proxy-rps` | RPS transformer (default). Talks to the RegData engine and resolves, from the token itself, which secrets manager created it (MongoDB, then SQL Server, with caching). The only module that depends on the RegData client. |
| `rgd-proxy-fpe` | FPE transformer. Local, deterministic AES FF1 encryption (BouncyCastle) with `RG{…}` tokens. Needs no engine. Per-property tweaks, padding of short values, and untransformable values are left unchanged. |
| `rgd-proxy-filetransform` | File transformation service, `/api/file-transform` endpoints and the `FileTransformTriggerApp` batch tool for schedulers. |
| `rgd-proxy-flightsql` | Apache Arrow Flight SQL server: result-set detokenization, SQL `transform()` rewriting, mapping configuration, TLS support, token-mapping index resolution, ODBC client package for Windows and a test-data loader. The only module that depends on Arrow. |
| `rgd-proxy-queue` | IBM MQ proxy. Reactive (Mutiny) pipelines per route, shared connections, failure classification, error queues, reconnect backoff, `/api/queue/routes` endpoint and readiness health check. The only module that depends on the IBM MQ client. |
| `rgd-proxy-hub` | The deployable application. Hosts the proxy resource and filters, `/transform`, `/auth`, WDX1 token utilities (`/utils/wdx1/concat`), Kerberos/LDAP security, the `X-Proxy-Ignore-Transform` bypass (can be disabled), secrets-manager resolution, OpenAPI/Swagger, and configuration and certificate generator tools. Also holds the wiring tests of all features. |

## Technology stack

Java 21, Quarkus 3.25.0 (REST, ARC, cache, OpenAPI, health, Micrometer/Prometheus, Kerberos, TLS registry), Apache Arrow Flight SQL 19, IBM MQ Jakarta client 10.0.1.0, BouncyCastle 1.81, JsonPath, MongoDB and SQL Server (token lookup), Artemis and H2 for tests.

## Points of attention for decision makers

- **Bypass header:** while `proxy.transform.allow-ignore-header=true`, any authenticated caller can retrieve untransformed payloads. It can be switched off (requests are then answered with 403).
- **Transformer lock-in per environment:** do not change `proxy.transform.impl` on an environment that already holds protected data.
- **Secrets and certificates:** keytabs (CyberArk) and TLS certificates are environment-specific and must be provisioned. The sample secrets manager ids are placeholders.
- **Flight SQL is plaintext by default;** enable TLS for anything other than local tests.
