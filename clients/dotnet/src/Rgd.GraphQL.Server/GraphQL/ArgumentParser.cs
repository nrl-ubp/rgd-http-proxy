using Rgd.GraphQL.Server.Data.Sql;
using Rgd.GraphQL.Server.Model;

namespace Rgd.GraphQL.Server.GraphQL;

/// <summary>Turns the <c>where</c> and <c>orderBy</c> argument values into the query model.</summary>
internal static class ArgumentParser
{
    public static FilterNode? ParseFilter(EntityModel entity, object? value)
    {
        if (value is null)
        {
            return null;
        }

        var operands = new List<FilterNode>();
        foreach (var (name, item) in AsObject(value))
        {
            if (item is null)
            {
                continue;
            }

            switch (name)
            {
                case "and":
                    operands.Add(new AndFilter(ParseFilterList(entity, item)));
                    break;
                case "or":
                    operands.Add(new OrFilter(ParseFilterList(entity, item)));
                    break;
                case "not":
                    if (ParseFilter(entity, item) is { } negated)
                    {
                        operands.Add(new NotFilter(negated));
                    }

                    break;
                default:
                    operands.AddRange(ParseConditions(entity.GetField(name), item));
                    break;
            }
        }

        return operands.Count switch
        {
            0 => null,
            1 => operands[0],
            _ => new AndFilter(operands),
        };
    }

    public static IReadOnlyList<SortOrder> ParseOrder(EntityModel entity, object? value)
    {
        if (value is null)
        {
            return [];
        }

        var order = new List<SortOrder>();
        foreach (var item in AsList(value))
        {
            foreach (var (name, direction) in AsObject(item!))
            {
                if (direction is not null)
                {
                    order.Add(new SortOrder(entity.GetField(name), ToDirection(direction)));
                }
            }
        }

        return order;
    }

    private static List<FilterNode> ParseFilterList(EntityModel entity, object value) =>
        AsList(value).Select(item => ParseFilter(entity, item)).OfType<FilterNode>().ToList();

    /// <summary>A null operator value is ignored, <c>isNull</c> being the way to look for nulls.</summary>
    private static IEnumerable<FilterNode> ParseConditions(FieldModel field, object value)
    {
        foreach (var (name, operand) in AsObject(value))
        {
            if (operand is null)
            {
                continue;
            }

            var op = FieldTypes.ParseOperator(name)
                     ?? throw new SqlGenerationException($"Unknown operator '{name}' on the field '{field.Name}'.");
            yield return new FieldCondition(field, op, op is FilterOperator.In or FilterOperator.Nin ? AsList(operand) : operand);
        }
    }

    private static SortDirection ToDirection(object value) => value switch
    {
        SortDirection direction => direction,
        string text when Enum.TryParse<SortDirection>(text, ignoreCase: true, out var parsed) => parsed,
        _ => throw new SqlGenerationException($"'{value}' is not a sort direction."),
    };

    private static IEnumerable<KeyValuePair<string, object?>> AsObject(object value) => value switch
    {
        IEnumerable<KeyValuePair<string, object?>> fields => fields,
        _ => throw new SqlGenerationException($"An input object is expected, got {value.GetType().Name}."),
    };

    private static List<object?> AsList(object value) => value switch
    {
        string or IEnumerable<KeyValuePair<string, object?>> => [value],
        System.Collections.IEnumerable items => items.Cast<object?>().ToList(),
        _ => [value],
    };
}
