using System.Globalization;
using Rgd.GraphQL.Server.Model;

namespace Rgd.GraphQL.Server.GraphQL;

/// <summary>A row of an entity, keyed by GraphQL field name.</summary>
public sealed class EntityRow(EntityModel entity, IReadOnlyDictionary<string, object?> values)
{
    public EntityModel Entity { get; } = entity;

    public object? this[string fieldName] => values.GetValueOrDefault(fieldName);
}

/// <summary>Converts the values read from Arrow into the runtime types of the GraphQL scalars.</summary>
internal static class OutputValues
{
    public static object? Convert(FieldModel field, object? value)
    {
        if (value is null)
        {
            return null;
        }

        var culture = CultureInfo.InvariantCulture;
        return field.Type switch
        {
            FieldType.Id or FieldType.String => value as string ?? System.Convert.ToString(value, culture),
            FieldType.Int => System.Convert.ToInt32(value, culture),
            FieldType.Long => System.Convert.ToInt64(value, culture),
            FieldType.Float => System.Convert.ToDouble(value, culture),
            FieldType.Decimal => System.Convert.ToDecimal(value, culture),
            FieldType.Boolean => System.Convert.ToBoolean(value, culture),
            FieldType.Date => ToDate(value),
            FieldType.DateTime => ToDateTimeOffset(value),
            _ => value,
        };
    }

    private static DateOnly ToDate(object value) => value switch
    {
        DateOnly date => date,
        DateTime dateTime => DateOnly.FromDateTime(dateTime),
        DateTimeOffset dateTimeOffset => DateOnly.FromDateTime(dateTimeOffset.Date),
        string text => DateOnly.Parse(text, CultureInfo.InvariantCulture),
        _ => throw new InvalidCastException($"A {value.GetType().Name} cannot be read as a date."),
    };

    /// <summary>A timestamp without time zone is taken as UTC.</summary>
    private static DateTimeOffset ToDateTimeOffset(object value) => value switch
    {
        DateTimeOffset dateTimeOffset => dateTimeOffset,
        DateTime dateTime => new DateTimeOffset(DateTime.SpecifyKind(dateTime, dateTime.Kind == DateTimeKind.Unspecified ? DateTimeKind.Utc : dateTime.Kind)),
        DateOnly date => new DateTimeOffset(date.ToDateTime(TimeOnly.MinValue, DateTimeKind.Utc)),
        string text => DateTimeOffset.Parse(text, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal),
        _ => throw new InvalidCastException($"A {value.GetType().Name} cannot be read as a timestamp."),
    };
}
