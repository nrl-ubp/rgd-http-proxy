using Microsoft.Extensions.Configuration.EnvironmentVariables;
using Rgd.GraphQL.Server.Configuration;
using Rgd.GraphQL.Server.Data;
using Rgd.GraphQL.Server.Data.FlightSql;
using Rgd.GraphQL.Server.Data.Sql;
using Rgd.GraphQL.Server.GraphQL;
using Rgd.GraphQL.Server.Health;
using Rgd.GraphQL.Server.Model;

namespace Rgd.GraphQL.Server;

/// <summary>Builds the GraphQL server from the content of the configuration directory.</summary>
public static class ServerApplication
{
    public static WebApplication Build(string[] args, Action<WebApplicationBuilder>? configure = null)
    {
        ArgumentNullException.ThrowIfNull(args);

        var configDirectory = ConfigDirectory.Resolve(args);
        var builder = WebApplication.CreateBuilder(args);
        AddPropertiesFile(builder.Configuration, configDirectory);

        var graphQLOptions = builder.Configuration.GetSection(GraphQLOptions.SectionName).Get<GraphQLOptions>() ?? new GraphQLOptions();
        var modelPath = Path.Combine(configDirectory, graphQLOptions.ModelFile);
        var model = ModelLoader.Load(modelPath);

        if (builder.Configuration["server:urls"] is { Length: > 0 } urls)
        {
            builder.WebHost.UseUrls(urls.Split([';', ','], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));
        }

        ConfigureServices(builder, model, graphQLOptions);
        configure?.Invoke(builder);

        var app = builder.Build();
        app.Logger.LogInformation(
            "Configuration read from {ConfigDirectory}, model {ModelPath} exposes {Entities}.",
            configDirectory, modelPath, string.Join(", ", model.Entities.Select(e => e.Name)));

        app.MapGraphQL(graphQLOptions.Path).WithOptions(o => o.Tool.Enable = graphQLOptions.EnableTooling);
        app.MapHealthChecks("/health");

        return app;
    }

    /// <summary>
    /// The properties file is inserted before the environment variables and the command line, which can
    /// therefore override any property, e.g. <c>FLIGHTSQL__PASSWORD</c> for <c>flightsql.password</c>.
    /// </summary>
    private static void AddPropertiesFile(ConfigurationManager configuration, string configDirectory)
    {
        var path = Path.Combine(configDirectory, ConfigDirectory.PropertiesFileName);
        if (!File.Exists(path))
        {
            throw new FileNotFoundException(
                $"The configuration file '{path}' does not exist. Start from the sample.{ConfigDirectory.PropertiesFileName} file of the configuration directory.",
                path);
        }

        IList<IConfigurationSource> sources = ((IConfigurationBuilder)configuration).Sources;
        var index = sources.ToList().FindIndex(s => s is EnvironmentVariablesConfigurationSource { Prefix: null or "" });
        var properties = new PropertiesConfigurationSource
        {
            Path = ConfigDirectory.PropertiesFileName,
            FileProvider = new Microsoft.Extensions.FileProviders.PhysicalFileProvider(configDirectory),
            Optional = false,
            ReloadOnChange = false,
        };

        if (index < 0)
        {
            sources.Add(properties);
        }
        else
        {
            sources.Insert(index, properties);
        }
    }

    private static void ConfigureServices(WebApplicationBuilder builder, DataModel model, GraphQLOptions graphQLOptions)
    {
        var services = builder.Services;

        services.AddOptions<FlightSqlOptions>().BindConfiguration(FlightSqlOptions.SectionName).ValidateDataAnnotations().ValidateOnStart();
        services.AddOptions<SqlOptions>().BindConfiguration(SqlOptions.SectionName).ValidateDataAnnotations().ValidateOnStart();
        services.AddOptions<GraphQLOptions>().BindConfiguration(GraphQLOptions.SectionName).ValidateDataAnnotations();

        services.AddSingleton(model);
        services.AddSingleton<FlightSqlConnection>();
        services.AddSingleton<IQueryExecutor, FlightSqlQueryExecutor>();
        services.AddSingleton<SqlQueryBuilder>();
        services.AddHostedService<FlightSqlStartupCheck>();

        services.AddHealthChecks().AddCheck<FlightSqlHealthCheck>("flightsql");

        services
            .AddGraphQLServer()
            .AddDataModel(model)
            .AddErrorFilter<DataErrorFilter>()
            .DisableIntrospection(!graphQLOptions.EnableTooling)
            .ModifyRequestOptions(o => o.IncludeExceptionDetails = graphQLOptions.IncludeExceptionDetails);
    }
}
