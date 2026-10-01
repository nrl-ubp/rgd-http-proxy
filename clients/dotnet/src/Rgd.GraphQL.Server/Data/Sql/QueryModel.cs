using Rgd.GraphQL.Server.Model;

namespace Rgd.GraphQL.Server.Data.Sql;

public enum FilterOperator
{
    Eq,
    Neq,
    Gt,
    Gte,
    Lt,
    Lte,
    In,
    Nin,
    Contains,
    StartsWith,
    EndsWith,
    IsNull,
}

public enum SortDirection
{
    Asc,
    Desc,
}

/// <summary>A database independent filter tree, rendered to SQL by <see cref="SqlQueryBuilder"/>.</summary>
public abstract record FilterNode;

public sealed record AndFilter(IReadOnlyList<FilterNode> Operands) : FilterNode;

public sealed record OrFilter(IReadOnlyList<FilterNode> Operands) : FilterNode;

public sealed record NotFilter(FilterNode Operand) : FilterNode;

/// <summary>A comparison of a field; <see cref="Value"/> is a list for <c>In</c> and <c>Nin</c>, a boolean for <c>IsNull</c>.</summary>
public sealed record FieldCondition(FieldModel Field, FilterOperator Operator, object Value) : FilterNode;

public sealed record SortOrder(FieldModel Field, SortDirection Direction);

public sealed record EntityQuery(
    EntityModel Entity,
    IReadOnlyList<FieldModel> Fields,
    FilterNode? Filter = null,
    IReadOnlyList<SortOrder>? Order = null,
    int? Limit = null,
    int Offset = 0);
