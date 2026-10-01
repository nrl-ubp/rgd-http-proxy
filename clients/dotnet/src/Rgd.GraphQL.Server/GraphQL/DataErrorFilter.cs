using HotChocolate;
using HotChocolate.Execution;
using Rgd.GraphQL.Server.Data;
using Rgd.GraphQL.Server.Data.Sql;

namespace Rgd.GraphQL.Server.GraphQL;

/// <summary>
/// Hands the messages of the expected failures to the client, the ones of the others staying in the logs.
/// </summary>
public sealed class DataErrorFilter : IErrorFilter
{
    public IError OnError(IError error)
    {
        ArgumentNullException.ThrowIfNull(error);

        return error.Exception switch
        {
            SqlGenerationException e => error.WithMessage(e.Message).WithCode("INVALID_QUERY"),
            KeyNotFoundException e => error.WithMessage(e.Message).WithCode("INVALID_QUERY"),
            DataAccessException e => error.WithMessage(e.Message).WithCode("DATA_ACCESS_ERROR"),
            _ => error,
        };
    }
}
