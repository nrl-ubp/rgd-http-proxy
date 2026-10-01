using System.Text;
using Grpc.Core;
using Grpc.Core.Interceptors;

namespace Rgd.GraphQL.Server.Data.FlightSql;

/// <summary>
/// Authenticates the Flight SQL calls with the configured credentials.
/// </summary>
/// <remarks>
/// Every call carries <c>Basic</c> credentials until the server hands a bearer token back in the
/// <c>authorization</c> response header, as the Arrow <c>GeneratedBearerTokenAuthenticator</c> of the
/// rgd-http-proxy does. The token is then used instead, sparing the server a credential validation per
/// call, until <see cref="ResetBearerToken"/> drops it.
/// <para>
/// This is a plain gRPC interceptor rather than an Arrow <c>IFlightClientMiddleware</c>: the Arrow
/// adapter of the middlewares replaces the <see cref="RpcException"/> of a failed call by an
/// <see cref="InvalidOperationException"/>, losing its status code.
/// </para>
/// </remarks>
public sealed class FlightSqlAuthInterceptor : Interceptor
{
    private const string AuthorizationHeader = "authorization";
    private const string BearerPrefix = "Bearer ";

    private readonly string _basicAuthorization;
    private volatile string? _bearerToken;

    public FlightSqlAuthInterceptor(string username, string password)
    {
        ArgumentNullException.ThrowIfNull(username);
        ArgumentNullException.ThrowIfNull(password);
        _basicAuthorization = "Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes($"{username}:{password}"));
    }

    public bool HasBearerToken => _bearerToken is not null;

    public void ResetBearerToken() => _bearerToken = null;

    public override AsyncUnaryCall<TResponse> AsyncUnaryCall<TRequest, TResponse>(
        TRequest request,
        ClientInterceptorContext<TRequest, TResponse> context,
        AsyncUnaryCallContinuation<TRequest, TResponse> continuation)
    {
        var call = continuation(request, Authenticated(context));
        return new AsyncUnaryCall<TResponse>(
            ReadResponseAsync(call.ResponseHeadersAsync, call.ResponseAsync),
            call.ResponseHeadersAsync,
            call.GetStatus,
            call.GetTrailers,
            call.Dispose);
    }

    public override AsyncServerStreamingCall<TResponse> AsyncServerStreamingCall<TRequest, TResponse>(
        TRequest request,
        ClientInterceptorContext<TRequest, TResponse> context,
        AsyncServerStreamingCallContinuation<TRequest, TResponse> continuation)
    {
        var call = continuation(request, Authenticated(context));
        CaptureToken(call.ResponseHeadersAsync);
        return call;
    }

    public override AsyncClientStreamingCall<TRequest, TResponse> AsyncClientStreamingCall<TRequest, TResponse>(
        ClientInterceptorContext<TRequest, TResponse> context,
        AsyncClientStreamingCallContinuation<TRequest, TResponse> continuation)
    {
        var call = continuation(Authenticated(context));
        CaptureToken(call.ResponseHeadersAsync);
        return call;
    }

    public override AsyncDuplexStreamingCall<TRequest, TResponse> AsyncDuplexStreamingCall<TRequest, TResponse>(
        ClientInterceptorContext<TRequest, TResponse> context,
        AsyncDuplexStreamingCallContinuation<TRequest, TResponse> continuation)
    {
        var call = continuation(Authenticated(context));
        CaptureToken(call.ResponseHeadersAsync);
        return call;
    }

    private ClientInterceptorContext<TRequest, TResponse> Authenticated<TRequest, TResponse>(
        ClientInterceptorContext<TRequest, TResponse> context)
        where TRequest : class
        where TResponse : class
    {
        var headers = new Metadata();
        foreach (var entry in context.Options.Headers ?? [])
        {
            if (!string.Equals(entry.Key, AuthorizationHeader, StringComparison.OrdinalIgnoreCase))
            {
                headers.Add(entry);
            }
        }

        var token = _bearerToken;
        headers.Add(AuthorizationHeader, token is null ? _basicAuthorization : BearerPrefix + token);
        return new ClientInterceptorContext<TRequest, TResponse>(context.Method, context.Host, context.Options.WithHeaders(headers));
    }

    private async Task<TResponse> ReadResponseAsync<TResponse>(Task<Metadata> headers, Task<TResponse> response)
    {
        StoreToken(await headers);
        return await response;
    }

    private void CaptureToken(Task<Metadata> headers) =>
        headers.ContinueWith(
            task => StoreToken(task.Result),
            CancellationToken.None,
            TaskContinuationOptions.OnlyOnRanToCompletion | TaskContinuationOptions.ExecuteSynchronously,
            TaskScheduler.Default);

    private void StoreToken(Metadata headers)
    {
        var authorization = headers.GetValue(AuthorizationHeader);
        if (authorization is not null && authorization.StartsWith(BearerPrefix, StringComparison.OrdinalIgnoreCase))
        {
            _bearerToken = authorization[BearerPrefix.Length..].Trim();
        }
    }
}
