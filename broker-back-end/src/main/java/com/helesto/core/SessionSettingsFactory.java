package com.helesto.core;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import quickfix.ConfigError;
import quickfix.SessionSettings;

@Singleton
public class SessionSettingsFactory {

    private static final Logger LOG = LoggerFactory.getLogger(SessionSettingsFactory.class.getName());

    @ConfigProperty(name = "quickfix.host", defaultValue = "localhost")
    String quickfixHost;

    @ConfigProperty(name = "quickfix.port", defaultValue = "9876")
    int quickfixPort;

    @ConfigProperty(name = "quickfix.heartbeat", defaultValue = "30")
    int heartbeatInterval;

    public SessionSettingsFactory() {
        LOG.info("SessionSettingsFactory Constructor");
    }

    public SessionSettings getSessionSettings() throws ConfigError {
        LOG.info("getSessionSettings called - Broker Initiator mode");
        
        // FIX 4.4 Initiator settings with enhanced configuration
        String settings = "[DEFAULT]\n" +
                "ConnectionType=initiator\n" +
                "StartTime=00:00:00\n" +
                "EndTime=00:00:00\n" +
                "HeartBtInt=" + heartbeatInterval + "\n" +
                "ReconnectInterval=5\n" +
                "SocketConnectHost=" + quickfixHost + "\n" +
                "SocketConnectPort=" + quickfixPort + "\n" +
                "FileStorePath=./runtime/data/broker\n" +
                "FileLogPath=./runtime/logs/broker\n" +
                "UseDataDictionary=Y\n" +
                "DataDictionary=FIX44.xml\n" +
                "ValidateUserDefinedFields=N\n" +
                "ValidateFieldsOutOfOrder=N\n" +
                "ValidateFieldsHaveValues=Y\n" +
                "AllowUnknownMsgFields=Y\n" +
                "RefreshOnLogon=Y\n" +
                "ResetOnLogon=N\n" +
                "ResetOnLogout=Y\n" +
                "ResetOnDisconnect=N\n" +
                "SendRedundantResendRequests=Y\n" +
                "PersistMessages=Y\n" +
                "LogonTimeout=30\n" +
                "LogoutTimeout=5\n" +
                "\n" +
                "[SESSION]\n" +
                "BeginString=FIX.4.4\n" +
                "SenderCompID=BROKER\n" +
                "TargetCompID=EXCHANGE\n" +
                "SocketConnectHost=" + quickfixHost + "\n" +
                "SocketConnectPort=" + quickfixPort + "\n";
        
        LOG.info("Broker Initiator configured to connect to {}:{}", quickfixHost, quickfixPort);
        ByteArrayInputStream inputStream = new ByteArrayInputStream(settings.getBytes(StandardCharsets.UTF_8));
        return new SessionSettings(inputStream);
    }
}
