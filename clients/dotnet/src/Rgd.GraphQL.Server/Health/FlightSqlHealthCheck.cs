using Microsoft.Extensions.Diagnostics.HealthChecks;
using Microsoft.Extensions.Options;
using Rgd.GraphQL.Server.Configuration;
using Rgd.GraphQL.Server.Data;

namespace Rgd.GraphQL.Server.Health;

/// <summary>Reports whether the configured health query runs on the Flight SQL server.</summary>
public sealed class FlightSqlHealthCheck(IQueryExecutor executor, IOptions<FlightSqlOptions> options) : IHealthCheck
{
    public async Task<HealthCheckResult> CheckHealthAsync(HealthCheckContext context, CancellationToken cancellationToken = default)
    {
        try
        {
            await executor.QueryAsync(options.Value.HealthQuery, cancellationToken);
            return HealthCheckResult.Healthy($"Flight SQL server {options.Value.Address} reachable.");
        }
        catch (DataAccessException e)
        {
            return new HealthCheckResult(context.Registration.FailureStatus, e.Message);
        }
    }
}

/// <summary>Connects to the Flight SQL server at startup, so that a configuration problem shows at once.</summary>
public sealed class FlightSqlStartupCheck(
    IQueryExecutor executor,
    IOptions<FlightSqlOptions> options,
    ILogger<FlightSqlStartupCheck> logger) : IHostedService
{
    public async Task StartAsync(CancellationToken cancellationToken)
    {
        var settings = options.Value;
        try
        {
            await executor.QueryAsync(settings.HealthQuery, cancellationToken);
            logger.LogInformation("Connected to the Flight SQL server {Address}.", settings.Address);
        }
        catch (DataAccessException e) when (!settings.FailOnStartupError)
        {
            logger.LogWarning(
                "The Flight SQL server {Address} cannot be reached yet: {Reason} The queries will fail until it is.",
                settings.Address, e.Message);
        }
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;
}
