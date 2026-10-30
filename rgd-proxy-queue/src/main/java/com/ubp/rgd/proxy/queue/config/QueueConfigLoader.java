package com.ubp.rgd.proxy.queue.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Reads and validates {@code mq_transform_config.json} and the credentials its routes name.
 * <p>
 * Every defect is reported at once, and any defect stops the application: a queue proxy that would
 * skip a misconfigured route would leave its messages piling up unnoticed.
 */
public final class QueueConfigLoader {

    static final String CREDENTIALS_PREFIX = "proxy.queue.credentials.";

    private QueueConfigLoader() {
    }

    /**
     * @param configFile the routes file
     * @param properties reads a configuration property, e.g. {@code proxy.queue.credentials.gva.user}
     * @return the routes and the credentials of the aliases they name
     * @throws IllegalStateException when the file is missing, unreadable or invalid
     */
    public static QueueConfiguration load(String configFile, Function<String, Optional<String>> properties) {
        File file = new File(configFile);
        if (!file.isFile()) {
            throw invalid(configFile, List.of("the file does not exist"));
        }
        List<QueueRouteConfig> routes;
        try {
            routes = new ObjectMapper().readValue(file, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw invalid(configFile, List.of("the file cannot be read: " + e.getMessage()));
        }
        return validate(configFile, routes == null ? List.of() : routes, properties);
    }

    public static QueueConfiguration validate(String source, List<QueueRouteConfig> routes,
                                       Function<String, Optional<String>> properties) {
        List<String> errors = new ArrayList<>();
        Map<String, QueueCredentials> credentials = new HashMap<>();
        Set<String> names = new HashSet<>();

        for (int i = 0; i < routes.size(); i++) {
            QueueRouteConfig route = routes.get(i);
            String label = route.getName() == null || route.getName().isBlank()
                    ? "route #" + (i + 1) : "route '" + route.getName() + "'";
            if (route.getName() == null || route.getName().isBlank()) {
                errors.add(label + ": name is missing");
            } else if (!names.add(route.getName())) {
                errors.add(label + ": the name is used by another route");
            }
            if (route.getConcurrency() < 1) {
                errors.add(label + ": concurrency must be at least 1");
            }
            checkAction(label, route, errors);
            checkEndpoint(label, "input", route.getInput(), true, errors, credentials, properties);
            checkEndpoint(label, "output", route.getOutput(), true, errors, credentials, properties);
            checkEndpoint(label, "error", route.getError(), false, errors, credentials, properties);
            if (route.getInput() != null && route.getOutput() != null
                    && sameQueue(route.getInput(), route.getOutput())) {
                errors.add(label + ": input and output are the same queue");
            }
        }
        if (!errors.isEmpty()) {
            throw invalid(source, errors);
        }
        return new QueueConfiguration(List.copyOf(routes), Map.copyOf(credentials));
    }

    private static void checkAction(String label, QueueRouteConfig route, List<String> errors) {
        Map<String, String> evidences = route.getProcessingContextEvidences();
        String action = evidences == null ? null : evidences.entrySet().stream()
                .filter(e -> "Action".equalsIgnoreCase(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
        if (!"Protect".equalsIgnoreCase(action) && !"Unprotect".equalsIgnoreCase(action)) {
            errors.add(label + ": processing-context.Action must be Protect or Unprotect");
        }
    }

    private static void checkEndpoint(String label, String role, QueueEndpointConfig endpoint, boolean mandatory,
                                      List<String> errors, Map<String, QueueCredentials> credentials,
                                      Function<String, Optional<String>> properties) {
        if (endpoint == null) {
            if (mandatory) {
                errors.add(label + ": " + role + " is missing");
            }
            return;
        }
        requireText(label, role + ".host", endpoint.getHost(), errors);
        requireText(label, role + ".queue-manager", endpoint.getQueueManager(), errors);
        requireText(label, role + ".channel", endpoint.getChannel(), errors);
        requireText(label, role + ".queue", endpoint.getQueue(), errors);
        if (endpoint.getPort() < 1 || endpoint.getPort() > 65535) {
            errors.add(label + ": " + role + ".port must be between 1 and 65535");
        }
        String alias = endpoint.getCredentials();
        if (alias != null && !credentials.containsKey(alias)) {
            Optional<String> user = properties.apply(CREDENTIALS_PREFIX + alias + ".user").filter(u -> !u.isBlank());
            if (user.isEmpty()) {
                errors.add(label + ": " + role + ".credentials '" + alias + "' has no "
                        + CREDENTIALS_PREFIX + alias + ".user property");
            } else {
                String password = properties.apply(CREDENTIALS_PREFIX + alias + ".password").orElse("");
                credentials.put(alias, new QueueCredentials(user.get(), password));
            }
        }
    }

    private static void requireText(String label, String field, String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(label + ": " + field + " is missing");
        }
    }

    private static boolean sameQueue(QueueEndpointConfig a, QueueEndpointConfig b) {
        return a.queueManagerKey().equals(b.queueManagerKey()) && a.getQueue() != null && a.getQueue().equals(b.getQueue());
    }

    private static IllegalStateException invalid(String source, List<String> errors) {
        return new IllegalStateException("Invalid queue proxy configuration " + source + ": " + String.join("; ", errors));
    }
}
