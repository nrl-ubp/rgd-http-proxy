using System.Collections.Concurrent;
using System.Text;
using Apache.Arrow;
using Apache.Arrow.Flight;
using Apache.Arrow.Flight.Server;
using Arrow.Flight.Protocol.Sql;
using Google.Protobuf;
using Google.Protobuf.WellKnownTypes;
using Grpc.Core;

namespace Rgd.GraphQL.Server.Tests.Integration;

/// <summary>What the fake Flight SQL server saw, and how it answers.</summary>
public sealed class FlightSqlServerState
{
    public const string Username = "sa";
    public const string Password = "s3cr=t";
    public const string BearerToken = "token-42";

    public ConcurrentQueue<string> Statements { get; } = new();

    public ConcurrentQueue<string> Authorizations { get; } = new();

    /// <summary>Returns the result set of a statement.</summary>
    public Func<string, RecordBatch> Responder { get; set; } = _ => SingleInt("1", 1);

    /// <summary>Makes the next call fail as unauthenticated, as a server that expired the bearer token would.</summary>
    public bool ExpireTokenOnce { get; set; }

    public void Reset()
    {
        Statements.Clear();
        Authorizations.Clear();
        Responder = _ => SingleInt("1", 1);
        ExpireTokenOnce = false;
    }

    public static RecordBatch SingleInt(string name, int value) =>
        new RecordBatch.Builder().Append(name, false, c => c.Int32(a => a.Append(value))).Build();
}

/// <summary>
/// A minimal Arrow Flight SQL server: it speaks the real protocol (gRPC, Flight SQL commands packed in
/// <c>Any</c>, Arrow IPC streams) and authenticates the way the Arrow Java servers do, basic credentials
/// being exchanged for a bearer token returned in the <c>authorization</c> response header.
/// </summary>
public sealed class FakeFlightSqlServer(FlightSqlServerState state) : FlightServer
{
    public override async Task<FlightInfo> GetFlightInfo(FlightDescriptor request, ServerCallContext context)
    {
        await AuthenticateAsync(context);

        var command = Any.Parser.ParseFrom(request.Command);
        if (!command.Is(CommandStatementQuery.Descriptor))
        {
            throw new RpcException(new Status(StatusCode.Unimplemented, $"Unsupported command {command.TypeUrl}"));
        }

        var query = command.Unpack<CommandStatementQuery>().Query;
        state.Statements.Enqueue(query);
        var batch = Respond(query);

        var ticket = new FlightTicket(new TicketStatementQuery { StatementHandle = ByteString.CopyFromUtf8(query) }.ToByteString());
        return new FlightInfo(batch.Schema, request, [new FlightEndpoint(ticket, [])], batch.Length, -1);
    }

    public override async Task DoGet(FlightTicket ticket, FlightServerRecordBatchStreamWriter responseStream, ServerCallContext context)
    {
        await AuthenticateAsync(context);

        var query = TicketStatementQuery.Parser.ParseFrom(ticket.Ticket).StatementHandle.ToStringUtf8();
        await responseStream.WriteAsync(Respond(query));
    }

    private RecordBatch Respond(string query)
    {
        try
        {
            return state.Responder(query);
        }
        catch (Exception e) when (e is not RpcException)
        {
            throw new RpcException(new Status(StatusCode.InvalidArgument, e.Message));
        }
    }

    private async Task AuthenticateAsync(ServerCallContext context)
    {
        var authorization = context.RequestHeaders.GetValue("authorization") ?? string.Empty;
        state.Authorizations.Enqueue(authorization);

        if (authorization == "Bearer " + FlightSqlServerState.BearerToken)
        {
            if (state.ExpireTokenOnce)
            {
                state.ExpireTokenOnce = false;
                throw new RpcException(new Status(StatusCode.Unauthenticated, "token expired"));
            }

            return;
        }

        var expected = "Basic " + Convert.ToBase64String(
            Encoding.UTF8.GetBytes($"{FlightSqlServerState.Username}:{FlightSqlServerState.Password}"));
        if (authorization != expected)
        {
            throw new RpcException(new Status(StatusCode.Unauthenticated, "invalid credentials"));
        }

        await context.WriteResponseHeadersAsync(new Metadata { { "authorization", "Bearer " + FlightSqlServerState.BearerToken } });
    }
}
