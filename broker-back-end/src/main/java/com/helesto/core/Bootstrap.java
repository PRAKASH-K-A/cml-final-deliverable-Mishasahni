package com.helesto.core;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
@Startup
public class Bootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(Bootstrap.class.getName());

    @Inject
    Trader trader;

    public Bootstrap() {
        LOG.info("Constructor");
    }

    public Trader getTrader() {
        return trader;
    }

    public void onStart(@Observes StartupEvent StartupEvent) {
        LOG.info("onStart - Initializing Trader...");
        try {
            trader.init();
        } catch (Exception e) {
            LOG.error("Error initializing trader", e);
        }
    }

    public void onStop(@Observes ShutdownEvent shutdownEvent) {
        LOG.info("onStop - Shutting down Trader...");
        trader.stop();
    }

}
