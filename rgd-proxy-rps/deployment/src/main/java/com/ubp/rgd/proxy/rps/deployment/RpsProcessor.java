package com.ubp.rgd.proxy.rps.deployment;

import com.ubp.rgd.proxy.rps.RPSClientEngineProvider;
import com.ubp.rgd.proxy.rps.RPSEndPointTransformer;
import com.ubp.rgd.proxy.rps.TokenSecretsManagerResolver;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

/**
 * Build steps of the RPS transformer extension.
 */
class RpsProcessor {

    private static final String FEATURE = "rgd-proxy-rps";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * The transformer is only looked up by name by the producer of the SPI, never injected by type,
     * so it must not be removed as unused.
     */
    @BuildStep
    AdditionalBeanBuildItem beans() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(RPSEndPointTransformer.class, RPSClientEngineProvider.class,
                        TokenSecretsManagerResolver.class)
                .setUnremovable()
                .build();
    }
}
