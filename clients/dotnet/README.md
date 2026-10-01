# RGD GraphQL server (.NET)

An ASP.NET Core GraphQL server whose schema is **generated at startup from a model file**, and whose
queries are executed through an **Apache Arrow Flight SQL** connection — typically the Flight SQL
server of the rgd-http-proxy, which detokenizes the result sets on their way back.

```
GraphQL client ──HTTP──▶ Rgd.GraphQL.Server ──Flight SQL (gRPC/Arrow)──▶ rgd-http-proxy ──JDBC──▶ database
```

## Layout

```
Rgd.GraphQL.slnx
src/Rgd.GraphQL.Server/
  config/                    configuration directory, deployed next to the binaries
    model.json               the data model the GraphQL schema is generated from
    sample.application.properties
  Configuration/             .properties parser and IConfiguration provider, options classes
  Model/                     model classes, loader and validation
  Data/Sql/                  SQL generation (filters, ordering, paging, dialects)
  Data/FlightSql/            Flight SQL connection, authentication, Arrow reading
  GraphQL/                   schema generation (Hot Chocolate) and resolvers
  Health/                    /health check and startup connection check
tests/Rgd.GraphQL.Server.Tests/
  Integration/               end to end tests against a real in-process Flight SQL server
```

## Getting started

Requires the .NET 10 SDK.

```bash
cp src/Rgd.GraphQL.Server/config/sample.application.properties \
   src/Rgd.GraphQL.Server/config/application.properties   # then fill in the credentials
dotnet run --project src/Rgd.GraphQL.Server
```

The GraphQL endpoint and the Nitro IDE are served on <http://localhost:5080/graphql>, the SDL on
`/graphql?sdl` and the health check on `/health`.

```bash
dotnet test                                                 # unit and integration tests
dotnet publish src/Rgd.GraphQL.Server -c Release -o out     # out/config holds the model and the sample
```

## Configuration directory

At startup the server looks for its configuration directory, in this order:

1. the `--config-dir <path>` command line switch,
2. the `RGD_CONFIG_DIR` environment variable,
3. a `config` directory in the current working directory,
4. the `config` directory next to the binaries.

It must hold `application.properties` and the model file. A missing file, an invalid model or invalid
options **stop the startup** with an explicit message.

### application.properties

A Java style properties file (`#` comments, `=`/`:` separators, `\` continuations and escapes). See
[`sample.application.properties`](src/Rgd.GraphQL.Server/config/sample.application.properties) for
every key.

| Property | Default | Meaning |
|---|---|---|
| `server.urls` | ASP.NET Core default | Listening addresses, `;` separated. |
| `graphql.path` | `/graphql` | Path of the GraphQL endpoint. |
| `graphql.model-file` | `model.json` | Model file, relative to the configuration directory. |
| `graphql.enable-tooling` | `true` | Serves the Nitro IDE and allows introspection. |
| `graphql.include-exception-details` | `false` | Adds exception details to the GraphQL errors. |
| `flightsql.host` / `flightsql.port` | `localhost` / `32010` | Flight SQL server. |
| `flightsql.username` / `flightsql.password` | | Credentials sent as `Basic` authentication. |
| `flightsql.tls.enabled` | `false` | Connects over TLS. |
| `flightsql.tls.trust-server-certificate` | `false` | Skips the certificate validation, development only. |
| `flightsql.timeout-seconds` | `30` | Timeout of a statement, results included. |
| `flightsql.health-query` | `SELECT 1` | Statement of `/health` and of the startup check. |
| `flightsql.fail-on-startup-error` | `false` | Stops the startup when the Flight SQL server is unreachable. |
| `sql.dialect` | `Ansi` | `Ansi` or `SqlServer`: quoting, literals and paging syntax. |
| `sql.quote-identifiers` | `true` | Quotes the table and column names. |
| `sql.max-in-list-size` | `1000` | Most values an `in`/`nin` filter accepts. |
| `logging.level.<category>` | | Log level, e.g. `logging.level.Rgd.GraphQL.Server.Data=Debug` logs every statement. |

`application.properties` is ignored by git and is **not** copied to the build or publish output: each
deployment provides its own. Any property can be overridden by an environment variable, dots becoming
`__` and dashes being dropped — `FLIGHTSQL__PASSWORD` overrides `flightsql.password`, which keeps the
password out of the file when needed.

**Authentication.** Every Flight SQL call carries the `Basic` credentials until the server returns a
bearer token in the `authorization` response header, as the rgd-http-proxy does. The token is used from
then on, and when it is rejected the statement is retried once with the credentials.

### The model file

A JSON file (comments allowed) listing the entities, i.e. the tables, and their fields, i.e. the
columns. Unknown properties are rejected, so a typo cannot silently drop a setting.

```jsonc
{
  "entities": [
    {
      "name": "Person",              // GraphQL type name
      "table": "PERSON",
      "schema": "dbo",               // optional
      "pluralName": null,            // optional, e.g. "People"; defaults to "Persons"
      "defaultPageSize": 100,
      "maxPageSize": 1000,
      "fields": [
        { "name": "id", "column": "ID", "type": "Int", "key": true },
        { "name": "firstName", "column": "FIRST_NAME", "type": "String",
          "transform": { "className": "Person", "propertyName": "shortString", "jurisdiction": "CH" } },
        { "name": "notes", "column": "NOTES", "type": "String", "filterable": false, "sortable": false }
      ]
    }
  ]
}
```

| Field property | Default | Meaning |
|---|---|---|
| `type` | | `ID`, `String`, `Int`, `Long`, `Float`, `Decimal`, `Boolean`, `Date`, `DateTime`. |
| `key` | `false` | Part of the primary key: the arguments of the single entity query. |
| `nullable` | `true` | `false` makes the GraphQL field non-null. |
| `filterable` / `sortable` | `true` | Exposes the field in the `where` / `orderBy` inputs. |
| `transform` | | The column stores RegData tokens, see below. |

For each entity the schema exposes:

```graphql
type Query {
  person(id: Int!): Person
  persons(where: PersonFilter, orderBy: [PersonOrder!], limit: Int, offset: Int! = 0): [Person!]!
  personsCount(where: PersonFilter): Long!
}
```

`where` combines field filters (`eq`, `neq`, `in`, `nin`, `isNull`, plus `gt`/`gte`/`lt`/`lte` on
numbers and dates and `contains`/`startsWith`/`endsWith` on strings) with `and`, `or` and `not`.
Only the columns of the selected fields are read.

```graphql
{
  persons(where: { or: [{ firstName: { eq: "Jean-Claude" } }, { city: { startsWith: "Gen" } }] },
          orderBy: [{ city: DESC }], limit: 10) {
    id firstName city
  }
}
```

### Tokenized columns

A field with a `transform` holds RegData tokens. A clear value cannot be compared with a token, so the
filter values of such a field are wrapped in the [`transform()` and `transform_search()` SQL
extensions](../../README.md#the-transform-sql-extensions) of the rgd-http-proxy, which tokenize them
before the statement reaches the database:

| GraphQL filter | SQL sent to the Flight SQL server |
|---|---|
| `firstName: { eq: "Jean" }` | `FIRST_NAME = transform('Person', 'shortString', 'CH', 'Jean')` |
| `firstName: { in: ["A", "B"] }` | `FIRST_NAME IN (transform(...,'A'), transform(...,'B'))` |
| `firstName: { startsWith: "Je" }` | `FIRST_NAME LIKE transform_search('Person', 'shortString', 'CH', 'Je')` |

Range and `contains`/`endsWith` filters are not offered on these fields since tokens do not preserve
them. The values read back are detokenized by the proxy according to its own column mappings.

## Design notes

- **Literals, not parameters.** The proxy extensions only accept literal arguments, so the values are
  rendered as escaped literals. Identifiers come from the validated model only, never from a request.
- **One gRPC channel** is shared by all requests, HTTP/2 multiplexing the calls.
- **Errors.** Invalid filters and database failures are reported with a `code` extension
  (`INVALID_QUERY`, `DATA_ACCESS_ERROR`, `INVALID_LIMIT`, ...); the details of a database failure stay
  in the logs.
- **Tests.** The integration tests host a real Arrow Flight SQL server on Kestrel (gRPC, Arrow IPC,
  bearer token authentication) and build the GraphQL server from a configuration directory exactly as
  `Program` does.
