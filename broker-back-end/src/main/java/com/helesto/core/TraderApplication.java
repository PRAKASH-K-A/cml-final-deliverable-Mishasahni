package com.helesto.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.service.ExecutionReportService;

import quickfix.Application;
import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.MessageCracker;
import quickfix.RejectLogon;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;
import quickfix.field.BusinessRejectReason;
import quickfix.field.ClOrdID;
import quickfix.field.CxlRejReason;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.MsgType;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.OrigClOrdID;
import quickfix.field.RawData;
import quickfix.field.RawDataLength;
import quickfix.field.RefMsgType;
import quickfix.field.Text;
import quickfix.fix44.BusinessMessageReject;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.OrderCancelReject;

@Singleton
public class TraderApplication extends MessageCracker implements Application {

    private static final Logger LOG = LoggerFactory.getLogger(TraderApplication.class);

    @Inject
    Bootstrap bootstrap;

    @Inject
    ExecutionReportService executionReportService;

    // Session event tracking
    private final List<SessionEvent> sessionEvents = new CopyOnWriteArrayList<>();
    private final List<Consumer<SessionEvent>> sessionEventListeners = new CopyOnWriteArrayList<>();
    private volatile boolean sessionLoggedOn = false;
    private volatile SessionID currentSessionId = null;
    private volatile String connectionStatus = "DISCONNECTED";

    public TraderApplication() {
        LOG.info("TraderApplication Constructor");
    }

    @Override
    public void onCreate(SessionID sessionID) {
        LOG.info("FIX Session created - SessionID: {}", sessionID);
        connectionStatus = "CREATED";
        addSessionEvent("SESSION_CREATED", "Session created: " + sessionID);
    }

    @Override
    public void onLogon(SessionID sessionID) {
        LOG.info("FIX Logon successful - Connected to Exchange: {}", sessionID.getTargetCompID());
        sessionLoggedOn = true;
        currentSessionId = sessionID;
        connectionStatus = "CONNECTED";
        addSessionEvent("LOGON", "Connected to Exchange: " + sessionID.getTargetCompID());
    }

    @Override
    public void onLogout(SessionID sessionID) {
        LOG.info("FIX Logout - Disconnected from Exchange: {}", sessionID.getTargetCompID());
        sessionLoggedOn = false;
        connectionStatus = "DISCONNECTED";
        addSessionEvent("LOGOUT", "Disconnected from Exchange: " + sessionID.getTargetCompID());
    }

    @Override
    public void toAdmin(Message message, SessionID sessionID) {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            LOG.debug("toAdmin - MsgType: {}, SessionID: {}", msgType, sessionID);
            
            if (MsgType.LOGON.equals(msgType)) {
                LOG.info("Sending Logon request to Exchange");
                addLogonField(message, sessionID);
                addSessionEvent("LOGON_SENT", "Logon request sent");
            } else if (MsgType.LOGOUT.equals(msgType)) {
                LOG.info("Sending Logout to Exchange");
                addSessionEvent("LOGOUT_SENT", "Logout sent");
            } else if (MsgType.HEARTBEAT.equals(msgType)) {
                LOG.trace("Sending Heartbeat");
            } else if (MsgType.TEST_REQUEST.equals(msgType)) {
                LOG.debug("Sending Test Request");
                addSessionEvent("TEST_REQUEST_SENT", "Test request sent");
            }
        } catch (FieldNotFound e) {
            LOG.debug("toAdmin - cannot get MsgType");
        }
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionID)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
        String msgType = message.getHeader().getString(MsgType.FIELD);
        LOG.debug("fromAdmin - MsgType: {}, SessionID: {}", msgType, sessionID);
        
        if (MsgType.LOGON.equals(msgType)) {
            LOG.info("Received Logon confirmation from Exchange");
            addSessionEvent("LOGON_CONFIRMED", "Logon confirmed by Exchange");
        } else if (MsgType.LOGOUT.equals(msgType)) {
            LOG.info("Received Logout from Exchange");
            String text = "";
            try {
                text = message.getString(Text.FIELD);
            } catch (FieldNotFound e) {
                // No text
            }
            addSessionEvent("LOGOUT_RECEIVED", "Logout from Exchange: " + text);
        } else if (MsgType.REJECT.equals(msgType)) {
            LOG.warn("Received Session Reject from Exchange: {}", message);
            addSessionEvent("SESSION_REJECT", "Session reject received");
        } else if (MsgType.HEARTBEAT.equals(msgType)) {
            LOG.trace("Received Heartbeat");
        } else if (MsgType.TEST_REQUEST.equals(msgType)) {
            LOG.debug("Received Test Request - responding with Heartbeat");
            addSessionEvent("TEST_REQUEST_RECEIVED", "Test request received");
        } else if (MsgType.RESEND_REQUEST.equals(msgType)) {
            LOG.info("Received Resend Request from Exchange");
            addSessionEvent("RESEND_REQUEST", "Resend request received");
        } else if (MsgType.SEQUENCE_RESET.equals(msgType)) {
            LOG.info("Received Sequence Reset from Exchange");
            addSessionEvent("SEQUENCE_RESET", "Sequence reset received");
        }
    }

    @Override
    public void toApp(Message message, SessionID sessionID) throws DoNotSend {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            LOG.debug("toApp - Sending MsgType: {} to Exchange", msgType);
            
            if (MsgType.ORDER_SINGLE.equals(msgType)) {
                String clOrdId = message.getString(ClOrdID.FIELD);
                addSessionEvent("ORDER_SENT", "Order sent: " + clOrdId);
            } else if (MsgType.ORDER_CANCEL_REQUEST.equals(msgType)) {
                String origClOrdId = message.getString(OrigClOrdID.FIELD);
                addSessionEvent("CANCEL_SENT", "Cancel request sent: " + origClOrdId);
            }
        } catch (FieldNotFound e) {
            LOG.debug("toApp - cannot get message details");
        }
    }

    @Override
    public void fromApp(Message message, SessionID sessionID)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        String msgType = message.getHeader().getString(MsgType.FIELD);
        LOG.info("fromApp - Received MsgType: {} from Exchange", msgType);
        
        try {
            crack(message, sessionID);
        } catch (UnsupportedMessageType e) {
            LOG.warn("Unsupported message type received: {}", msgType);
            addSessionEvent("UNSUPPORTED_MSG", "Unsupported: " + msgType);
            throw e;
        }
    }

    private void addLogonField(Message message, SessionID sessionID) {
        LOG.debug("Adding Logon credentials");
        try {
            String password = bootstrap.getTrader().getPassword();
            if (password != null && !password.isEmpty()) {
                // Tag 95 RawDataLength
                message.getHeader().setField(new RawDataLength(password.length()));
                // Tag 96 RawData
                message.getHeader().setField(new RawData(password));
            }
        } catch (Exception e) {
            LOG.error("Error adding logon fields", e);
        }
    }

    /**
     * Handle ExecutionReport (MsgType=8)
     */
    public void onMessage(ExecutionReport executionReport, SessionID sessionID)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        LOG.info("Processing ExecutionReport from Exchange");
        
        try {
            String clOrdId = executionReport.getString(ClOrdID.FIELD);
            String orderId = executionReport.getString(OrderID.FIELD);
            char execType = executionReport.getChar(ExecType.FIELD);
            char ordStatus = executionReport.getChar(OrdStatus.FIELD);
            
            String execTypeStr = mapExecType(execType);
            String ordStatusStr = mapOrdStatus(ordStatus);
            
            LOG.info("ExecutionReport - ClOrdID: {}, OrderID: {}, ExecType: {}, Status: {}", 
                     clOrdId, orderId, execTypeStr, ordStatusStr);
            
            // Log additional details for fills
            if (execType == ExecType.TRADE || execType == ExecType.FILL) {
                try {
                    double lastQty = executionReport.getDouble(LastQty.FIELD);
                    double lastPx = executionReport.getDouble(LastPx.FIELD);
                    LOG.info("Fill: {} @ {}", lastQty, lastPx);
                    addSessionEvent("ORDER_FILLED", "Order " + clOrdId + " filled: " + lastQty + " @ " + lastPx);
                } catch (FieldNotFound e) {
                    // No fill details
                }
            } else if (execType == ExecType.NEW) {
                addSessionEvent("ORDER_ACKED", "Order " + clOrdId + " acknowledged");
            } else if (execType == ExecType.CANCELED) {
                addSessionEvent("ORDER_CANCELED", "Order " + clOrdId + " canceled");
            } else if (execType == ExecType.REJECTED) {
                String rejectReason = "";
                try {
                    rejectReason = executionReport.getString(Text.FIELD);
                } catch (FieldNotFound e) {
                    // No text
                }
                addSessionEvent("ORDER_REJECTED", "Order " + clOrdId + " rejected: " + rejectReason);
            } else if (execType == ExecType.REPLACED) {
                addSessionEvent("ORDER_REPLACED", "Order " + clOrdId + " replaced");
            }
            
            // Process through service
            executionReportService.processExecutionReport(executionReport, sessionID);
            
        } catch (FieldNotFound e) {
            LOG.error("Required field not found in ExecutionReport", e);
            addSessionEvent("EXEC_REPORT_ERROR", "Error processing execution report: " + e.getMessage());
        }
    }

    /**
     * Handle OrderCancelReject (MsgType=9)
     */
    public void onMessage(OrderCancelReject cancelReject, SessionID sessionID)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        LOG.info("Processing OrderCancelReject from Exchange");
        
        try {
            String clOrdId = cancelReject.getString(ClOrdID.FIELD);
            String origClOrdId = cancelReject.getString(OrigClOrdID.FIELD);
            char ordStatus = cancelReject.getChar(OrdStatus.FIELD);
            
            String rejectReason = "";
            try {
                int reason = cancelReject.getInt(CxlRejReason.FIELD);
                rejectReason = mapCxlRejReason(reason);
            } catch (FieldNotFound e) {
                // No reject reason
            }
            
            String text = "";
            try {
                text = cancelReject.getString(Text.FIELD);
            } catch (FieldNotFound e) {
                // No text
            }
            
            LOG.warn("Cancel rejected - OrigClOrdID: {}, Reason: {}, Text: {}", 
                    origClOrdId, rejectReason, text);
            
            addSessionEvent("CANCEL_REJECTED", "Cancel rejected for " + origClOrdId + ": " + rejectReason + " " + text);
            
            // Update order status in database
            executionReportService.processCancelReject(origClOrdId, rejectReason + " " + text);
            
        } catch (FieldNotFound e) {
            LOG.error("Error processing OrderCancelReject", e);
        }
    }

    /**
     * Handle BusinessMessageReject (MsgType=j)
     */
    public void onMessage(BusinessMessageReject reject, SessionID sessionID)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        LOG.warn("Processing BusinessMessageReject from Exchange");
        
        try {
            String refMsgType = reject.getString(RefMsgType.FIELD);
            int rejectReason = reject.getInt(BusinessRejectReason.FIELD);
            
            String text = "";
            try {
                text = reject.getString(Text.FIELD);
            } catch (FieldNotFound e) {
                // No text
            }
            
            LOG.warn("Business reject - RefMsgType: {}, Reason: {}, Text: {}", 
                    refMsgType, mapBusinessRejectReason(rejectReason), text);
            
            addSessionEvent("BUSINESS_REJECT", "Business reject: " + text);
            
        } catch (FieldNotFound e) {
            LOG.error("Error processing BusinessMessageReject", e);
        }
    }

    // Helper mapping methods

    private String mapExecType(char execType) {
        switch (execType) {
            case ExecType.NEW: return "NEW";
            case ExecType.PARTIAL_FILL: return "PARTIAL_FILL";
            case ExecType.FILL: return "FILL";
            case ExecType.DONE_FOR_DAY: return "DONE_FOR_DAY";
            case ExecType.CANCELED: return "CANCELED";
            case ExecType.REPLACED: return "REPLACED";
            case ExecType.PENDING_CANCEL: return "PENDING_CANCEL";
            case ExecType.STOPPED: return "STOPPED";
            case ExecType.REJECTED: return "REJECTED";
            case ExecType.SUSPENDED: return "SUSPENDED";
            case ExecType.PENDING_NEW: return "PENDING_NEW";
            case ExecType.TRADE: return "TRADE";
            case ExecType.ORDER_STATUS: return "ORDER_STATUS";
            default: return "UNKNOWN(" + execType + ")";
        }
    }

    private String mapOrdStatus(char ordStatus) {
        switch (ordStatus) {
            case OrdStatus.NEW: return "NEW";
            case OrdStatus.PARTIALLY_FILLED: return "PARTIALLY_FILLED";
            case OrdStatus.FILLED: return "FILLED";
            case OrdStatus.DONE_FOR_DAY: return "DONE_FOR_DAY";
            case OrdStatus.CANCELED: return "CANCELED";
            case OrdStatus.REPLACED: return "REPLACED";
            case OrdStatus.PENDING_CANCEL: return "PENDING_CANCEL";
            case OrdStatus.STOPPED: return "STOPPED";
            case OrdStatus.REJECTED: return "REJECTED";
            case OrdStatus.SUSPENDED: return "SUSPENDED";
            case OrdStatus.PENDING_NEW: return "PENDING_NEW";
            case OrdStatus.EXPIRED: return "EXPIRED";
            default: return "UNKNOWN(" + ordStatus + ")";
        }
    }

    private String mapCxlRejReason(int reason) {
        switch (reason) {
            case CxlRejReason.TOO_LATE_TO_CANCEL: return "TOO_LATE_TO_CANCEL";
            case CxlRejReason.UNKNOWN_ORDER: return "UNKNOWN_ORDER";
            case CxlRejReason.BROKER_EXCHANGE_OPTION: return "BROKER_EXCHANGE_OPTION";
            case CxlRejReason.ORDER_ALREADY_IN_PENDING_CANCEL_OR_PENDING_REPLACE_STATUS: 
                return "ALREADY_PENDING";
            default: return "OTHER(" + reason + ")";
        }
    }

    private String mapBusinessRejectReason(int reason) {
        switch (reason) {
            case BusinessRejectReason.OTHER: return "OTHER";
            case BusinessRejectReason.UNKNOWN_ID: return "UNKNOWN_ID";
            case BusinessRejectReason.UNKNOWN_SECURITY: return "UNKNOWN_SECURITY";
            case BusinessRejectReason.UNSUPPORTED_MESSAGE_TYPE: return "UNSUPPORTED_MESSAGE_TYPE";
            case BusinessRejectReason.APPLICATION_NOT_AVAILABLE: return "APPLICATION_NOT_AVAILABLE";
            default: return "UNKNOWN(" + reason + ")";
        }
    }

    // Session event tracking methods

    private void addSessionEvent(String type, String message) {
        SessionEvent event = new SessionEvent(type, message, System.currentTimeMillis());
        sessionEvents.add(event);
        
        // Notify listeners
        for (Consumer<SessionEvent> listener : sessionEventListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                LOG.error("Error notifying session event listener", e);
            }
        }
        
        // Keep only last 1000 events
        while (sessionEvents.size() > 1000) {
            sessionEvents.remove(0);
        }
    }

    public void addSessionEventListener(Consumer<SessionEvent> listener) {
        sessionEventListeners.add(listener);
    }

    public void removeSessionEventListener(Consumer<SessionEvent> listener) {
        sessionEventListeners.remove(listener);
    }

    public List<SessionEvent> getSessionEvents() {
        return new ArrayList<>(sessionEvents);
    }

    public List<SessionEvent> getRecentSessionEvents(int count) {
        int size = sessionEvents.size();
        int start = Math.max(0, size - count);
        return new ArrayList<>(sessionEvents.subList(start, size));
    }

    public boolean isSessionLoggedOn() {
        return sessionLoggedOn;
    }

    public SessionID getCurrentSessionId() {
        return currentSessionId;
    }

    public String getConnectionStatus() {
        return connectionStatus;
    }

    // Session event class
    public static class SessionEvent {
        public final String type;
        public final String message;
        public final long timestamp;

        public SessionEvent(String type, String message, long timestamp) {
            this.type = type;
            this.message = message;
            this.timestamp = timestamp;
        }
    }
}
