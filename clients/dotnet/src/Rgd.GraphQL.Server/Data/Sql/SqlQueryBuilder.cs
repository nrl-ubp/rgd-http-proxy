using System.Collections;
using System.Globalization;
using System.Text;
using Microsoft.Extensions.Options;
using Rgd.GraphQL.Server.Configuration;
using Rgd.GraphQL.Server.Model;

namespace Rgd.GraphQL.Server.Data.Sql;

/// <summary>
/// Renders <see cref="EntityQuery"/> instances as SQL statements.
/// </summary>
/// <remarks>
/// Values are rendered as escaped literals rather than bound parameters: the <c>transform()</c> and
/// <c>transform_search()</c> extensions of the rgd-http-proxy are only understood with literal arguments.
/// Identifiers all come from the validated model, never from the request.
/// </remarks>
public sealed class SqlQueryBuilder
{
    private readonly SqlDialect _dialect;
    private readonly int _maxInListSize;

    public SqlQueryBuilder(IOptions<SqlOptions> options)
    {
        ArgumentNullException.ThrowIfNull(options);
        _dialect = new SqlDialect(options.Value.Dialect, options.Value.QuoteIdentifiers);
        _maxInListSize = options.Value.MaxInListSize;
    }

    public string BuildSelect(EntityQuery query)
    {
        ArgumentNullException.ThrowIfNull(query);
        if (query.Fields.Count == 0)
        {
            throw new SqlGenerationException("At least one field must be selected.");
        }

        var sql = new StringBuilder("SELECT ");
        sql.AppendJoin(", ", query.Fields.Select(f => _dialect.QuoteIdentifier(f.Column)));
        sql.Append(" FROM ").Append(Table(query.Entity));
        AppendWhere(sql, query.Filter);

        var order = EffectiveOrder(query);
        if (order.Count > 0)
        {
            sql.Append(" ORDER BY ");
            sql.AppendJoin(", ", order.Select(o => _dialect.QuoteIdentifier(o.Field.Column) + (o.Direction == SortDirection.Desc ? " DESC" : " ASC")));
        }
        else if (query.Limit is not null || query.Offset > 0)
        {
            // OFFSET / FETCH requires an ORDER BY on SQL Server.
            sql.Append(" ORDER BY 1");
        }

        if (query.Limit is not null || query.Offset > 0)
        {
            sql.Append(CultureInfo.InvariantCulture, $" OFFSET {query.Offset} ROWS");
            if (query.Limit is { } limit)
            {
                sql.Append(CultureInfo.InvariantCulture, $" FETCH NEXT {limit} ROWS ONLY");
            }
        }

        return sql.ToString();
    }

    public string BuildCount(EntityModel entity, FilterNode? filter)
    {
        ArgumentNullException.ThrowIfNull(entity);

        var sql = new StringBuilder("SELECT COUNT(*) FROM ").Append(Table(entity));
        AppendWhere(sql, filter);
        return sql.ToString();
    }

    /// <summary>Appends the key fields to the requested order so that pages are stable.</summary>
    private static List<SortOrder> EffectiveOrder(EntityQuery query)
    {
        var order = new List<SortOrder>(query.Order ?? []);
        foreach (var key in query.Entity.KeyFields.Where(key => order.TrueForAll(o => o.Field.Column != key.Column)))
        {
            order.Add(new SortOrder(key, SortDirection.Asc));
        }

        return order;
    }

    private string Table(EntityModel entity) => entity.Schema is null
        ? _dialect.QuoteIdentifier(entity.Table)
        : _dialect.QuoteIdentifier(entity.Schema) + "." + _dialect.QuoteIdentifier(entity.Table);

    private void AppendWhere(StringBuilder sql, FilterNode? filter)
    {
        if (filter is null)
        {
            return;
        }

        var condition = Render(filter);
        if (condition.Length > 0)
        {
            sql.Append(" WHERE ").Append(condition);
        }
    }

    private string Render(FilterNode node) => node switch
    {
        AndFilter and => Combine(and.Operands, " AND "),
        OrFilter or => Combine(or.Operands, " OR "),
        NotFilter not => Render(not.Operand) is { Length: > 0 } operand ? $"NOT ({operand})" : string.Empty,
        FieldCondition condition => Render(condition),
        _ => throw new SqlGenerationException($"Unsupported filter node {node.GetType().Name}."),
    };

    private string Combine(IReadOnlyList<FilterNode> operands, string separator)
    {
        var rendered = operands.Select(Render).Where(s => s.Length > 0).ToList();
        return rendered.Count switch
        {
            0 => string.Empty,
            1 => rendered[0],
            _ => "(" + string.Join(separator, rendered) + ")",
        };
    }

    private string Render(FieldCondition condition)
    {
        var field = condition.Field;
        var column = _dialect.QuoteIdentifier(field.Column);
        var tokenized = field.Transform is not null;

        return condition.Operator switch
        {
            FilterOperator.IsNull => (bool)condition.Value ? $"{column} IS NULL" : $"{column} IS NOT NULL",
            FilterOperator.Eq => $"{column} = {Value(field, condition.Value)}",
            FilterOperator.Neq => $"{column} <> {Value(field, condition.Value)}",
            FilterOperator.Gt => $"{column} > {Comparable(field, condition)}",
            FilterOperator.Gte => $"{column} >= {Comparable(field, condition)}",
            FilterOperator.Lt => $"{column} < {Comparable(field, condition)}",
            FilterOperator.Lte => $"{column} <= {Comparable(field, condition)}",
            FilterOperator.In => $"{column} IN ({ValueList(field, condition.Value)})",
            FilterOperator.Nin => $"{column} NOT IN ({ValueList(field, condition.Value)})",
            FilterOperator.StartsWith when tokenized => $"{column} LIKE {TransformCall("transform_search", field, condition.Value)}",
            FilterOperator.StartsWith => Like(column, field, condition.Value, "", "%"),
            FilterOperator.Contains when !tokenized => Like(column, field, condition.Value, "%", "%"),
            FilterOperator.EndsWith when !tokenized => Like(column, field, condition.Value, "%", ""),
            _ => throw new SqlGenerationException($"The operator {condition.Operator} is not supported on the field '{field.Name}'."),
        };
    }

    private string Comparable(FieldModel field, FieldCondition condition) =>
        field.Transform is null
            ? Value(field, condition.Value)
            : throw new SqlGenerationException($"The operator {condition.Operator} is not supported on the tokenized field '{field.Name}'.");

    private string Like(string column, FieldModel field, object value, string prefix, string suffix) =>
        $"{column} LIKE {_dialect.StringLiteral(prefix + _dialect.EscapeLike(ToText(field, value)) + suffix)} ESCAPE '\\'";

    private string ValueList(FieldModel field, object value)
    {
        if (value is not IEnumerable values || value is string)
        {
            throw new SqlGenerationException($"A list of values is expected for the field '{field.Name}'.");
        }

        var rendered = values.Cast<object?>().Where(v => v is not null).Select(v => Value(field, v!)).ToList();
        if (rendered.Count > _maxInListSize)
        {
            throw new SqlGenerationException($"At most {_maxInListSize} values can be listed for the field '{field.Name}'.");
        }

        // An empty IN list is invalid SQL, and matches nothing.
        return rendered.Count == 0 ? "NULL" : string.Join(", ", rendered);
    }

    /// <summary>Renders a value as a literal of the type of the field, or as a <c>transform()</c> call when it is tokenized.</summary>
    private string Value(FieldModel field, object value)
    {
        if (field.Transform is not null)
        {
            return TransformCall("transform", field, value);
        }

        try
        {
            return field.Type switch
            {
                FieldType.Id or FieldType.String => _dialect.StringLiteral(ToText(field, value)),
                FieldType.Int or FieldType.Long => Convert.ToInt64(value, CultureInfo.InvariantCulture).ToString(CultureInfo.InvariantCulture),
                FieldType.Float => Float(Convert.ToDouble(value, CultureInfo.InvariantCulture)),
                FieldType.Decimal => Convert.ToDecimal(value, CultureInfo.InvariantCulture).ToString(CultureInfo.InvariantCulture),
                FieldType.Boolean => _dialect.BooleanLiteral(Convert.ToBoolean(value, CultureInfo.InvariantCulture)),
                FieldType.Date => _dialect.DateLiteral(ToDate(value)),
                FieldType.DateTime => _dialect.TimestampLiteral(ToUtcDateTime(value)),
                _ => throw new SqlGenerationException($"Unsupported field type {field.Type}."),
            };
        }
        catch (Exception e) when (e is FormatException or InvalidCastException or OverflowException)
        {
            throw new SqlGenerationException($"The value '{value}' is not valid for the {field.Type} field '{field.Name}'.", e);
        }
    }

    private string TransformCall(string function, FieldModel field, object value)
    {
        var transform = field.Transform!;
        return $"{function}({_dialect.StringLiteral(transform.ClassName)}, {_dialect.StringLiteral(transform.PropertyName)}, "
               + $"{_dialect.StringLiteral(transform.Jurisdiction)}, {_dialect.StringLiteral(ToText(field, value))})";
    }

    private static string ToText(FieldModel field, object value) =>
        value as string ?? Convert.ToString(value, CultureInfo.InvariantCulture)
        ?? throw new SqlGenerationException($"The field '{field.Name}' expects a text value.");

    private static string Float(double value) => double.IsFinite(value)
        ? value.ToString("R", CultureInfo.InvariantCulture)
        : throw new SqlGenerationException("NaN and infinite values are not supported.");

    private static DateOnly ToDate(object value) => value switch
    {
        DateOnly date => date,
        DateTime dateTime => DateOnly.FromDateTime(dateTime),
        DateTimeOffset dateTimeOffset => DateOnly.FromDateTime(dateTimeOffset.Date),
        string text => DateOnly.Parse(text, CultureInfo.InvariantCulture),
        _ => throw new InvalidCastException(),
    };

    /// <summary>Timestamps are compared in UTC, the way they are read back.</summary>
    private static DateTime ToUtcDateTime(object value) => value switch
    {
        DateTimeOffset dateTimeOffset => dateTimeOffset.UtcDateTime,
        DateTime dateTime => dateTime.Kind == DateTimeKind.Local ? dateTime.ToUniversalTime() : dateTime,
        string text => DateTimeOffset.Parse(text, CultureInfo.InvariantCulture).UtcDateTime,
        _ => throw new InvalidCastException(),
    };
}
