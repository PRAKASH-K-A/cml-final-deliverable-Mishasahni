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

    @ConfigProperty(name = "quickfix.port", defaultValue = "9876")
    int quickfixPort;

    @ConfigProperty(name = "quickfix.heartbeat", defaultValue = "30")
    int heartbeatInterval;

    public SessionSettingsFactory() {
        LOG.info("SessionSettingsFactory Constructor");
    }

    public SessionSettings getSessionSettings() throws ConfigError {
        LOG.info("getSessionSettings called - Exchange Acceptor mode");
        
        // FIX 4.4 Acceptor settings with enhanced configuration
        String settings = "[DEFAULT]\n" +
                "ConnectionType=acceptor\n" +
                "StartTime=00:00:00\n" +
                "EndTime=00:00:00\n" +
                "HeartBtInt=" + heartbeatInterval + "\n" +
                "ReconnectInterval=5\n" +
                "SocketAcceptPort=" + quickfixPort + "\n" +
                "FileStorePath=./runtime/data/exchange\n" +
                "FileLogPath=./runtime/logs/exchange\n" +
                "UseDataDictionary=Y\n" +
                "DataDictionary=FIX44.xml\n" +
                "ValidateUserDefinedFields=N\n" +
                "ValidateFieldsOutOfOrder=N\n" +
                "ValidateFieldsHaveValues=Y\n" +
                "AllowUnknownMsgFields=Y\n" +
                "RefreshOnLogon=Y\n" +
                "ResetOnLogon=Y\n" +
                "ResetOnLogout=Y\n" +
                "ResetOnDisconnect=Y\n" +
                "SendRedundantResendRequests=Y\n" +
                "PersistMessages=Y\n" +
                "LogonTimeout=30\n" +
                "LogoutTimeout=5\n" +
                "\n" +
                "[SESSION]\n" +
                "BeginString=FIX.4.4\n" +
                "SenderCompID=EXCHANGE\n" +
                "TargetCompID=BROKER\n" +
                "SocketAcceptPort=" + quickfixPort + "\n" +
                "\n" +
                "[SESSION]\n" +
                "BeginString=FIX.4.4\n" +
                "SenderCompID=EXCHANGE\n" +
                "TargetCompID=MINIFIX\n" +
                "SocketAcceptPort=" + quickfixPort + "\n" +
                "\n" +
                "[SESSION]\n" +
                "BeginString=FIX.4.4\n" +
                "SenderCompID=EXCHANGE\n" +
                "TargetCompID=MINIFIXJ\n" +
                "SocketAcceptPort=" + quickfixPort + "\n" +
                "\n" +
                "[SESSION]\n" +
                "BeginString=FIX.4.4\n" +
                "SenderCompID=EXCHANGE\n" +
                "TargetCompID=MINIFIX-J\n" +
                "SocketAcceptPort=" + quickfixPort + "\n" +
                "\n" +
                "[SESSION]\n" +
                "BeginString=FIX.4.2\n" +
                "SenderCompID=EXCHANGE\n" +
                "TargetCompID=BROKER\n" +
                "UseDataDictionary=N\n" +
                "ValidateSequenceNumbers=N\n" +
                "SocketAcceptPort=" + quickfixPort + "\n" +
                "\n" +
                "[SESSION]\n" +
                "BeginString=FIX.4.2\n" +
                "SenderCompID=EXCHANGE\n" +
                "TargetCompID=MINIFIX\n" +
                "UseDataDictionary=N\n" +
                "ValidateSequenceNumbers=N\n" +
                "SocketAcceptPort=" + quickfixPort + "\n" +
                "\n" +
                "[SESSION]\n" +
                "BeginString=FIX.4.2\n" +
                "SenderCompID=EXCHANGE\n" +
                "TargetCompID=MINIFIXJ\n" +
                "UseDataDictionary=N\n" +
                "ValidateSequenceNumbers=N\n" +
                "SocketAcceptPort=" + quickfixPort + "\n" +
                "\n" +
                "[SESSION]\n" +
                "BeginString=FIX.4.2\n" +
                "SenderCompID=EXCHANGE\n" +
                "TargetCompID=MINIFIX-J\n" +
                "UseDataDictionary=N\n" +
                "ValidateSequenceNumbers=N\n" +
                "SocketAcceptPort=" + quickfixPort + "\n";
        
            LOG.info("Exchange Acceptor configured on port {} (accepts FIX 4.4 and FIX 4.2 sessions for BROKER, MINIFIX, MINIFIXJ, MINIFIX-J)", quickfixPort);
        ByteArrayInputStream inputStream = new ByteArrayInputStream(settings.getBytes(StandardCharsets.UTF_8));
        return new SessionSettings(inputStream);
    }
}
