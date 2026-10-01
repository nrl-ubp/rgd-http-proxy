using System.Globalization;
using System.Text;

namespace Rgd.GraphQL.Server.Configuration;

/// <summary>
/// Parses the Java <c>.properties</c> format, the one of <c>java.util.Properties</c>: <c>#</c> and <c>!</c>
/// comments, <c>=</c>, <c>:</c> or whitespace separators, backslash line continuations and the
/// <c>\t \n \r \f \uXXXX</c> escapes. When a key is repeated the last value wins.
/// </summary>
public static class PropertiesFileParser
{
    public static IReadOnlyDictionary<string, string> Parse(TextReader reader)
    {
        ArgumentNullException.ThrowIfNull(reader);

        var result = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var logicalLine in ReadLogicalLines(reader))
        {
            var (key, value) = SplitKeyValue(logicalLine);
            if (key.Length > 0)
            {
                result[key] = value;
            }
        }

        return result;
    }

    public static IReadOnlyDictionary<string, string> Parse(string content)
    {
        using var reader = new StringReader(content);
        return Parse(reader);
    }

    /// <summary>Joins continued lines and drops blank lines and comments.</summary>
    private static IEnumerable<string> ReadLogicalLines(TextReader reader)
    {
        var buffer = new StringBuilder();
        string? line;
        while ((line = reader.ReadLine()) is not null)
        {
            var trimmed = line.TrimStart(' ', '\t', '\f');
            if (buffer.Length == 0 && (trimmed.Length == 0 || trimmed[0] is '#' or '!'))
            {
                continue;
            }

            if (EndsWithContinuation(trimmed))
            {
                buffer.Append(trimmed, 0, trimmed.Length - 1);
                continue;
            }

            buffer.Append(trimmed);
            yield return buffer.ToString();
            buffer.Clear();
        }

        if (buffer.Length > 0)
        {
            yield return buffer.ToString();
        }
    }

    /// <summary>A line is continued when it ends with an odd number of backslashes.</summary>
    private static bool EndsWithContinuation(string line)
    {
        var count = 0;
        for (var i = line.Length - 1; i >= 0 && line[i] == '\\'; i--)
        {
            count++;
        }

        return count % 2 == 1;
    }

    private static (string Key, string Value) SplitKeyValue(string line)
    {
        var index = 0;
        var key = new StringBuilder();
        while (index < line.Length)
        {
            var c = line[index];
            if (c == '\\' && index + 1 < line.Length)
            {
                index = AppendEscape(line, index, key);
                continue;
            }

            if (c is '=' or ':' or ' ' or '\t' or '\f')
            {
                break;
            }

            key.Append(c);
            index++;
        }

        // The separator is any run of whitespace, optionally holding one '=' or ':'.
        while (index < line.Length && line[index] is ' ' or '\t' or '\f')
        {
            index++;
        }

        if (index < line.Length && line[index] is '=' or ':')
        {
            index++;
        }

        while (index < line.Length && line[index] is ' ' or '\t' or '\f')
        {
            index++;
        }

        var value = new StringBuilder();
        while (index < line.Length)
        {
            if (line[index] == '\\' && index + 1 < line.Length)
            {
                index = AppendEscape(line, index, value);
                continue;
            }

            value.Append(line[index]);
            index++;
        }

        return (key.ToString(), value.ToString());
    }

    /// <summary>Appends the character escaped at <paramref name="index"/> and returns the next index.</summary>
    private static int AppendEscape(string line, int index, StringBuilder target)
    {
        var escaped = line[index + 1];
        switch (escaped)
        {
            case 't': target.Append('\t'); return index + 2;
            case 'n': target.Append('\n'); return index + 2;
            case 'r': target.Append('\r'); return index + 2;
            case 'f': target.Append('\f'); return index + 2;
            case 'u' when index + 6 <= line.Length
                          && int.TryParse(line.AsSpan(index + 2, 4), NumberStyles.HexNumber, CultureInfo.InvariantCulture, out var code):
                target.Append((char)code);
                return index + 6;
            default:
                target.Append(escaped);
                return index + 2;
        }
    }
}
