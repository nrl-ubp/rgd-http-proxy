using Rgd.GraphQL.Server;
using Rgd.GraphQL.Server.Model;

try
{
    await ServerApplication.Build(args).RunAsync();
    return 0;
}
catch (Exception e) when (e is ModelException or FileNotFoundException or DirectoryNotFoundException)
{
    // A configuration problem: its message says it all, a stack trace would only hide it.
    await Console.Error.WriteLineAsync($"Startup failed: {e.Message}");
    return 1;
}
