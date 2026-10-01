using System.Text.Json.Serialization;

namespace Rgd.GraphQL.Server.Model;

/// <summary>
/// The data model the GraphQL schema is generated from, read from the model file of the configuration directory.
/// </summary>
public sealed class DataModel
{
    public IReadOnlyList<EntityModel> Entities { get; init; } = [];
}

/// <summary>A table exposed as a GraphQL object type.</summary>
public sealed class EntityModel
{
    /// <summary>GraphQL type name, e.g. <c>Person</c>.</summary>
    public required string Name { get; init; }

    public string? Description { get; init; }

    /// <summary>Optional database schema the table belongs to, e.g. <c>dbo</c>.</summary>
    public string? Schema { get; init; }

    public required string Table { get; init; }

    /// <summary>Name of the list query field. Defaults to the camel-cased plural of <see cref="Name"/>.</summary>
    public string? PluralName { get; init; }

    public int DefaultPageSize { get; init; } = 100;

    public int MaxPageSize { get; init; } = 1000;

    public IReadOnlyList<FieldModel> Fields { get; init; } = [];

    [JsonIgnore]
    public IEnumerable<FieldModel> KeyFields => Fields.Where(f => f.Key);

    [JsonIgnore]
    public string SingleQueryName => NameConventions.ToCamelCase(Name);

    [JsonIgnore]
    public string ListQueryName => NameConventions.ToCamelCase(PluralName ?? NameConventions.Pluralize(Name));

    [JsonIgnore]
    public string CountQueryName => ListQueryName + "Count";

    [JsonIgnore]
    public string FilterTypeName => Name + "Filter";

    [JsonIgnore]
    public string OrderTypeName => Name + "Order";

    public FieldModel GetField(string name) =>
        Fields.FirstOrDefault(f => f.Name == name)
        ?? throw new KeyNotFoundException($"Entity '{Name}' has no field '{name}'.");
}

/// <summary>A column exposed as a GraphQL field.</summary>
public sealed class FieldModel
{
    /// <summary>GraphQL field name, e.g. <c>firstName</c>.</summary>
    public required string Name { get; init; }

    public required string Column { get; init; }

    public required FieldType Type { get; init; }

    public string? Description { get; init; }

    /// <summary>Part of the primary key: key fields become the arguments of the single-entity query.</summary>
    public bool Key { get; init; }

    public bool Nullable { get; init; } = true;

    public bool Filterable { get; init; } = true;

    public bool Sortable { get; init; } = true;

    /// <summary>
    /// Set when the column stores RegData tokens: filter values are then wrapped in the <c>transform()</c>
    /// and <c>transform_search()</c> SQL extensions of the rgd-http-proxy, so that clear values can be
    /// compared with the tokens.
    /// </summary>
    public TransformModel? Transform { get; init; }

    [JsonIgnore]
    public bool IsNonNull => Key || !Nullable;
}

/// <summary>The RPS mapping of a tokenized column, i.e. the first three arguments of <c>transform()</c>.</summary>
public sealed class TransformModel
{
    public required string ClassName { get; init; }

    public required string PropertyName { get; init; }

    public required string Jurisdiction { get; init; }
}

/// <summary>The type of a field, named after the GraphQL scalar it is exposed as.</summary>
[JsonConverter(typeof(JsonStringEnumConverter<FieldType>))]
[System.Diagnostics.CodeAnalysis.SuppressMessage("Naming", "CA1720:Identifier contains type name", Justification = "Named after the GraphQL scalars.")]
public enum FieldType
{
    Id,
    String,
    Int,
    Long,
    Float,
    Decimal,
    Boolean,
    Date,
    DateTime,
}
