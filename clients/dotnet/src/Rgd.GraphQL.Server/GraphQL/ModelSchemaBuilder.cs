using HotChocolate.Execution.Configuration;
using HotChocolate.Language;
using HotChocolate.Types;
using Rgd.GraphQL.Server.Data.Sql;
using Rgd.GraphQL.Server.Model;

namespace Rgd.GraphQL.Server.GraphQL;

/// <summary>
/// Generates the GraphQL schema from the data model. For an entity <c>Person</c>:
/// <code>
/// type Query {
///   person(id: Int!): Person
///   persons(where: PersonFilter, orderBy: [PersonOrder!], limit: Int, offset: Int! = 0): [Person!]!
///   personsCount(where: PersonFilter): Long!
/// }
/// </code>
/// </summary>
public static class ModelSchemaBuilder
{
    private const string SortDirectionName = "SortDirection";

    public static IRequestExecutorBuilder AddDataModel(this IRequestExecutorBuilder builder, DataModel model)
    {
        ArgumentNullException.ThrowIfNull(builder);
        ArgumentNullException.ThrowIfNull(model);

        AddScalars(builder, model);
        builder.AddType(new EnumType<SortDirection>(d => d.Name(SortDirectionName)));

        var filterFields = model.Entities.SelectMany(e => e.Fields).Where(f => f.Filterable).ToList();
        foreach (var group in filterFields.GroupBy(FieldTypes.FilterInputName))
        {
            builder.AddType(OperatorInput(group.Key, group.First()));
        }

        foreach (var entity in model.Entities)
        {
            builder.AddType(ObjectType(entity));
            if (entity.Fields.Any(f => f.Filterable))
            {
                builder.AddType(FilterInput(entity));
            }

            if (entity.Fields.Any(f => f.Sortable))
            {
                builder.AddType(OrderInput(entity));
            }
        }

        return builder.AddQueryType(d =>
        {
            d.Name(OperationTypeNames.Query);
            foreach (var entity in model.Entities)
            {
                AddQueryFields(d, entity);
            }
        });
    }

    private static void AddScalars(IRequestExecutorBuilder builder, DataModel model)
    {
        var types = model.Entities.SelectMany(e => e.Fields).Select(f => f.Type).ToHashSet();
        builder.AddType<LongType>(); // the count queries return a Long.
        if (types.Contains(FieldType.Decimal))
        {
            builder.AddType<DecimalType>();
        }

        if (types.Contains(FieldType.Date))
        {
            builder.AddType<DateType>();
        }

        if (types.Contains(FieldType.DateTime))
        {
            builder.AddType<DateTimeType>();
        }
    }

    private static ObjectType ObjectType(EntityModel entity) => new(d =>
    {
        d.Name(entity.Name);
        if (entity.Description is not null)
        {
            d.Description(entity.Description);
        }

        foreach (var field in entity.Fields)
        {
            var name = field.Name;
            var descriptor = d.Field(name)
                .Type(Scalar(field, nonNull: field.IsNonNull))
                .Resolve(ctx => OutputValues.Convert(field, ctx.Parent<EntityRow>()[name]));
            if (field.Description is not null)
            {
                descriptor.Description(field.Description);
            }
        }
    });

    private static InputObjectType OperatorInput(string name, FieldModel sample) => new(d =>
    {
        d.Name(name);
        if (sample.Transform is not null)
        {
            d.Description("Filter on a tokenized column: the values are tokenized by the Flight SQL server before the comparison.");
        }

        foreach (var op in FieldTypes.Operators(sample))
        {
            ITypeNode type = op switch
            {
                FilterOperator.IsNull => new NamedTypeNode("Boolean"),
                FilterOperator.In or FilterOperator.Nin => new ListTypeNode(Scalar(sample, nonNull: true)),
                _ => Scalar(sample, nonNull: false),
            };
            d.Field(FieldTypes.OperatorName(op)).Type(type);
        }
    });

    private static InputObjectType FilterInput(EntityModel entity) => new(d =>
    {
        d.Name(entity.FilterTypeName);
        var self = new NamedTypeNode(entity.FilterTypeName);
        d.Field("and").Type(new ListTypeNode(new NonNullTypeNode(self)));
        d.Field("or").Type(new ListTypeNode(new NonNullTypeNode(self)));
        d.Field("not").Type(self);
        foreach (var field in entity.Fields.Where(f => f.Filterable))
        {
            d.Field(field.Name).Type(new NamedTypeNode(FieldTypes.FilterInputName(field)));
        }
    });

    private static InputObjectType OrderInput(EntityModel entity) => new(d =>
    {
        d.Name(entity.OrderTypeName);
        d.Description("One field per item: the items of the list give the precedence.");
        foreach (var field in entity.Fields.Where(f => f.Sortable))
        {
            d.Field(field.Name).Type(new NamedTypeNode(SortDirectionName));
        }
    });

    private static void AddQueryFields(IObjectTypeDescriptor query, EntityModel entity)
    {
        var entityType = new NamedTypeNode(entity.Name);
        var hasFilter = entity.Fields.Any(f => f.Filterable);

        var keys = entity.KeyFields.ToList();
        if (keys.Count > 0)
        {
            var single = query.Field(entity.SingleQueryName)
                .Description($"The {entity.Name} of the given key, if any.")
                .Type(entityType)
                .Resolve(ctx => EntityResolvers.GetByKeyAsync(ctx, entity));
            foreach (var key in keys)
            {
                single.Argument(key.Name, a => a.Type(Scalar(key, nonNull: true)));
            }
        }

        var list = query.Field(entity.ListQueryName)
            .Description($"Lists the {entity.Name} rows, {entity.DefaultPageSize} by default and {entity.MaxPageSize} at most.")
            .Type(new NonNullTypeNode(new ListTypeNode(new NonNullTypeNode(entityType))))
            .Argument("limit", a => a.Type(new NamedTypeNode("Int")))
            .Argument("offset", a => a.Type(new NonNullTypeNode(new NamedTypeNode("Int"))).DefaultValue(new IntValueNode(0)))
            .Resolve(ctx => EntityResolvers.ListAsync(ctx, entity));
        if (hasFilter)
        {
            list.Argument("where", a => a.Type(new NamedTypeNode(entity.FilterTypeName)));
        }

        if (entity.Fields.Any(f => f.Sortable))
        {
            list.Argument("orderBy", a => a.Type(new ListTypeNode(new NonNullTypeNode(new NamedTypeNode(entity.OrderTypeName)))));
        }

        var count = query.Field(entity.CountQueryName)
            .Description($"Counts the {entity.Name} rows.")
            .Type(new NonNullTypeNode(new NamedTypeNode("Long")))
            .Resolve(ctx => EntityResolvers.CountAsync(ctx, entity));
        if (hasFilter)
        {
            count.Argument("where", a => a.Type(new NamedTypeNode(entity.FilterTypeName)));
        }
    }

    private static ITypeNode Scalar(FieldModel field, bool nonNull)
    {
        var type = new NamedTypeNode(FieldTypes.ScalarName(field.Type));
        return nonNull ? new NonNullTypeNode(type) : type;
    }
}
