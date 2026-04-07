package com.helesto.core;

import java.io.File;
import java.util.Iterator;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.CompositeLogFactory;
import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.FileLogFactory;
import quickfix.FileStoreFactory;
import quickfix.Initiator;
import quickfix.LogFactory;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.ScreenLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;

@Singleton
public class Trader {

    @ConfigProperty(name = "quickfix.appVersion")
    String appVersion;

    @ConfigProperty(name = "quickfix.password")
    String password;

    @ConfigProperty(name = "quickfix.activateScreenLog")
    boolean activateScreenLog;

    @ConfigProperty(name = "quickfix.autoStart")
    boolean autoStart;

    @Inject
    SessionSettingsFactory sessionSettingsFactory;

    @Inject
    TraderApplication traderApplication;

    private static final Logger LOG = LoggerFactory.getLogger(Trader.class.getName());
    private boolean initiatorStarted;
    private SessionSettings sessionSettings;
    private Initiator initiator;

    public Trader() {
        LOG.info("Trader Constructor");
    }

    public void init() {
        LOG.info("Trader initializing...");
        try {
            sessionSettings = sessionSettingsFactory.getSessionSettings();
            LOG.info("SessionSettings created");

            // Create directories for FileStore and FileLog
            new File("./runtime/data/broker").mkdirs();
            new File("./runtime/logs/broker").mkdirs();

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

            initiator = new SocketInitiator(traderApplication, messageStoreFactory, sessionSettings, logFactory,
                    messageFactory);
            LOG.info("SocketInitiator created");

            if (autoStart) {
                logon();
            }

        } catch (ConfigError e) {
            LOG.error("ConfigError initializing trader", e);
            if (initiatorStarted) {
                stop();
            }
        }
    }

    public synchronized void logon() throws ConfigError {
        LOG.info("Logging on...");
        if (!initiatorStarted) {
            initiator.start();
            initiatorStarted = true;
            LOG.info("Logon initiated");
        }
    }

    public synchronized void stop() {
        LOG.info("Stopping initiator...");
        if (initiatorStarted) {
            initiator.stop();
            initiatorStarted = false;
            LOG.info("Initiator stopped");
        }
    }

    public synchronized void logout() {
        LOG.info("Logging out...");
        if (initiatorStarted) {
            try {
                Session session = Session.lookupSession(getSessionIDFromInitiator());
                session.logout();
            } catch (Exception e) {
                LOG.error("Error during logout", e);
            }
        }
    }

    // Getters/Setters
    public SessionID getSessionIDFromInitiator() {
        if (initiatorStarted) {
            return initiator.getSessions().get(0);
        } else {
            throw new RuntimeException("Initiator not started");
        }
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
        return Session.lookupSession(getSessionIDFromInitiator());
    }

    public boolean isInitiatorStarted() {
        return initiatorStarted;
    }

    public SessionSettings getSessionSettings() {
        return sessionSettings;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public String getPassword() {
        return password;
    }
}
