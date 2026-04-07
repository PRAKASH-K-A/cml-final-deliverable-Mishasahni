package com.helesto.service;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.dao.OrderDao;
import com.helesto.model.OrderEntity;

import quickfix.FieldNotFound;
import quickfix.SessionID;
import quickfix.field.*;
import quickfix.fix44.ExecutionReport;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@ApplicationScoped
public class ExecutionReportService {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionReportService.class);

    @Inject
    OrderDao orderDao;

    // Execution event tracking for WebSocket streaming
    private final List<ExecutionEvent> executionEvents = new CopyOnWriteArrayList<>();
    private final List<Consumer<ExecutionEvent>> executionListeners = new CopyOnWriteArrayList<>();

    public ExecutionReportService() {
        LOG.info("ExecutionReportService Constructor");
    }

    @Transactional
    public void processExecutionReport(ExecutionReport executionReport, SessionID sessionID) throws FieldNotFound {
        LOG.info("Processing ExecutionReport");

        try {
            String clOrdID = executionReport.getString(ClOrdID.FIELD);
            String orderID = executionReport.getString(OrderID.FIELD);
            char ordStatus = executionReport.getChar(OrdStatus.FIELD);
            char execType = executionReport.getChar(ExecType.FIELD);

            LOG.info("ExecutionReport - ClOrdID: {}, OrderID: {}, Status: {}, ExecType: {}", 
                     clOrdID, orderID, ordStatus, execType);

            // Find and update order in database
            OrderEntity order = orderDao.findByClOrdId(clOrdID);
            if (order != null) {
                // Update order status based on OrdStatus
                String newStatus = mapOrdStatus(ordStatus);
                order.setStatus(newStatus);
                order.setOrderRefNumber(orderID);
                
                // Update fill information
                double cumQty = 0;
                double avgPx = 0;
                double lastQty = 0;
                double lastPx = 0;
                
                try {
                    cumQty = executionReport.getDouble(CumQty.FIELD);
                    order.setFilledQty((long) cumQty);
                } catch (FieldNotFound e) {
                    // CumQty not present
                }
                
                try {
                    avgPx = executionReport.getDouble(AvgPx.FIELD);
                    order.setAvgPrice(avgPx);
                } catch (FieldNotFound e) {
                    // AvgPx not present
                }
                
                try {
                    lastQty = executionReport.getDouble(LastQty.FIELD);
                } catch (FieldNotFound e) {
                    // LastQty not present
                }
                
                try {
                    lastPx = executionReport.getDouble(LastPx.FIELD);
                } catch (FieldNotFound e) {
                    // LastPx not present
                }
                
                // Get leaves quantity if present
                try {
                    double leavesQty = executionReport.getDouble(LeavesQty.FIELD);
                    order.setLeavesQty((long) leavesQty);
                } catch (FieldNotFound e) {
                    // LeavesQty not present - calculate it
                    order.setLeavesQty(order.getQuantity() - order.getFilledQty());
                }
                
                // Get reject reason if present
                String text = "";
                try {
                    text = executionReport.getString(Text.FIELD);
                    if (ordStatus == OrdStatus.REJECTED) {
                        order.setRejectReason(text);
                    }
                } catch (FieldNotFound e) {
                    // Text not present
                }
                
                orderDao.updateOrder(order);
                LOG.info("Order updated: {} -> status={}, filledQty={}, leavesQty={}", 
                         clOrdID, order.getStatus(), order.getFilledQty(), order.getLeavesQty());
                
                // Notify listeners
                ExecutionEvent event = new ExecutionEvent(
                    clOrdID, orderID, mapExecType(execType), newStatus,
                    lastQty, lastPx, cumQty, avgPx, text
                );
                notifyExecutionListeners(event);
                
            } else {
                LOG.warn("Order not found for ClOrdID: {}", clOrdID);
            }

        } catch (FieldNotFound e) {
            LOG.error("Required field not found in ExecutionReport", e);
            throw e;
        }
    }

    /**
     * Process a cancel reject from the exchange
     */
    @Transactional
    public void processCancelReject(String origClOrdId, String reason) {
        LOG.info("Processing Cancel Reject for OrigClOrdID: {}, Reason: {}", origClOrdId, reason);
        
        OrderEntity order = orderDao.findByClOrdId(origClOrdId);
        if (order != null) {
            // Revert any pending cancel status
            if ("PENDING_CANCEL".equals(order.getStatus())) {
                // Restore to previous status if known, otherwise set to NEW
                String prevStatus = order.getPreviousStatus();
                order.setStatus(prevStatus != null ? prevStatus : "NEW");
            }
            order.setRejectReason("Cancel rejected: " + reason);
            orderDao.updateOrder(order);
            LOG.info("Order cancel reject processed: {} - status restored", origClOrdId);
            
            // Notify listeners
            ExecutionEvent event = new ExecutionEvent(
                origClOrdId, order.getOrderRefNumber(), "CANCEL_REJECT", order.getStatus(),
                0, 0, order.getFilledQty(), order.getAvgPrice(), reason
            );
            notifyExecutionListeners(event);
        } else {
            LOG.warn("Order not found for cancel reject: {}", origClOrdId);
        }
    }

    /**
     * Process a replace reject from the exchange
     */
    @Transactional
    public void processReplaceReject(String origClOrdId, String reason) {
        LOG.info("Processing Replace Reject for OrigClOrdID: {}, Reason: {}", origClOrdId, reason);
        
        OrderEntity order = orderDao.findByClOrdId(origClOrdId);
        if (order != null) {
            // Revert any pending replace status
            if ("PENDING_REPLACE".equals(order.getStatus())) {
                String prevStatus = order.getPreviousStatus();
                order.setStatus(prevStatus != null ? prevStatus : "NEW");
            }
            order.setRejectReason("Replace rejected: " + reason);
            orderDao.updateOrder(order);
            LOG.info("Order replace reject processed: {} - status restored", origClOrdId);
        } else {
            LOG.warn("Order not found for replace reject: {}", origClOrdId);
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
            default: return "UNKNOWN";
        }
    }

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
            default: return "UNKNOWN";
        }
    }

    // Event listener management

    public void addExecutionListener(Consumer<ExecutionEvent> listener) {
        executionListeners.add(listener);
    }

    public void removeExecutionListener(Consumer<ExecutionEvent> listener) {
        executionListeners.remove(listener);
    }

    private void notifyExecutionListeners(ExecutionEvent event) {
        executionEvents.add(event);
        
        // Keep only last 1000 events
        while (executionEvents.size() > 1000) {
            executionEvents.remove(0);
        }
        
        for (Consumer<ExecutionEvent> listener : executionListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                LOG.error("Error notifying execution listener", e);
            }
        }
    }

    public List<ExecutionEvent> getRecentExecutions(int count) {
        int size = executionEvents.size();
        int start = Math.max(0, size - count);
        return new ArrayList<>(executionEvents.subList(start, size));
    }

    // Execution event class for WebSocket streaming
    public static class ExecutionEvent {
        public final String clOrdId;
        public final String orderId;
        public final String execType;
        public final String ordStatus;
        public final double lastQty;
        public final double lastPx;
        public final double cumQty;
        public final double avgPx;
        public final String text;
        public final LocalDateTime timestamp;

        public ExecutionEvent(String clOrdId, String orderId, String execType, String ordStatus,
                            double lastQty, double lastPx, double cumQty, double avgPx, String text) {
            this.clOrdId = clOrdId;
            this.orderId = orderId;
            this.execType = execType;
            this.ordStatus = ordStatus;
            this.lastQty = lastQty;
            this.lastPx = lastPx;
            this.cumQty = cumQty;
            this.avgPx = avgPx;
            this.text = text;
            this.timestamp = LocalDateTime.now();
        }
    }
}
