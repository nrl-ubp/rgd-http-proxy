namespace Rgd.GraphQL.Server.Configuration;

/// <summary>
/// Locates the configuration directory holding <c>application.properties</c> and the model file.
/// </summary>
/// <remarks>
/// In order of precedence:
/// <list type="number">
/// <item>the <c>--config-dir</c> command line switch,</item>
/// <item>the <c>RGD_CONFIG_DIR</c> environment variable,</item>
/// <item>a <c>config</c> directory in the current working directory,</item>
/// <item>the <c>config</c> directory deployed next to the binaries.</item>
/// </list>
/// </remarks>
public static class ConfigDirectory
{
    public const string CommandLineSwitch = "--config-dir";
    public const string EnvironmentVariable = "RGD_CONFIG_DIR";
    public const string DefaultDirectoryName = "config";
    public const string PropertiesFileName = "application.properties";

    public static string Resolve(IReadOnlyList<string> args)
    {
        ArgumentNullException.ThrowIfNull(args);

        var explicitDirectory = FromCommandLine(args) ?? Environment.GetEnvironmentVariable(EnvironmentVariable);
        if (!string.IsNullOrWhiteSpace(explicitDirectory))
        {
            var fullPath = Path.GetFullPath(explicitDirectory);
            if (!Directory.Exists(fullPath))
            {
                throw new DirectoryNotFoundException($"The configuration directory '{fullPath}' does not exist.");
            }

            return fullPath;
        }

        var candidates = new[]
        {
            Path.Combine(Directory.GetCurrentDirectory(), DefaultDirectoryName),
            Path.Combine(AppContext.BaseDirectory, DefaultDirectoryName),
        };

        return candidates.FirstOrDefault(Directory.Exists)
            ?? throw new DirectoryNotFoundException(
                $"No configuration directory found, looked into: {string.Join(", ", candidates)}. "
                + $"Use {CommandLineSwitch} or the {EnvironmentVariable} environment variable.");
    }

    private static string? FromCommandLine(IReadOnlyList<string> args)
    {
        for (var i = 0; i < args.Count; i++)
        {
            if (args[i].StartsWith(CommandLineSwitch + "=", StringComparison.Ordinal))
            {
                return args[i][(CommandLineSwitch.Length + 1)..];
            }

            if (args[i] == CommandLineSwitch && i + 1 < args.Count)
            {
                return args[i + 1];
            }
        }

        return null;
    }
}
