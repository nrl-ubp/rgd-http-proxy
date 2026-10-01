using HotChocolate;
using HotChocolate.Resolvers;
using HotChocolate.Types;
using Rgd.GraphQL.Server.Data;
using Rgd.GraphQL.Server.Data.Sql;
using Rgd.GraphQL.Server.Model;

namespace Rgd.GraphQL.Server.GraphQL;

/// <summary>The resolvers of the generated query fields: each one runs a single SQL statement.</summary>
internal static class EntityResolvers
{
    public static async ValueTask<object?> ListAsync(IResolverContext context, EntityModel entity)
    {
        var limit = context.ArgumentValue<int?>("limit") ?? entity.DefaultPageSize;
        var offset = context.ArgumentValue<int>("offset");
        if (limit < 1 || limit > entity.MaxPageSize)
        {
            throw new GraphQLException(Error($"The limit must be between 1 and {entity.MaxPageSize}.", "INVALID_LIMIT"));
        }

        if (offset < 0)
        {
            throw new GraphQLException(Error("The offset cannot be negative.", "INVALID_OFFSET"));
        }

        var query = new EntityQuery(
            entity,
            SelectedFields(context, entity),
            ArgumentParser.ParseFilter(entity, OptionalArgument(context, "where")),
            ArgumentParser.ParseOrder(entity, OptionalArgument(context, "orderBy")),
            limit,
            offset);

        return await QueryAsync(context, query);
    }

    public static async ValueTask<object?> GetByKeyAsync(IResolverContext context, EntityModel entity)
    {
        var conditions = entity.KeyFields
            .Select(key => (FilterNode)new FieldCondition(key, FilterOperator.Eq, context.ArgumentValue<object>(key.Name)))
            .ToList();
        var query = new EntityQuery(entity, SelectedFields(context, entity), new AndFilter(conditions), Limit: 2);

        var rows = await QueryAsync(context, query);
        return rows.Count switch
        {
            0 => null,
            1 => rows[0],
            _ => throw new GraphQLException(Error($"The key of {entity.Name} is not unique.", "NON_UNIQUE_KEY")),
        };
    }

    public static async ValueTask<object?> CountAsync(IResolverContext context, EntityModel entity)
    {
        var builder = context.Service<SqlQueryBuilder>();
        var sql = builder.BuildCount(entity, ArgumentParser.ParseFilter(entity, OptionalArgument(context, "where")));

        var rows = await context.Service<IQueryExecutor>().QueryAsync(sql, context.RequestAborted);
        return rows.Count == 1 && rows[0].Length == 1
            ? Convert.ToInt64(rows[0][0], System.Globalization.CultureInfo.InvariantCulture)
            : throw new DataAccessException("The count query returned an unexpected result.");
    }

    private static async Task<List<EntityRow>> QueryAsync(IResolverContext context, EntityQuery query)
    {
        var sql = context.Service<SqlQueryBuilder>().BuildSelect(query);
        var rows = await context.Service<IQueryExecutor>().QueryAsync(sql, context.RequestAborted);

        // The values are read by position: column names may come back in another case, or be aliased.
        return rows.Select(row =>
        {
            var values = new Dictionary<string, object?>(query.Fields.Count, StringComparer.Ordinal);
            for (var i = 0; i < query.Fields.Count && i < row.Length; i++)
            {
                values[query.Fields[i].Name] = row[i];
            }

            return new EntityRow(query.Entity, values);
        }).ToList();
    }

    /// <summary>Only the columns of the fields selected by the request are read, sparing the detokenization of the others.</summary>
    private static List<FieldModel> SelectedFields(IResolverContext context, EntityModel entity)
    {
        var fields = new List<FieldModel>();
        if (context.Selection.Type.NamedType() is ObjectType objectType)
        {
            foreach (var selection in context.GetSelections(objectType, context.Selection, allowInternals: false))
            {
                var field = entity.Fields.FirstOrDefault(f => f.Name == selection.Field.Name);
                if (field is not null && !fields.Contains(field))
                {
                    fields.Add(field);
                }
            }
        }

        // A selection made of __typename only still needs a column to count the rows.
        if (fields.Count == 0)
        {
            fields.Add(entity.KeyFields.FirstOrDefault() ?? entity.Fields[0]);
        }

        return fields;
    }

    private static object? OptionalArgument(IResolverContext context, string name) =>
        context.Selection.Field.Arguments.ContainsField(name) ? context.ArgumentValue<object?>(name) : null;

    private static IError Error(string message, string code) =>
        ErrorBuilder.New().SetMessage(message).SetCode(code).Build();
}
