using System.Net.Security;
using Apache.Arrow.Flight.Client;
using Apache.Arrow.Flight.Sql.Client;
using Grpc.Core.Interceptors;
using Grpc.Net.Client;
using Microsoft.Extensions.Options;
using Rgd.GraphQL.Server.Configuration;

namespace Rgd.GraphQL.Server.Data.FlightSql;

/// <summary>
/// The connection to the Flight SQL server: one gRPC channel, multiplexing the calls of every request.
/// </summary>
public sealed class FlightSqlConnection : IDisposable
{
    private readonly GrpcChannel _channel;

    public FlightSqlConnection(IOptions<FlightSqlOptions> options, ILogger<FlightSqlConnection> logger)
    {
        ArgumentNullException.ThrowIfNull(options);
        ArgumentNullException.ThrowIfNull(logger);

        var settings = options.Value;
        Address = settings.Address;

        var handler = new SocketsHttpHandler
        {
            EnableMultipleHttp2Connections = true,
            PooledConnectionIdleTimeout = Timeout.InfiniteTimeSpan,
            KeepAlivePingDelay = TimeSpan.FromSeconds(60),
            KeepAlivePingTimeout = TimeSpan.FromSeconds(30),
        };

        if (settings.Tls.Enabled && settings.Tls.TrustServerCertificate)
        {
            logger.LogWarning("The certificate of the Flight SQL server {Address} is not validated.", Address);
            handler.SslOptions = new SslClientAuthenticationOptions
            {
#pragma warning disable CA5359 // Explicitly requested by flightsql.tls.trust-server-certificate.
                RemoteCertificateValidationCallback = (_, _, _, _) => true,
#pragma warning restore CA5359
            };
        }

        _channel = GrpcChannel.ForAddress(Address, new GrpcChannelOptions
        {
            HttpHandler = handler,
            MaxReceiveMessageSize = settings.MaxReceiveMessageSizeMb * 1024 * 1024,
        });

        Authentication = new FlightSqlAuthInterceptor(settings.Username, settings.Password);
        var invoker = _channel.Intercept(Authentication);
        Client = new FlightSqlClient(new FlightClient(invoker));

        logger.LogInformation("Flight SQL client configured for {Address} as '{Username}'.", Address, settings.Username);
    }

    public Uri Address { get; }

    public FlightSqlClient Client { get; }

    public FlightSqlAuthInterceptor Authentication { get; }

    public void Dispose() => _channel.Dispose();
}
