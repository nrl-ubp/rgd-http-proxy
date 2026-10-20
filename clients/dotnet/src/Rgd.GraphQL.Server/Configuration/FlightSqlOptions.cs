using System.ComponentModel.DataAnnotations;

namespace Rgd.GraphQL.Server.Configuration;

/// <summary>Connection to the Flight SQL server, bound from the <c>flightsql.*</c> properties.</summary>
public sealed class FlightSqlOptions
{
    public const string SectionName = "flightsql";

    [Required]
    public string Host { get; set; } = "localhost";

    [Range(1, 65535)]
    public int Port { get; set; } = 32010;

    /// <summary>Basic credentials, exchanged on the first call for a bearer token when the server issues one.</summary>
    [Required]
    public string Username { get; set; } = string.Empty;

    public string Password { get; set; } = string.Empty;

    [Range(1, 3600)]
    public int TimeoutSeconds { get; set; } = 30;

    /// <summary>Statement run by the health check and the startup check.</summary>
    [Required]
    public string HealthQuery { get; set; } = "SELECT 1";

    /// <summary>Stops the application when the Flight SQL server cannot be reached at startup.</summary>
    public bool FailOnStartupError { get; set; }

    [Range(1, 1024)]
    public int MaxReceiveMessageSizeMb { get; set; } = 64;

    public FlightSqlTlsOptions Tls { get; set; } = new();

    public Uri Address => new UriBuilder(Tls.Enabled ? Uri.UriSchemeHttps : Uri.UriSchemeHttp, Host, Port).Uri;

    public TimeSpan Timeout => TimeSpan.FromSeconds(TimeoutSeconds);
}

public sealed class FlightSqlTlsOptions
{
    public bool Enabled { get; set; }

    /// <summary>Accepts any server certificate. For development only.</summary>
    public bool TrustServerCertificate { get; set; }
}
