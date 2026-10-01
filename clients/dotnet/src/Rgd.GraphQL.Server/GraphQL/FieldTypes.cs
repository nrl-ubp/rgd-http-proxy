using Rgd.GraphQL.Server.Data.Sql;
using Rgd.GraphQL.Server.Model;

namespace Rgd.GraphQL.Server.GraphQL;

/// <summary>The GraphQL scalar and filter input type of each model field type.</summary>
internal static class FieldTypes
{
    private static readonly FilterOperator[] Equality = [FilterOperator.Eq, FilterOperator.Neq, FilterOperator.IsNull];

    private static readonly FilterOperator[] Membership = [.. Equality, FilterOperator.In, FilterOperator.Nin];

    private static readonly FilterOperator[] Comparison =
        [.. Membership, FilterOperator.Gt, FilterOperator.Gte, FilterOperator.Lt, FilterOperator.Lte];

    private static readonly FilterOperator[] Text =
        [.. Membership, FilterOperator.Contains, FilterOperator.StartsWith, FilterOperator.EndsWith];

    /// <summary>A token can only be matched whole, or by its head through <c>transform_search()</c>.</summary>
    private static readonly FilterOperator[] Token = [.. Membership, FilterOperator.StartsWith];

    public static string ScalarName(FieldType type) => type switch
    {
        FieldType.Id => "ID",
        FieldType.String => "String",
        FieldType.Int => "Int",
        FieldType.Long => "Long",
        FieldType.Float => "Float",
        FieldType.Decimal => "Decimal",
        FieldType.Boolean => "Boolean",
        FieldType.Date => "Date",
        FieldType.DateTime => "DateTime",
        _ => throw new ArgumentOutOfRangeException(nameof(type), type, null),
    };

    public static string FilterInputName(FieldModel field) =>
        (field.Transform is null ? string.Empty : "Token") + field.Type switch
        {
            FieldType.Id => "Id",
            _ => ScalarName(field.Type),
        } + "Filter";

    public static IReadOnlyList<FilterOperator> Operators(FieldModel field)
    {
        if (field.Transform is not null)
        {
            return Token;
        }

        return field.Type switch
        {
            FieldType.Id => Membership,
            FieldType.String => Text,
            FieldType.Boolean => Equality,
            _ => Comparison,
        };
    }

    public static string OperatorName(FilterOperator op) => op switch
    {
        FilterOperator.Eq => "eq",
        FilterOperator.Neq => "neq",
        FilterOperator.Gt => "gt",
        FilterOperator.Gte => "gte",
        FilterOperator.Lt => "lt",
        FilterOperator.Lte => "lte",
        FilterOperator.In => "in",
        FilterOperator.Nin => "nin",
        FilterOperator.Contains => "contains",
        FilterOperator.StartsWith => "startsWith",
        FilterOperator.EndsWith => "endsWith",
        FilterOperator.IsNull => "isNull",
        _ => throw new ArgumentOutOfRangeException(nameof(op), op, null),
    };

    public static FilterOperator? ParseOperator(string name) =>
        Enum.GetValues<FilterOperator>().Select(op => (FilterOperator?)op).FirstOrDefault(op => OperatorName(op!.Value) == name);
}
