package com.helesto.core;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class Bootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(Bootstrap.class.getName());

    @Inject
    Exchange exchange;

    public Bootstrap() {
        LOG.info("Constructor");
    }

    public void onStart(@Observes StartupEvent StartupEvent) {
        LOG.info("onStart - Initializing Exchange...");
        try {
            exchange.init();
        } catch (Exception e) {
            LOG.error("Error initializing exchange", e);
        }
    }

    public void onStop(@Observes ShutdownEvent shutdownEvent) {
        LOG.info("onStop - Shutting down Exchange...");
        exchange.stop();
    }

}
