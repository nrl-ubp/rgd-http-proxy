using System.Net;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace Rgd.GraphQL.Server.Tests.Integration;

/// <summary>
/// Starts the fake Flight SQL server on a free port, writes a configuration directory pointing at it, and
/// builds the GraphQL server from that directory exactly as <c>Program</c> does.
/// </summary>
public sealed class ServerFixture : IAsyncLifetime
{
    private WebApplication? _flightServer;
    private WebApplication? _graphQLServer;

    public FlightSqlServerState FlightState { get; } = new();

    public string ConfigDirectory { get; } = Directory.CreateTempSubdirectory("rgd-graphql-").FullName;

    public int FlightPort { get; private set; }

    public HttpClient Client { get; private set; } = null!;

    public async Task InitializeAsync()
    {
        _flightServer = await StartFlightServerAsync();

        WriteConfiguration(FlightSqlServerState.Password);
        _graphQLServer = BuildGraphQLServer();
        await _graphQLServer.StartAsync();
        Client = _graphQLServer.GetTestClient();
    }

    public async Task DisposeAsync()
    {
        Client?.Dispose();
        if (_graphQLServer is not null)
        {
            await _graphQLServer.DisposeAsync();
        }

        if (_flightServer is not null)
        {
            await _flightServer.DisposeAsync();
        }

        Directory.Delete(ConfigDirectory, recursive: true);
    }

    /// <summary>Writes <c>application.properties</c> and copies the shipped model into the configuration directory.</summary>
    public void WriteConfiguration(string password, string directory = "")
    {
        var target = directory.Length == 0 ? ConfigDirectory : directory;
        File.Copy(Path.Combine(AppContext.BaseDirectory, "config", "model.json"), Path.Combine(target, "model.json"), overwrite: true);
        File.WriteAllText(Path.Combine(target, "application.properties"), $"""
            # written by the integration tests
            flightsql.host=127.0.0.1
            flightsql.port={FlightPort}
            flightsql.username={FlightSqlServerState.Username}
            flightsql.password={password.Replace("=", "\\=", StringComparison.Ordinal)}
            flightsql.timeout-seconds=10
            graphql.model-file=model.json
            sql.dialect=SqlServer
            logging.level.Default=Warning
            """);
    }

    public WebApplication BuildGraphQLServer(string? configDirectory = null) =>
        ServerApplication.Build(
            ["--config-dir", configDirectory ?? ConfigDirectory],
            builder => builder.WebHost.UseTestServer());

    private async Task<WebApplication> StartFlightServerAsync()
    {
        var builder = WebApplication.CreateSlimBuilder();
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(o => o.Listen(IPAddress.Loopback, 0, l => l.Protocols = HttpProtocols.Http2));
        builder.Services.AddSingleton(FlightState);
        builder.Services.AddGrpc().AddFlightServer<FakeFlightSqlServer>();

        var app = builder.Build();
        app.MapFlightEndpoint();
        await app.StartAsync();

        var address = app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.Single();
        FlightPort = new Uri(address).Port;
        return app;
    }
}

[CollectionDefinition(Name)]
public sealed class ServerTestGroup : ICollectionFixture<ServerFixture>
{
    public const string Name = "server";
}
