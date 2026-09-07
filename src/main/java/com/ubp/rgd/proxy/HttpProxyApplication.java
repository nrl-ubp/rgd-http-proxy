package com.ubp.rgd.proxy;

import com.ubp.rgd.proxy.security.SecurityUtils;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@QuarkusMain
public class HttpProxyApplication implements QuarkusApplication {

    private static final Logger LOG = LoggerFactory.getLogger(HttpProxyApplication.class);

    public static void main(String[] args) {

        try {

            // init log manager
            System.setProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager");

            // init properties for Kerberos from files in the config dir.
            SecurityUtils.setup();

            Quarkus.run(HttpProxyApplication.class, args);
        } catch (Throwable ex) {
            LOG.error("ERROR in Quarkus app.", ex);
        }
    }

    @Override
    public int run(String... args) {
        LOG.info("Quarkus HTTP Proxy starting...");
        Quarkus.waitForExit();
        return 0;
    }
}