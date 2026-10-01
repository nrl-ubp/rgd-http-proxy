namespace Rgd.GraphQL.Server.Data;

/// <summary>Runs SQL statements against the database.</summary>
public interface IQueryExecutor
{
    /// <summary>Runs a query and returns its rows, each value being at the position of its column in the result.</summary>
    Task<IReadOnlyList<object?[]>> QueryAsync(string sql, CancellationToken cancellationToken);
}

/// <summary>A failure of the database, reported to the GraphQL client without its internals.</summary>
public sealed class DataAccessException : Exception
{
    public DataAccessException()
    {
    }

    public DataAccessException(string message)
        : base(message)
    {
    }

    public DataAccessException(string message, Exception innerException)
        : base(message, innerException)
    {
    }
}
