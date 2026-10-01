using Microsoft.Extensions.FileProviders;

namespace Rgd.GraphQL.Server.Configuration;

/// <summary>
/// Exposes a Java style <c>application.properties</c> file as an <see cref="IConfiguration"/> source.
/// </summary>
/// <remarks>
/// Keys are mapped onto the .NET configuration model so that they bind to the options classes:
/// <list type="bullet">
/// <item><c>flightsql.tls.trust-server-certificate</c> becomes <c>flightsql:tls:trustservercertificate</c>:
/// dots are section separators and dashes are dropped, the binder being case-insensitive.</item>
/// <item><c>logging.level.&lt;category&gt;</c> becomes <c>Logging:LogLevel:&lt;category&gt;</c>, the
/// category being kept verbatim since it is itself dotted (<c>logging.level.Microsoft.AspNetCore=Warning</c>).</item>
/// </list>
/// </remarks>
public sealed class PropertiesConfigurationProvider(PropertiesConfigurationSource source) : FileConfigurationProvider(source)
{
    private const string LoggingLevelPrefix = "logging.level.";

    public override void Load(Stream stream)
    {
        using var reader = new StreamReader(stream);
        var properties = PropertiesFileParser.Parse(reader);

        var data = new Dictionary<string, string?>(StringComparer.OrdinalIgnoreCase);
        foreach (var (key, value) in properties)
        {
            data[ToConfigurationKey(key)] = value;
        }

        Data = data;
    }

    public static string ToConfigurationKey(string propertyKey)
    {
        ArgumentNullException.ThrowIfNull(propertyKey);

        if (propertyKey.StartsWith(LoggingLevelPrefix, StringComparison.OrdinalIgnoreCase))
        {
            return "Logging:LogLevel:" + propertyKey[LoggingLevelPrefix.Length..];
        }

        var segments = propertyKey.Split('.', StringSplitOptions.RemoveEmptyEntries)
            .Select(segment => segment.Replace("-", string.Empty, StringComparison.Ordinal));
        return string.Join(ConfigurationPath.KeyDelimiter, segments);
    }
}

public sealed class PropertiesConfigurationSource : FileConfigurationSource
{
    public override IConfigurationProvider Build(IConfigurationBuilder builder)
    {
        EnsureDefaults(builder);
        return new PropertiesConfigurationProvider(this);
    }
}

public static class PropertiesConfigurationExtensions
{
    public static IConfigurationBuilder AddPropertiesFile(
        this IConfigurationBuilder builder, string path, bool optional = false, bool reloadOnChange = false)
    {
        ArgumentNullException.ThrowIfNull(builder);
        ArgumentException.ThrowIfNullOrWhiteSpace(path);

        var fullPath = Path.GetFullPath(path);
        return builder.Add(new PropertiesConfigurationSource
        {
            FileProvider = new PhysicalFileProvider(Path.GetDirectoryName(fullPath)!),
            Path = Path.GetFileName(fullPath),
            Optional = optional,
            ReloadOnChange = reloadOnChange,
        });
    }
}
