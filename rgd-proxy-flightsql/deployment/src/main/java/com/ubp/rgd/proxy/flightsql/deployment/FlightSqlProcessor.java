package com.ubp.rgd.proxy.flightsql.deployment;

import com.ubp.rgd.proxy.flightsql.FlightSqlConnectionManager;
import com.ubp.rgd.proxy.flightsql.FlightSqlDetokenizeService;
import com.ubp.rgd.proxy.flightsql.FlightSqlMappingConfigProvider;
import com.ubp.rgd.proxy.flightsql.FlightSqlServerService;
import com.ubp.rgd.proxy.flightsql.FlightSqlTokenizeService;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

/**
 * Build steps of the Flight SQL server extension.
 */
class FlightSqlProcessor {

    private static final String FEATURE = "rgd-proxy-flightsql";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * The extension's runtime jar is not a bean archive: its beans are declared here. They are kept even
     * when nothing injects them, so that tools and tests can look them up.
     */
    @BuildStep
    AdditionalBeanBuildItem beans() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(FlightSqlServerService.class, FlightSqlConnectionManager.class,
                        FlightSqlDetokenizeService.class, FlightSqlTokenizeService.class,
                        FlightSqlMappingConfigProvider.class)
                .setUnremovable()
                .build();
    }
}
