using Microsoft.Extensions.Options;
using Rgd.GraphQL.Server.Configuration;
using Rgd.GraphQL.Server.Data.Sql;
using Rgd.GraphQL.Server.Model;

namespace Rgd.GraphQL.Server.Tests.Sql;

public class SqlQueryBuilderTests
{
    private static readonly EntityModel Person = new()
    {
        Name = "Person",
        Schema = "dbo",
        Table = "PERSON",
        Fields =
        [
            new FieldModel { Name = "id", Column = "ID", Type = FieldType.Int, Key = true },
            new FieldModel
            {
                Name = "firstName", Column = "FIRST_NAME", Type = FieldType.String,
                Transform = new TransformModel { ClassName = "Person", PropertyName = "shortString", Jurisdiction = "CH" },
            },
            new FieldModel { Name = "city", Column = "CITY", Type = FieldType.String },
            new FieldModel { Name = "active", Column = "ACTIVE", Type = FieldType.Boolean },
            new FieldModel { Name = "birthDate", Column = "BIRTH_DATE", Type = FieldType.Date },
            new FieldModel { Name = "balance", Column = "BALANCE", Type = FieldType.Decimal },
        ],
    };

    private static FieldModel Field(string name) => Person.GetField(name);

    private static SqlQueryBuilder Builder(SqlDialectKind dialect = SqlDialectKind.SqlServer, bool quote = true) =>
        new(Options.Create(new SqlOptions { Dialect = dialect, QuoteIdentifiers = quote, MaxInListSize = 3 }));

    [Fact]
    public void Selects_the_requested_columns_ordered_by_key_with_paging()
    {
        var sql = Builder().BuildSelect(new EntityQuery(Person, [Field("id"), Field("city")], Limit: 10, Offset: 20));

        Assert.Equal(
            "SELECT [ID], [CITY] FROM [dbo].[PERSON] ORDER BY [ID] ASC OFFSET 20 ROWS FETCH NEXT 10 ROWS ONLY",
            sql);
    }

    [Fact]
    public void Quotes_identifiers_the_ansi_way()
    {
        var sql = Builder(SqlDialectKind.Ansi).BuildSelect(new EntityQuery(Person, [Field("city")]));

        Assert.Equal("SELECT \"CITY\" FROM \"dbo\".\"PERSON\" ORDER BY \"ID\" ASC", sql);
    }

    [Fact]
    public void Can_leave_identifiers_unquoted()
    {
        var sql = Builder(quote: false).BuildSelect(new EntityQuery(Person, [Field("city")]));

        Assert.Equal("SELECT CITY FROM dbo.PERSON ORDER BY ID ASC", sql);
    }

    [Fact]
    public void Appends_the_key_to_the_requested_order_for_stable_pages()
    {
        var order = new[] { new SortOrder(Field("city"), SortDirection.Desc) };
        var sql = Builder().BuildSelect(new EntityQuery(Person, [Field("city")], Order: order));

        Assert.EndsWith("ORDER BY [CITY] DESC, [ID] ASC", sql, StringComparison.Ordinal);
    }

    [Fact]
    public void Wraps_the_values_compared_with_a_tokenized_column_in_transform()
    {
        var filter = new FieldCondition(Field("firstName"), FilterOperator.Eq, "Jean-Claude");
        var sql = Builder().BuildCount(Person, filter);

        Assert.Equal(
            "SELECT COUNT(*) FROM [dbo].[PERSON] WHERE [FIRST_NAME] = transform('Person', 'shortString', 'CH', 'Jean-Claude')",
            sql);
    }

    [Fact]
    public void Uses_transform_search_for_a_prefix_match_on_a_tokenized_column()
    {
        var filter = new FieldCondition(Field("firstName"), FilterOperator.StartsWith, "Jean");
        var sql = Builder().BuildCount(Person, filter);

        Assert.EndsWith("WHERE [FIRST_NAME] LIKE transform_search('Person', 'shortString', 'CH', 'Jean')", sql, StringComparison.Ordinal);
    }

    [Fact]
    public void Transforms_every_value_of_an_in_list()
    {
        var filter = new FieldCondition(Field("firstName"), FilterOperator.In, new[] { "Anna", "Marc" });
        var sql = Builder().BuildCount(Person, filter);

        Assert.EndsWith(
            "WHERE [FIRST_NAME] IN (transform('Person', 'shortString', 'CH', 'Anna'), transform('Person', 'shortString', 'CH', 'Marc'))",
            sql,
            StringComparison.Ordinal);
    }

    [Theory]
    [InlineData(FilterOperator.Contains)]
    [InlineData(FilterOperator.EndsWith)]
    [InlineData(FilterOperator.Gt)]
    public void Rejects_the_operators_a_token_cannot_support(FilterOperator op)
    {
        var filter = new FieldCondition(Field("firstName"), op, "x");

        Assert.Throws<SqlGenerationException>(() => Builder().BuildCount(Person, filter));
    }

    [Fact]
    public void Escapes_quotes_in_string_literals()
    {
        var filter = new FieldCondition(Field("city"), FilterOperator.Eq, "L'Abbaye'; DROP TABLE PERSON; --");
        var sql = Builder().BuildCount(Person, filter);

        Assert.EndsWith("WHERE [CITY] = 'L''Abbaye''; DROP TABLE PERSON; --'", sql, StringComparison.Ordinal);
    }

    [Fact]
    public void Escapes_the_wildcards_of_a_like_value()
    {
        var filter = new FieldCondition(Field("city"), FilterOperator.Contains, "50%_[a]");
        var sql = Builder().BuildCount(Person, filter);

        Assert.EndsWith(@"WHERE [CITY] LIKE '%50\%\_\[a]%' ESCAPE '\'", sql, StringComparison.Ordinal);
    }

    [Fact]
    public void Combines_and_or_not_with_parentheses()
    {
        var filter = new AndFilter(
        [
            new OrFilter(
            [
                new FieldCondition(Field("city"), FilterOperator.StartsWith, "Gen"),
                new FieldCondition(Field("city"), FilterOperator.IsNull, true),
            ]),
            new NotFilter(new FieldCondition(Field("active"), FilterOperator.Eq, false)),
        ]);
        var sql = Builder().BuildCount(Person, filter);

        Assert.EndsWith(@"WHERE (([CITY] LIKE 'Gen%' ESCAPE '\' OR [CITY] IS NULL) AND NOT ([ACTIVE] = 0))", sql, StringComparison.Ordinal);
    }

    [Fact]
    public void Renders_typed_literals()
    {
        var filter = new AndFilter(
        [
            new FieldCondition(Field("birthDate"), FilterOperator.Gte, new DateOnly(1980, 1, 31)),
            new FieldCondition(Field("balance"), FilterOperator.Lt, 1234.5m),
            new FieldCondition(Field("active"), FilterOperator.Eq, true),
        ]);

        Assert.EndsWith(
            "WHERE ([BIRTH_DATE] >= '1980-01-31' AND [BALANCE] < 1234.5 AND [ACTIVE] = 1)",
            Builder().BuildCount(Person, filter),
            StringComparison.Ordinal);
        Assert.EndsWith(
            "WHERE (\"BIRTH_DATE\" >= DATE '1980-01-31' AND \"BALANCE\" < 1234.5 AND \"ACTIVE\" = TRUE)",
            Builder(SqlDialectKind.Ansi).BuildCount(Person, filter),
            StringComparison.Ordinal);
    }

    [Fact]
    public void An_empty_in_list_matches_nothing()
    {
        var filter = new FieldCondition(Field("id"), FilterOperator.In, Array.Empty<int>());

        Assert.EndsWith("WHERE [ID] IN (NULL)", Builder().BuildCount(Person, filter), StringComparison.Ordinal);
    }

    [Fact]
    public void Limits_the_size_of_an_in_list()
    {
        var filter = new FieldCondition(Field("id"), FilterOperator.In, new[] { 1, 2, 3, 4 });

        Assert.Throws<SqlGenerationException>(() => Builder().BuildCount(Person, filter));
    }

    [Fact]
    public void Rejects_a_value_of_the_wrong_type()
    {
        var filter = new FieldCondition(Field("id"), FilterOperator.Eq, "not a number");

        Assert.Throws<SqlGenerationException>(() => Builder().BuildCount(Person, filter));
    }
}
