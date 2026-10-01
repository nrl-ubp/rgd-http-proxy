using System.Diagnostics;
using Apache.Arrow;
using Apache.Arrow.Flight.Sql;
using Grpc.Core;
using Microsoft.Extensions.Options;
using Rgd.GraphQL.Server.Configuration;

namespace Rgd.GraphQL.Server.Data.FlightSql;

/// <summary>Runs the statements through the Flight SQL client and materializes the Arrow record batches.</summary>
public sealed class FlightSqlQueryExecutor(
    FlightSqlConnection connection,
    IOptions<FlightSqlOptions> options,
    ILogger<FlightSqlQueryExecutor> logger) : IQueryExecutor
{
    public async Task<IReadOnlyList<object?[]>> QueryAsync(string sql, CancellationToken cancellationToken)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(sql);

        // FlightCallOptions.Timeout is not honoured by the client, the timeout is enforced by cancellation.
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(options.Value.Timeout);

        var stopwatch = Stopwatch.StartNew();
        logger.LogDebug("Executing {Sql}", sql);
        try
        {
            var rows = await ExecuteWithReauthenticationAsync(sql, timeout.Token);
            logger.LogDebug("{RowCount} row(s) read in {ElapsedMs} ms", rows.Count, stopwatch.ElapsedMilliseconds);
            return rows;
        }
        catch (Exception e) when (!cancellationToken.IsCancellationRequested && IsFlightFailure(e))
        {
            var status = FindRpcException(e)?.StatusCode
                         ?? (timeout.IsCancellationRequested ? StatusCode.DeadlineExceeded : StatusCode.Unknown);
            logger.LogError(e, "The Flight SQL server {Address} failed to execute {Sql} ({Status})", connection.Address, sql, status);
            throw new DataAccessException(Describe(status), e);
        }
    }

    /// <summary>A bearer token may have expired server side: it is then dropped and the statement run once more.</summary>
    private async Task<IReadOnlyList<object?[]>> ExecuteWithReauthenticationAsync(string sql, CancellationToken cancellationToken)
    {
        var usedBearerToken = connection.Authentication.HasBearerToken;
        try
        {
            return await ExecuteAsync(sql, cancellationToken);
        }
        catch (Exception e) when (usedBearerToken && FindRpcException(e)?.StatusCode == StatusCode.Unauthenticated)
        {
            logger.LogInformation("The Flight SQL bearer token was rejected, authenticating again.");
            connection.Authentication.ResetBearerToken();
            return await ExecuteAsync(sql, cancellationToken);
        }
    }

    private async Task<IReadOnlyList<object?[]>> ExecuteAsync(string sql, CancellationToken cancellationToken)
    {
        var client = connection.Client;
        var info = await client.ExecuteAsync(sql, options: NewCallOptions(), cancellationToken: cancellationToken);

        var rows = new List<object?[]>();
        foreach (var endpoint in info.Endpoints)
        {
            await foreach (var batch in client.DoGetAsync(endpoint.Ticket, NewCallOptions(), cancellationToken))
            {
                using (batch)
                {
                    ReadBatch(batch, rows);
                }
            }
        }

        return rows;
    }

    private static void ReadBatch(RecordBatch batch, List<object?[]> rows)
    {
        for (var row = 0; row < batch.Length; row++)
        {
            var values = new object?[batch.ColumnCount];
            for (var column = 0; column < batch.ColumnCount; column++)
            {
                values[column] = ArrowValues.Get(batch.Column(column), row);
            }

            rows.Add(values);
        }
    }

    /// <summary>The call options carry the headers the middleware writes to, hence one instance per call.</summary>
    private static FlightCallOptions NewCallOptions() => new();

    private static bool IsFlightFailure(Exception e) =>
        FindRpcException(e) is not null || e is OperationCanceledException or InvalidOperationException;

    /// <summary>The Flight SQL client wraps the gRPC failures into <see cref="InvalidOperationException"/>.</summary>
    private static RpcException? FindRpcException(Exception? e)
    {
        for (; e is not null; e = e.InnerException)
        {
            if (e is RpcException rpc)
            {
                return rpc;
            }
        }

        return null;
    }

    private static string Describe(StatusCode status) => status switch
    {
        StatusCode.Unauthenticated => "The Flight SQL server rejected the configured credentials.",
        StatusCode.PermissionDenied => "The configured Flight SQL account is not allowed to run this query.",
        StatusCode.Unavailable => "The Flight SQL server is unavailable.",
        StatusCode.DeadlineExceeded or StatusCode.Cancelled => "The query timed out.",
        _ => "The query failed on the Flight SQL server.",
    };
}
