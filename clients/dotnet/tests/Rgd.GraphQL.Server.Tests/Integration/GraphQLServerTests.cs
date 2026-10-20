using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Apache.Arrow;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;
using Rgd.GraphQL.Server.Configuration;
using Rgd.GraphQL.Server.Model;

namespace Rgd.GraphQL.Server.Tests.Integration;

[Collection(ServerTestGroup.Name)]
public sealed class GraphQLServerTests : IDisposable
{
    private readonly ServerFixture _fixture;

    public GraphQLServerTests(ServerFixture fixture)
    {
        _fixture = fixture;
        _fixture.FlightState.Reset();
    }

    public void Dispose() => _fixture.FlightState.Reset();

    [Fact]
    public async Task Exposes_the_schema_generated_from_the_model()
    {
        var sdl = await _fixture.Client.GetStringAsync("/graphql?sdl");

        Assert.Contains("person(id: Int!): Person", sdl, StringComparison.Ordinal);
        Assert.Contains("personsCount(where: PersonFilter): Long!", sdl, StringComparison.Ordinal);
        Assert.Contains("input TokenStringFilter", sdl, StringComparison.Ordinal);
        Assert.Contains("firstName: TokenStringFilter", sdl, StringComparison.Ordinal);
        Assert.Contains("city: StringFilter", sdl, StringComparison.Ordinal);
        Assert.DoesNotContain("notes: StringFilter", sdl, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Lists_rows_through_flight_sql_reading_only_the_selected_columns()
    {
        _fixture.FlightState.Responder = _ => new RecordBatch.Builder()
            .Append("ID", false, c => c.Int32(a => a.Append(1).Append(2)))
            .Append("FIRST_NAME", true, c => c.String(a => a.Append("Jean-Claude").AppendNull()))
            .Append("CITY", true, c => c.String(a => a.Append("Genève").Append("Zürich")))
            .Build();

        var data = await QueryAsync("""
            {
              persons(
                limit: 2, offset: 4,
                where: { or: [ { firstName: { eq: "Jean-Claude" } }, { city: { startsWith: "Z" } } ] },
                orderBy: [ { city: DESC } ]
              ) { id firstName city }
            }
            """);

        Assert.Equal(
            "SELECT [ID], [FIRST_NAME], [CITY] FROM [PERSON] "
            + @"WHERE ([FIRST_NAME] = transform('Person', 'shortString', 'CH', 'Jean-Claude') OR [CITY] LIKE 'Z%' ESCAPE '\') "
            + "ORDER BY [CITY] DESC, [ID] ASC OFFSET 4 ROWS FETCH NEXT 2 ROWS ONLY",
            Assert.Single(_fixture.FlightState.Statements));

        var persons = data.GetProperty("persons");
        Assert.Equal(2, persons.GetArrayLength());
        Assert.Equal(1, persons[0].GetProperty("id").GetInt32());
        Assert.Equal("Jean-Claude", persons[0].GetProperty("firstName").GetString());
        Assert.Equal("Genève", persons[0].GetProperty("city").GetString());
        Assert.Equal(JsonValueKind.Null, persons[1].GetProperty("firstName").ValueKind);
    }

    [Fact]
    public async Task Finds_a_row_by_its_key()
    {
        _fixture.FlightState.Responder = _ => new RecordBatch.Builder()
            .Append("EMAIL", true, c => c.String(a => a.Append("jc@example.com")))
            .Build();

        var data = await QueryAsync("{ person(id: 7) { email } }");

        Assert.Equal("jc@example.com", data.GetProperty("person").GetProperty("email").GetString());
        Assert.Equal(
            "SELECT [EMAIL] FROM [PERSON] WHERE [ID] = 7 ORDER BY [ID] ASC OFFSET 0 ROWS FETCH NEXT 2 ROWS ONLY",
            Assert.Single(_fixture.FlightState.Statements));
    }

    [Fact]
    public async Task Returns_null_for_an_unknown_key()
    {
        _fixture.FlightState.Responder = _ => new RecordBatch.Builder()
            .Append("EMAIL", true, c => c.String(_ => { }))
            .Build();

        var data = await QueryAsync("{ person(id: 404) { email } }");

        Assert.Equal(JsonValueKind.Null, data.GetProperty("person").ValueKind);
    }

    [Fact]
    public async Task Counts_rows_with_a_token_prefix_search()
    {
        _fixture.FlightState.Responder = _ => new RecordBatch.Builder()
            .Append("COUNT", false, c => c.Int64(a => a.Append(12_345_678_901)))
            .Build();

        var data = await QueryAsync("""{ personsCount(where: { lastName: { startsWith: "Dus" } }) }""");

        Assert.Equal(12_345_678_901, data.GetProperty("personsCount").GetInt64());
        Assert.Equal(
            "SELECT COUNT(*) FROM [PERSON] WHERE [LAST_NAME] LIKE transform_search('Person', 'shortString', 'CH', 'Dus')",
            Assert.Single(_fixture.FlightState.Statements));
    }

    [Fact]
    public async Task Uses_the_bearer_token_issued_by_the_server_and_authenticates_again_once_it_expires()
    {
        _fixture.FlightState.Responder = _ => new RecordBatch.Builder()
            .Append("COUNT", false, c => c.Int64(a => a.Append(1)))
            .Build();

        await QueryAsync("{ personsCount }");
        Assert.All(_fixture.FlightState.Authorizations, a => Assert.StartsWith("Bearer ", a, StringComparison.Ordinal));

        _fixture.FlightState.Authorizations.Clear();
        _fixture.FlightState.ExpireTokenOnce = true;

        var data = await QueryAsync("{ personsCount }");

        Assert.Equal(1, data.GetProperty("personsCount").GetInt64());
        var authorizations = _fixture.FlightState.Authorizations.ToList();
        Assert.StartsWith("Bearer ", authorizations[0], StringComparison.Ordinal);
        Assert.StartsWith("Basic ", authorizations[1], StringComparison.Ordinal);
    }

    [Fact]
    public async Task Reports_a_database_failure_without_its_internals()
    {
        _fixture.FlightState.Responder = _ => throw new InvalidOperationException("Table PERSON not found, internal detail");

        using var response = await PostAsync("{ persons { id } }");
        var error = response.RootElement.GetProperty("errors")[0];

        Assert.Equal("The query failed on the Flight SQL server.", error.GetProperty("message").GetString());
        Assert.Equal("DATA_ACCESS_ERROR", error.GetProperty("extensions").GetProperty("code").GetString());
        Assert.DoesNotContain("internal detail", response.RootElement.GetRawText(), StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("{ persons(limit: 5000) { id } }", "INVALID_LIMIT")]
    [InlineData("{ persons(offset: -1) { id } }", "INVALID_OFFSET")]
    [InlineData("""{ persons(where: { id: { eq: 1 }, city: { in: ["a", "b"] } }, limit: 0) { id } }""", "INVALID_LIMIT")]
    public async Task Rejects_invalid_paging(string query, string code)
    {
        using var response = await PostAsync(query);

        Assert.Equal(code, response.RootElement.GetProperty("errors")[0].GetProperty("extensions").GetProperty("code").GetString());
        Assert.Empty(_fixture.FlightState.Statements);
    }

    [Fact]
    public async Task Rejects_a_filter_the_schema_does_not_allow()
    {
        using var response = await PostAsync("""{ persons(where: { firstName: { contains: "ea" } }) { id } }""");

        Assert.True(response.RootElement.TryGetProperty("errors", out _));
        Assert.Empty(_fixture.FlightState.Statements);
    }

    [Fact]
    public async Task Reports_healthy_when_the_flight_sql_server_answers()
    {
        var response = await _fixture.Client.GetAsync("/health");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("Healthy", await response.Content.ReadAsStringAsync());
        Assert.Equal("SELECT 1", Assert.Single(_fixture.FlightState.Statements));
    }

    [Fact]
    public async Task Reports_unhealthy_and_fails_the_queries_with_wrong_credentials()
    {
        var directory = Directory.CreateTempSubdirectory("rgd-graphql-bad-").FullName;
        try
        {
            _fixture.WriteConfiguration("wrong password", directory);
            await using var app = _fixture.BuildGraphQLServer(directory);
            await app.StartAsync();
            using var client = app.GetTestClient();

            var health = await client.GetAsync("/health");
            using var response = await JsonDocument.ParseAsync(
                await (await client.PostAsJsonAsync("/graphql", new { query = "{ personsCount }" })).Content.ReadAsStreamAsync());

            Assert.Equal(HttpStatusCode.ServiceUnavailable, health.StatusCode);
            Assert.Equal(
                "The Flight SQL server rejected the configured credentials.",
                response.RootElement.GetProperty("errors")[0].GetProperty("message").GetString());
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public void Fails_the_startup_on_an_invalid_model()
    {
        var directory = Directory.CreateTempSubdirectory("rgd-graphql-invalid-").FullName;
        try
        {
            _fixture.WriteConfiguration(FlightSqlServerState.Password, directory);
            File.WriteAllText(Path.Combine(directory, "model.json"), """{ "entities": [ { "name": "1Person", "table": "P" } ] }""");

            var e = Assert.Throws<ModelException>(() => _fixture.BuildGraphQLServer(directory));
            Assert.Contains("not a valid GraphQL type name", e.Message, StringComparison.Ordinal);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public async Task Environment_variables_override_the_properties_file()
    {
        Environment.SetEnvironmentVariable("FLIGHTSQL__HEALTHQUERY", "SELECT 2");
        try
        {
            await using var app = _fixture.BuildGraphQLServer();
            var options = app.Services.GetRequiredService<IOptions<FlightSqlOptions>>().Value;

            Assert.Equal("SELECT 2", options.HealthQuery);
            Assert.Equal(_fixture.FlightPort, options.Port);
        }
        finally
        {
            Environment.SetEnvironmentVariable("FLIGHTSQL__HEALTHQUERY", null);
        }
    }

    [Fact]
    public void Fails_the_startup_without_application_properties()
    {
        var directory = Directory.CreateTempSubdirectory("rgd-graphql-empty-").FullName;
        try
        {
            var e = Assert.Throws<FileNotFoundException>(() => _fixture.BuildGraphQLServer(directory));
            Assert.Contains("application.properties", e.Message, StringComparison.Ordinal);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    private async Task<JsonElement> QueryAsync(string query)
    {
        using var response = await PostAsync(query);
        if (response.RootElement.TryGetProperty("errors", out var errors))
        {
            Assert.Fail(errors.GetRawText());
        }

        return response.RootElement.GetProperty("data").Clone();
    }

    private async Task<JsonDocument> PostAsync(string query)
    {
        var response = await _fixture.Client.PostAsJsonAsync("/graphql", new { query });
        return await JsonDocument.ParseAsync(await response.Content.ReadAsStreamAsync());
    }
}
