package com.ubp.rgd.proxy.fpe.deployment;

import com.ubp.rgd.proxy.fpe.FPEEndPointTransformer;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

/**
 * Build steps of the FPE transformer extension.
 */
class FpeProcessor {

    private static final String FEATURE = "rgd-proxy-fpe";

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
                .addBeanClasses(FPEEndPointTransformer.class)
                .setUnremovable()
                .build();
    }
}
