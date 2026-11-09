package com.ubp.rgd.proxy.flightsql;

import com.ubp.rgd.proxy.transform.model.ValueMapping;
import com.ubp.rgd.proxy.transform.model.MappedValue;
import com.ubp.rgd.proxy.sqlgateway.config.MappingConfig;

/**
 * A {@link FlightSqlTokenizeService} for the Flight SQL tests, producing a readable fake token
 * instead of calling an engine. The tests run without an RPS engine, and what matters here is that
 * the {@code transform()} calls reach the database rewritten, not what the token looks like.
 */
class LocalTokenizeService extends FlightSqlTokenizeService {

    LocalTokenizeService() {
        this(new MappingConfig());
    }

    LocalTokenizeService(MappingConfig config) {
        setMappingConfigProvider(new FlightSqlMappingConfigProvider() {
            @Override
            public MappingConfig get() {
                return config;
            }
        });
    }

    @Override
    protected void transformValues(MappedValue[] values) {
        for (MappedValue value : values) {
            ValueMapping mapping = value.getMapping();
            value.setTransformed(mapping.getClassName() + "." + mapping.getPropertyName()
                    + "=" + value.getOriginal());
        }
    }
}
