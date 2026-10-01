using System.ComponentModel.DataAnnotations;
using Rgd.GraphQL.Server.Data.Sql;

namespace Rgd.GraphQL.Server.Configuration;

/// <summary>GraphQL endpoint settings, bound from the <c>graphql.*</c> properties.</summary>
public sealed class GraphQLOptions
{
    public const string SectionName = "graphql";

    [Required]
    public string Path { get; set; } = "/graphql";

    /// <summary>The model file, relative to the configuration directory unless absolute.</summary>
    [Required]
    public string ModelFile { get; set; } = "model.json";

    public bool IncludeExceptionDetails { get; set; }

    /// <summary>Serves the Nitro IDE and allows introspection on the endpoint.</summary>
    public bool EnableTooling { get; set; } = true;
}

/// <summary>SQL generation settings, bound from the <c>sql.*</c> properties.</summary>
public sealed class SqlOptions
{
    public const string SectionName = "sql";

    public SqlDialectKind Dialect { get; set; } = SqlDialectKind.Ansi;

    /// <summary>Quotes table and column names, which makes them case-sensitive on most databases.</summary>
    public bool QuoteIdentifiers { get; set; } = true;

    [Range(1, 10_000)]
    public int MaxInListSize { get; set; } = 1000;
}
