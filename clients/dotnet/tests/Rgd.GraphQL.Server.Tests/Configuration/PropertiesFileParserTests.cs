using Microsoft.Extensions.Configuration;
using Rgd.GraphQL.Server.Configuration;

namespace Rgd.GraphQL.Server.Tests.Configuration;

public class PropertiesFileParserTests
{
    [Fact]
    public void Parses_separators_comments_and_blank_lines()
    {
        var properties = PropertiesFileParser.Parse("""
            # a comment
            ! another comment

            a=1
            b : 2
            c 3
              d   =   spaced value
            e=
            """);

        Assert.Equal("1", properties["a"]);
        Assert.Equal("2", properties["b"]);
        Assert.Equal("3", properties["c"]);
        Assert.Equal("spaced value", properties["d"]);
        Assert.Equal(string.Empty, properties["e"]);
        Assert.Equal(5, properties.Count);
    }

    [Fact]
    public void Joins_continued_lines()
    {
        var properties = PropertiesFileParser.Parse("list=one, \\\n    two, \\\n    three\nnext=value");

        Assert.Equal("one, two, three", properties["list"]);
        Assert.Equal("value", properties["next"]);
    }

    [Fact]
    public void An_even_number_of_backslashes_does_not_continue_the_line()
    {
        var properties = PropertiesFileParser.Parse("path=c:\\\\\nnext=value");

        Assert.Equal("c:\\", properties["path"]);
        Assert.Equal("value", properties["next"]);
    }

    [Fact]
    public void Decodes_escapes()
    {
        var properties = PropertiesFileParser.Parse("key\\=with\\:separators=tab\\there \\u00e9\\#");

        Assert.Equal("tab\there é#", properties["key=with:separators"]);
    }

    [Fact]
    public void Keeps_separators_and_hashes_inside_the_value()
    {
        var properties = PropertiesFileParser.Parse("flightsql.password=p@ss=word#1:x");

        Assert.Equal("p@ss=word#1:x", properties["flightsql.password"]);
    }

    [Fact]
    public void The_last_value_of_a_repeated_key_wins()
    {
        var properties = PropertiesFileParser.Parse("a=1\na=2");

        Assert.Equal("2", properties["a"]);
    }

    [Theory]
    [InlineData("flightsql.host", "flightsql:host")]
    [InlineData("flightsql.tls.trust-server-certificate", "flightsql:tls:trustservercertificate")]
    [InlineData("graphql.model-file", "graphql:modelfile")]
    [InlineData("logging.level.Microsoft.AspNetCore", "Logging:LogLevel:Microsoft.AspNetCore")]
    public void Maps_property_keys_onto_configuration_keys(string propertyKey, string configurationKey)
    {
        Assert.Equal(configurationKey, PropertiesConfigurationProvider.ToConfigurationKey(propertyKey));
    }

    [Fact]
    public void Binds_the_options_from_a_properties_file()
    {
        var path = Path.GetTempFileName();
        try
        {
            File.WriteAllText(path, """
                flightsql.host=db.example.com
                flightsql.port=31337
                flightsql.username=reader
                flightsql.password=secret
                flightsql.tls.enabled=true
                flightsql.tls.trust-server-certificate=true
                flightsql.timeout-seconds=5
                logging.level.Rgd.GraphQL=Debug
                """);

            var configuration = new ConfigurationBuilder().AddPropertiesFile(path).Build();
            var options = configuration.GetSection(FlightSqlOptions.SectionName).Get<FlightSqlOptions>()!;

            Assert.Equal("db.example.com", options.Host);
            Assert.Equal(31337, options.Port);
            Assert.Equal("reader", options.Username);
            Assert.Equal("secret", options.Password);
            Assert.True(options.Tls.Enabled);
            Assert.True(options.Tls.TrustServerCertificate);
            Assert.Equal(TimeSpan.FromSeconds(5), options.Timeout);
            Assert.Equal(new Uri("https://db.example.com:31337/"), options.Address);
            Assert.Equal("Debug", configuration["Logging:LogLevel:Rgd.GraphQL"]);
        }
        finally
        {
            File.Delete(path);
        }
    }
}
