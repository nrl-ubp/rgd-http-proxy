using System.Globalization;

namespace Rgd.GraphQL.Server.Data.Sql;

public enum SqlDialectKind
{
    /// <summary>ANSI SQL, e.g. H2 or PostgreSQL behind the Flight SQL server.</summary>
    Ansi,

    /// <summary>Microsoft SQL Server.</summary>
    SqlServer,
}

/// <summary>The few places where the generated SQL depends on the database behind the Flight SQL server.</summary>
public sealed class SqlDialect(SqlDialectKind kind, bool quoteIdentifiers)
{
    public SqlDialectKind Kind { get; } = kind;

    public string QuoteIdentifier(string name)
    {
        if (!quoteIdentifiers)
        {
            return name;
        }

        return Kind == SqlDialectKind.SqlServer
            ? "[" + name.Replace("]", "]]", StringComparison.Ordinal) + "]"
            : "\"" + name.Replace("\"", "\"\"", StringComparison.Ordinal) + "\"";
    }

    public string StringLiteral(string value)
    {
        if (value.Contains('\0', StringComparison.Ordinal))
        {
            throw new SqlGenerationException("A string value cannot contain a NUL character.");
        }

        return "'" + value.Replace("'", "''", StringComparison.Ordinal) + "'";
    }

    public string BooleanLiteral(bool value) => Kind == SqlDialectKind.SqlServer
        ? (value ? "1" : "0")
        : (value ? "TRUE" : "FALSE");

    public string DateLiteral(DateOnly value)
    {
        var text = value.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        return Kind == SqlDialectKind.SqlServer ? $"'{text}'" : $"DATE '{text}'";
    }

    public string TimestampLiteral(DateTime value)
    {
        return Kind == SqlDialectKind.SqlServer
            ? $"'{value.ToString("yyyy-MM-ddTHH:mm:ss.fffffff", CultureInfo.InvariantCulture)}'"
            : $"TIMESTAMP '{value.ToString("yyyy-MM-dd HH:mm:ss.ffffff", CultureInfo.InvariantCulture)}'";
    }

    /// <summary>Escapes the LIKE wildcards of a value, to be used with <c>ESCAPE '\'</c>.</summary>
    public string EscapeLike(string value)
    {
        var escaped = value
            .Replace("\\", "\\\\", StringComparison.Ordinal)
            .Replace("%", "\\%", StringComparison.Ordinal)
            .Replace("_", "\\_", StringComparison.Ordinal);

        // '[' opens a character class in a SQL Server LIKE pattern.
        return Kind == SqlDialectKind.SqlServer ? escaped.Replace("[", "\\[", StringComparison.Ordinal) : escaped;
    }
}

public sealed class SqlGenerationException : Exception
{
    public SqlGenerationException()
    {
    }

    public SqlGenerationException(string message)
        : base(message)
    {
    }

    public SqlGenerationException(string message, Exception innerException)
        : base(message, innerException)
    {
    }
}
