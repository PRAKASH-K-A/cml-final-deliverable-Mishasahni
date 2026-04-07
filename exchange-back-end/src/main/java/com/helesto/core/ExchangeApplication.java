package com.helesto.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.dao.OrderDao;
import com.helesto.model.OrderEntity;
import com.helesto.service.ExecutionReportService;
import com.helesto.service.FixOrderManagementEngine;
import com.helesto.service.QuickFixJOrderIntakeEngine;
import com.helesto.service.TelemetryService;

import quickfix.Application;
import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.MessageCracker;
import quickfix.RejectLogon;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.UnsupportedMessageType;
import quickfix.field.AvgPx;
import quickfix.field.BusinessRejectReason;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.CxlRejReason;
import quickfix.field.CxlRejResponseTo;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.LeavesQty;
import quickfix.field.MsgSeqNum;
import quickfix.field.MsgType;
import quickfix.field.OrdRejReason;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.RawData;
import quickfix.field.RefMsgType;
import quickfix.field.RefSeqNum;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.fix44.BusinessMessageReject;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelReject;
import quickfix.fix44.OrderCancelReplaceRequest;
import quickfix.fix44.OrderCancelRequest;
import quickfix.fix44.OrderStatusRequest;

@Singleton
public class ExchangeApplication extends MessageCracker implements Application {

    private static final Logger LOG = LoggerFactory.getLogger(ExchangeApplication.class);

    @Inject
    ExecutionReportService executionReportService;

    @Inject
    OrderDao orderDao;

    @Inject
    QuickFixJOrderIntakeEngine quickFixJOrderIntakeEngine;

    @Inject
    TelemetryService telemetryService;

    @Inject
    FixOrderManagementEngine fixOrderManagementEngine;

        private final ExecutorService asyncExecutor = new ThreadPoolExecutor(
            1,
            1,
            60,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(10000),
            runnable -> {
                Thread thread = new Thread(runnable, "fix-async-worker");
                thread.setDaemon(true);
                return thread;
            }
    );

    // Session event tracking
    private final List<SessionEvent> sessionEvents = new CopyOnWriteArrayList<>();
    private final List<Consumer<SessionEvent>> sessionEventListeners = new CopyOnWriteArrayList<>();
    private volatile boolean sessionLoggedOn = false;
    private volatile SessionID currentSessionId = null;

    public ExchangeApplication() {
        LOG.info("ExchangeApplication Constructor");
    }

    @Override
    public void onCreate(SessionID sessionID) {
        LOG.info("FIX Session created - SessionID: {}", sessionID);
        addSessionEvent("SESSION_CREATED", "Session created: " + sessionID);
    }

    @Override
    public void onLogon(SessionID sessionID) {
        LOG.info("FIX Logon successful - SessionID: {}", sessionID);
        sessionLoggedOn = true;
        currentSessionId = sessionID;
        addSessionEvent("LOGON", "Broker connected: " + sessionID.getTargetCompID());
    }

    @Override
    public void onLogout(SessionID sessionID) {
        LOG.info("FIX Logout - SessionID: {}", sessionID);
        sessionLoggedOn = false;
        addSessionEvent("LOGOUT", "Broker disconnected: " + sessionID.getTargetCompID());
    }

    @PreDestroy
    public void shutdownAsyncExecutor() {
        asyncExecutor.shutdown();
    }

    @Override
    public void toAdmin(Message message, SessionID sessionID) {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            LOG.debug("toAdmin - MsgType: {}, SessionID: {}", msgType, sessionID);
            
            // Log heartbeats at trace level
            if (MsgType.HEARTBEAT.equals(msgType)) {
                LOG.trace("Sending Heartbeat");
            } else if (MsgType.LOGON.equals(msgType)) {
                LOG.info("Sending Logon response");
                addSessionEvent("LOGON_SENT", "Logon response sent");
            } else if (MsgType.LOGOUT.equals(msgType)) {
                LOG.info("Sending Logout");
                addSessionEvent("LOGOUT_SENT", "Logout sent");
            } else if (MsgType.REJECT.equals(msgType)) {
                LOG.warn("Sending Session Reject: {}", message);
                addSessionEvent("SESSION_REJECT_SENT", "Session reject sent");
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
            LOG.info("Received Logon request from {}", sessionID.getTargetCompID());
            addSessionEvent("LOGON_RECEIVED", "Logon request from " + sessionID.getTargetCompID());
            // Validate logon credentials if needed
            validateLogon(message, sessionID);
        } else if (MsgType.LOGOUT.equals(msgType)) {
            LOG.info("Received Logout from {}", sessionID.getTargetCompID());
            addSessionEvent("LOGOUT_RECEIVED", "Logout from " + sessionID.getTargetCompID());
        } else if (MsgType.HEARTBEAT.equals(msgType)) {
            LOG.trace("Received Heartbeat");
        } else if (MsgType.TEST_REQUEST.equals(msgType)) {
            LOG.debug("Received Test Request");
            addSessionEvent("TEST_REQUEST", "Test request received");
        } else if (MsgType.RESEND_REQUEST.equals(msgType)) {
            LOG.info("Received Resend Request");
            addSessionEvent("RESEND_REQUEST", "Resend request received");
        } else if (MsgType.SEQUENCE_RESET.equals(msgType)) {
            LOG.info("Received Sequence Reset");
            addSessionEvent("SEQUENCE_RESET", "Sequence reset received");
        }
    }

    @Override
    public void toApp(Message message, SessionID sessionID) throws DoNotSend {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            LOG.debug("toApp - Sending MsgType: {}", msgType);
            
            // Record FIX message sent
            if (telemetryService != null) {
                telemetryService.recordFixMessageSent();
            }
        } catch (FieldNotFound e) {
            LOG.debug("toApp - cannot get MsgType");
        }
    }

    @Override
    public void fromApp(Message message, SessionID sessionID)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        long startTime = System.nanoTime();
        String msgType = message.getHeader().getString(MsgType.FIELD);
        LOG.info("fromApp - Received MsgType: {} from {}", msgType, sessionID.getTargetCompID());
        
        // Record FIX message received
        if (telemetryService != null) {
            telemetryService.recordFixMessageReceived();
        }

        if (MsgType.ORDER_SINGLE.equals(msgType)) {
            LOG.info("Dispatching generic NewOrderSingle to async gateway worker");
            asyncExecutor.submit(() -> processNewOrderSingleAsync(message, sessionID));
            return;
        }

        if (MsgType.ORDER_CANCEL_REQUEST.equals(msgType)) {
            LOG.info("Processing generic OrderCancelRequest");
            processOrderCancelRequest(message, sessionID);
            return;
        }

        if (MsgType.ORDER_CANCEL_REPLACE_REQUEST.equals(msgType)) {
            LOG.info("Processing generic OrderCancelReplaceRequest");
            processOrderCancelReplaceRequest(message, sessionID);
            return;
        }
        
        try {
            crack(message, sessionID);
            
            // Record message lag
            if (telemetryService != null) {
                long lagNanos = System.nanoTime() - startTime;
                telemetryService.recordFixMessageLag(lagNanos);
            }
        } catch (UnsupportedMessageType e) {
            LOG.warn("Unsupported message type: {}", msgType);
            if (telemetryService != null) {
                telemetryService.recordFixMessageRejected();
            }
            sendBusinessReject(message, sessionID, BusinessRejectReason.UNSUPPORTED_MESSAGE_TYPE, 
                    "Message type " + msgType + " not supported");
            throw e;
        }
    }

    /**
     * Handle NewOrderSingle (MsgType=D)
     */
    public void onMessage(NewOrderSingle newOrderSingle, SessionID sessionID)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        LOG.info("Dispatching NewOrderSingle to async gateway worker");
        asyncExecutor.submit(() -> processNewOrderSingleAsync(newOrderSingle, sessionID));
    }

    private void processNewOrderSingleAsync(NewOrderSingle newOrderSingle, SessionID sessionID) {
        long orderStartTime = System.nanoTime();

        if (telemetryService != null) {
            telemetryService.recordOrderReceived();
        }

        try {
            QuickFixJOrderIntakeEngine.IntakeResult intakeResult = quickFixJOrderIntakeEngine.intake(newOrderSingle, sessionID);
            routeIntakeResult(intakeResult, sessionID, orderStartTime);
        } catch (Exception e) {
            LOG.error("Error processing NewOrderSingle asynchronously", e);
            if (telemetryService != null) {
                telemetryService.recordError();
            }
            try {
                String clOrdId = newOrderSingle.getClOrdID().getValue();
                sendRejectExecutionReport(clOrdId, "UNKNOWN", Side.BUY, 0, OrdRejReason.OTHER,
                        "Internal error: " + e.getMessage(), sessionID);
            } catch (FieldNotFound ex) {
                LOG.error("Cannot extract ClOrdID for reject", ex);
            }
        }
    }

    private void processNewOrderSingleAsync(Message newOrderSingle, SessionID sessionID) {
        long orderStartTime = System.nanoTime();

        if (telemetryService != null) {
            telemetryService.recordOrderReceived();
        }

        try {
            QuickFixJOrderIntakeEngine.IntakeResult intakeResult = quickFixJOrderIntakeEngine.intake(newOrderSingle, sessionID);
            routeIntakeResult(intakeResult, sessionID, orderStartTime);
        } catch (Exception e) {
            LOG.error("Error processing generic NewOrderSingle asynchronously", e);
            if (telemetryService != null) {
                telemetryService.recordError();
            }
            try {
                String clOrdId = newOrderSingle.getString(ClOrdID.FIELD);
                sendRejectExecutionReport(clOrdId, "UNKNOWN", Side.BUY, 0, OrdRejReason.OTHER,
                        "Internal error: " + e.getMessage(), sessionID);
            } catch (FieldNotFound ex) {
                LOG.error("Cannot extract ClOrdID for reject", ex);
            }
        }
    }

    private void processOrderCancelRequest(Message orderCancelRequest, SessionID sessionID) {
        try {
            String origClOrdId = orderCancelRequest.getString(OrigClOrdID.FIELD);
            String clOrdId = orderCancelRequest.getString(ClOrdID.FIELD);

            FixOrderManagementEngine.CancelResult cancelResult = fixOrderManagementEngine.cancel(origClOrdId);
            if (cancelResult.notFound) {
                LOG.warn("Order not found for cancel: {}", origClOrdId);
                sendCancelReject(clOrdId, origClOrdId, "UNKNOWN", CxlRejReason.UNKNOWN_ORDER,
                        cancelResult.reason, sessionID);
                return;
            }

            if (cancelResult.tooLate) {
                LOG.warn("Cannot cancel order in status: {}", cancelResult.order.getStatus());
                sendCancelReject(clOrdId, origClOrdId, cancelResult.order.getOrderRefNumber(),
                        CxlRejReason.TOO_LATE_TO_CANCEL, cancelResult.reason, sessionID);
                return;
            }

            if (cancelResult.pending) {
                sendCancelReject(clOrdId, origClOrdId, cancelResult.order.getOrderRefNumber(),
                        CxlRejReason.ORDER_ALREADY_IN_PENDING_CANCEL_OR_PENDING_REPLACE_STATUS,
                        cancelResult.reason, sessionID);
                return;
            }

            LOG.info("Order canceled: {}", origClOrdId);
            addSessionEvent("ORDER_CANCELED", "Order " + origClOrdId + " canceled");
            executionReportService.sendCancel(cancelResult.order, sessionID);
        } catch (Exception e) {
            LOG.error("Error processing generic OrderCancelRequest", e);
        }
    }

    private void routeIntakeResult(QuickFixJOrderIntakeEngine.IntakeResult intakeResult,
                                   SessionID sessionID,
                                   long orderStartTime) throws SessionNotFound {
        if (!intakeResult.accepted) {
            LOG.warn("QuickFIX/J order intake rejected {}: {}", intakeResult.clOrdId, intakeResult.rejectReason);
            if (telemetryService != null) {
                telemetryService.recordOrderRejected();
            }
            sendRejectExecutionReport(
                    intakeResult.clOrdId,
                    intakeResult.symbol,
                    intakeResult.side,
                    intakeResult.quantity,
                    OrdRejReason.OTHER,
                    intakeResult.rejectReason,
                    sessionID);
            return;
        }

        OrderEntity order = intakeResult.order;
        LOG.info("Order validated and persisted: {} -> {}", intakeResult.clOrdId, order.getOrderRefNumber());
        addSessionEvent("ORDER_RECEIVED", "Order " + intakeResult.clOrdId + " received and validated");

        if (telemetryService != null) {
            long processingTime = System.nanoTime() - orderStartTime;
            telemetryService.recordOrderProcessed(processingTime);
        }

        executionReportService.sendAck(order, sessionID);
        fixOrderManagementEngine.processOrderMatch(order, sessionID);
    }

    /**
     * Handle OrderCancelRequest (MsgType=F)
     */
    public void onMessage(OrderCancelRequest orderCancelRequest, SessionID sessionID)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        LOG.info("Processing OrderCancelRequest");
        
        try {
            String origClOrdId = orderCancelRequest.getOrigClOrdID().getValue();
            String clOrdId = orderCancelRequest.getClOrdID().getValue();

            FixOrderManagementEngine.CancelResult cancelResult = fixOrderManagementEngine.cancel(origClOrdId);
            if (cancelResult.notFound) {
                LOG.warn("Order not found for cancel: {}", origClOrdId);
                sendCancelReject(clOrdId, origClOrdId, "UNKNOWN", CxlRejReason.UNKNOWN_ORDER,
                        cancelResult.reason, sessionID);
                return;
            }

            if (cancelResult.tooLate) {
                LOG.warn("Cannot cancel order in status: {}", cancelResult.order.getStatus());
                sendCancelReject(clOrdId, origClOrdId, cancelResult.order.getOrderRefNumber(),
                        CxlRejReason.TOO_LATE_TO_CANCEL, cancelResult.reason, sessionID);
                return;
            }

            if (cancelResult.pending) {
                sendCancelReject(clOrdId, origClOrdId, cancelResult.order.getOrderRefNumber(),
                        CxlRejReason.ORDER_ALREADY_IN_PENDING_CANCEL_OR_PENDING_REPLACE_STATUS,
                        cancelResult.reason, sessionID);
                return;
            }
            
            LOG.info("Order canceled: {}", origClOrdId);
            addSessionEvent("ORDER_CANCELED", "Order " + origClOrdId + " canceled");
            
            // Send CANCELED execution report
            executionReportService.sendCancel(cancelResult.order, sessionID);
            
        } catch (Exception e) {
            LOG.error("Error processing OrderCancelRequest", e);
        }
    }

    private void processOrderCancelReplaceRequest(Message replaceRequest, SessionID sessionID) {
        try {
            String origClOrdId = replaceRequest.getString(OrigClOrdID.FIELD);
            String clOrdId = replaceRequest.getString(ClOrdID.FIELD);

            Double newQty = null;
            Double newPrice = null;
            try {
                newQty = replaceRequest.getDouble(OrderQty.FIELD);
            } catch (FieldNotFound ignored) {
                // Keep original qty
            }
            try {
                newPrice = replaceRequest.getDouble(Price.FIELD);
            } catch (FieldNotFound ignored) {
                // Keep original price
            }

            FixOrderManagementEngine.ReplaceResult replaceResult =
                    fixOrderManagementEngine.replace(origClOrdId, clOrdId, newQty, newPrice);

            if (replaceResult.notFound) {
                LOG.warn("Order not found for replace: {}", origClOrdId);
                sendCancelReject(clOrdId, origClOrdId, "UNKNOWN", CxlRejReason.UNKNOWN_ORDER,
                        "Order not found", sessionID);
                return;
            }

            if (replaceResult.tooLate) {
                sendCancelReject(clOrdId, origClOrdId, replaceResult.order.getOrderRefNumber(),
                        CxlRejReason.TOO_LATE_TO_CANCEL, replaceResult.reason, sessionID);
                return;
            }

            LOG.info("Order replaced: {} -> {}", origClOrdId, clOrdId);
            addSessionEvent("ORDER_REPLACED", "Order " + origClOrdId + " replaced with " + clOrdId);

            executionReportService.sendReplace(replaceResult.order, origClOrdId, sessionID);
            fixOrderManagementEngine.processOrderMatch(replaceResult.order, sessionID);

        } catch (Exception e) {
            LOG.error("Error processing OrderCancelReplaceRequest", e);
        }
    }

    /**
     * Handle OrderCancelReplaceRequest (MsgType=G)
     */
    public void onMessage(OrderCancelReplaceRequest replaceRequest, SessionID sessionID)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        LOG.info("Processing OrderCancelReplaceRequest");
        
        try {
            String origClOrdId = replaceRequest.getOrigClOrdID().getValue();
            String clOrdId = replaceRequest.getClOrdID().getValue();

            Double newQty = null;
            Double newPrice = null;
            try {
                newQty = replaceRequest.getOrderQty().getValue();
            } catch (FieldNotFound ignored) {
                // Keep original qty
            }
            try {
                newPrice = replaceRequest.getPrice().getValue();
            } catch (FieldNotFound ignored) {
                // Keep original price
            }

            FixOrderManagementEngine.ReplaceResult replaceResult =
                    fixOrderManagementEngine.replace(origClOrdId, clOrdId, newQty, newPrice);

            if (replaceResult.notFound) {
                LOG.warn("Order not found for replace: {}", origClOrdId);
                sendCancelReject(clOrdId, origClOrdId, "UNKNOWN", CxlRejReason.UNKNOWN_ORDER,
                        "Order not found", sessionID);
                return;
            }

            if (replaceResult.tooLate) {
                sendCancelReject(clOrdId, origClOrdId, replaceResult.order.getOrderRefNumber(),
                        CxlRejReason.TOO_LATE_TO_CANCEL, replaceResult.reason, sessionID);
                return;
            }
            
            LOG.info("Order replaced: {} -> {}", origClOrdId, clOrdId);
            addSessionEvent("ORDER_REPLACED", "Order " + origClOrdId + " replaced with " + clOrdId);
            
            // Send REPLACED execution report
            executionReportService.sendReplace(replaceResult.order, origClOrdId, sessionID);
            
            // Re-process through matching
            fixOrderManagementEngine.processOrderMatch(replaceResult.order, sessionID);
            
        } catch (Exception e) {
            LOG.error("Error processing OrderCancelReplaceRequest", e);
        }
    }

    /**
     * Handle OrderStatusRequest (MsgType=H)
     */
    public void onMessage(OrderStatusRequest statusRequest, SessionID sessionID)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        LOG.info("Processing OrderStatusRequest");
        
        try {
            String clOrdId = statusRequest.getClOrdID().getValue();
            OrderEntity order = orderDao.findByClOrdId(clOrdId);
            
            if (order == null) {
                LOG.warn("Order not found for status request: {}", clOrdId);
                // Send business reject
                sendBusinessReject(statusRequest, sessionID, BusinessRejectReason.UNKNOWN_ID,
                        "Order not found: " + clOrdId);
                return;
            }
            
            // Send order status
            executionReportService.sendOrderStatus(order, sessionID);
            
        } catch (Exception e) {
            LOG.error("Error processing OrderStatusRequest", e);
        }
    }

    // Helper methods for message processing

    private void validateLogon(Message message, SessionID sessionID) throws RejectLogon {
        // Add custom logon validation here if needed
        // For example, validate credentials from RawData field
        try {
            if (message.isSetField(RawData.FIELD)) {
                String password = message.getString(RawData.FIELD);
                LOG.debug("Logon with password provided");
                // Validate password here if needed
            }
        } catch (FieldNotFound e) {
            // No password field - continue
        }
    }

    private void sendRejectExecutionReport(String clOrdId, String symbol, char side, double qty,
            int rejectReason, String text, SessionID sessionID) {
        try {
            ExecutionReport reject = new ExecutionReport();
            reject.set(new OrderID("NONE"));
            reject.set(new ExecID("REJ-" + System.currentTimeMillis()));
            reject.set(new ExecType(ExecType.REJECTED));
            reject.set(new OrdStatus(OrdStatus.REJECTED));
            reject.set(new Symbol(symbol));
            reject.set(new Side(side));
            reject.set(new LeavesQty(0));
            reject.set(new CumQty(0));
            reject.set(new AvgPx(0));
            reject.set(new ClOrdID(clOrdId));
            reject.set(new OrdRejReason(rejectReason));
            reject.set(new Text(text));
            
            if (qty > 0) {
                reject.set(new OrderQty(qty));
            }
            
            Session.sendToTarget(reject, sessionID);
            LOG.info("Sent reject for order {}: {}", clOrdId, text);
            addSessionEvent("ORDER_REJECTED", "Order " + clOrdId + " rejected: " + text);
            
        } catch (SessionNotFound e) {
            LOG.error("Cannot send reject - session not found", e);
        }
    }

    private void sendCancelReject(String clOrdId, String origClOrdId, String orderId, 
            int rejectReason, String text, SessionID sessionID) {
        try {
            OrderCancelReject reject = new OrderCancelReject();
            reject.set(new OrderID(orderId != null ? orderId : "NONE"));
            reject.set(new ClOrdID(clOrdId));
            reject.set(new OrigClOrdID(origClOrdId));
            reject.set(new OrdStatus(OrdStatus.REJECTED));
            reject.set(new CxlRejResponseTo(CxlRejResponseTo.ORDER_CANCEL_REQUEST));
            reject.set(new CxlRejReason(rejectReason));
            reject.set(new Text(text));
            
            Session.sendToTarget(reject, sessionID);
            LOG.info("Sent cancel reject for order {}: {}", origClOrdId, text);
            addSessionEvent("CANCEL_REJECTED", "Cancel rejected for " + origClOrdId + ": " + text);
            
        } catch (SessionNotFound e) {
            LOG.error("Cannot send cancel reject - session not found", e);
        }
    }

    private void sendBusinessReject(Message refMessage, SessionID sessionID, 
            int rejectReason, String text) {
        try {
            BusinessMessageReject reject = new BusinessMessageReject();
            reject.set(new RefMsgType(refMessage.getHeader().getString(MsgType.FIELD)));
            reject.set(new BusinessRejectReason(rejectReason));
            reject.set(new Text(text));
            
            try {
                reject.set(new RefSeqNum(refMessage.getHeader().getInt(MsgSeqNum.FIELD)));
            } catch (FieldNotFound e) {
                // Skip RefSeqNum
            }
            
            Session.sendToTarget(reject, sessionID);
            LOG.warn("Sent business reject: {}", text);
            addSessionEvent("BUSINESS_REJECT", text);
            
        } catch (Exception e) {
            LOG.error("Cannot send business reject", e);
        }
    }

    // Session event tracking

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
