package com.helesto.core;

import java.io.File;
import java.util.Iterator;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.Acceptor;
import quickfix.CompositeLogFactory;
import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.FileLogFactory;
import quickfix.FileStoreFactory;
import quickfix.LogFactory;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.ScreenLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;

@Singleton
public class Exchange {

    private static final Logger LOG = LoggerFactory.getLogger(Exchange.class.getName());

    @ConfigProperty(name = "quickfix.activateScreenLog")
    boolean activateScreenLog;

    @ConfigProperty(name = "quickfix.automatic.trade", defaultValue = "false")
    boolean automaticTrade;

    @ConfigProperty(name = "quickfix.automatic.trade.seconds", defaultValue = "5")
    int automaticTradeSeconds;

    @Inject
    SessionSettingsFactory sessionSettingsFactory;

    @Inject
    ExchangeApplication exchangeApplication;

    private SessionSettings sessionSettings;
    private boolean acceptorStarted;
    private Acceptor acceptor;

    public Exchange() {
        LOG.info("Exchange Constructor");
    }

    public void init() {
        LOG.info("Exchange initializing...");
        try {
            sessionSettings = sessionSettingsFactory.getSessionSettings();
            LOG.info("SessionSettings created");

            // Create directories for FileStore and FileLog
            new File("./runtime/data/exchange").mkdirs();
            new File("./runtime/logs/exchange").mkdirs();

            // Use file-based store and log for development
            MessageStoreFactory messageStoreFactory = new FileStoreFactory(sessionSettings);
            LOG.info("MessageStoreFactory created - FileStoreFactory");

            LogFactory logFactory;
            FileLogFactory fileLogFactory = new FileLogFactory(sessionSettings);

            if (activateScreenLog) {
                logFactory = new CompositeLogFactory(
                        new LogFactory[] { new ScreenLogFactory(sessionSettings), fileLogFactory });
                LOG.info("LogFactory created - FileLogFactory and ScreenLogFactory");
            } else {
                logFactory = fileLogFactory;
                LOG.info("LogFactory created - FileLogFactory");
            }

            MessageFactory messageFactory = new DefaultMessageFactory();
            LOG.info("MessageFactory created - DefaultMessageFactory");

            acceptor = new SocketAcceptor(exchangeApplication, messageStoreFactory, sessionSettings, logFactory,
                    messageFactory);
            LOG.info("SocketAcceptor created");

            start();

        } catch (ConfigError e) {
            LOG.error("ConfigError initializing exchange", e);
            if (acceptorStarted) {
                stop();
            }
        }
    }

    public synchronized void start() throws ConfigError {
        LOG.info("Starting acceptor...");
        if (!acceptorStarted) {
            acceptor.start();
            acceptorStarted = true;
            LOG.info("Acceptor started successfully");
        }
    }

    public synchronized void stop() {
        LOG.info("Stopping acceptor...");
        if (acceptorStarted) {
            acceptor.stop();
            acceptorStarted = false;
            LOG.info("Acceptor stopped");
        }
    }

    // Getters/Setters

    public SessionSettings getSessionSettings() {
        return sessionSettings;
    }

    public boolean isAcceptorStarted() {
        return acceptorStarted;
    }

    public SessionID getSessionIDFromSettings() {
        Iterator<SessionID> iteratorSessionID = sessionSettings.sectionIterator();
        SessionID sessionID = null;
        while (iteratorSessionID.hasNext()) {
            sessionID = iteratorSessionID.next();
        }
        return sessionID;
    }

    public String getStringFromSettings(String key) throws ConfigError {
        return sessionSettings.getString(getSessionIDFromSettings(), key);
    }

    public Session getSession() {
        return Session.lookupSession(getSessionIDFromAcceptor());
    }

    public SessionID getSessionIDFromAcceptor() {
        if (acceptorStarted) {
            return acceptor.getSessions().get(0);
        } else {
            throw new RuntimeException("Acceptor not started");
        }
    }

    public boolean isAutomaticTrade() {
        return automaticTrade;
    }

    public void setAutomaticTrade(boolean automaticTrade) {
        this.automaticTrade = automaticTrade;
    }

    public int getAutomaticTradeSeconds() {
        return automaticTradeSeconds;
    }

    public void setAutomaticTradeSeconds(int automaticTradeSeconds) {
        this.automaticTradeSeconds = automaticTradeSeconds;
    }
}
