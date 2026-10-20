namespace Rgd.GraphQL.Server.Model;

internal static class NameConventions
{
    public static string ToCamelCase(string name) =>
        string.IsNullOrEmpty(name) || char.IsLower(name[0]) ? name : char.ToLowerInvariant(name[0]) + name[1..];

    /// <summary>English pluralization good enough for type names; use <c>pluralName</c> in the model otherwise.</summary>
    public static string Pluralize(string name)
    {
        if (name.EndsWith('y') && name.Length > 1 && !IsVowel(name[^2]))
        {
            return name[..^1] + "ies";
        }

        if (name.EndsWith('s') || name.EndsWith('x') || name.EndsWith('z')
            || name.EndsWith("ch", StringComparison.Ordinal) || name.EndsWith("sh", StringComparison.Ordinal))
        {
            return name + "es";
        }

        return name + "s";
    }

    private static bool IsVowel(char c) => "aeiouAEIOU".Contains(c, StringComparison.Ordinal);
}
