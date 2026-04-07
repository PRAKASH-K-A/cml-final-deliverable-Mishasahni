package com.helesto.service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.core.Exchange;
import com.helesto.dao.OrderDao;
import com.helesto.model.OrderEntity;

import quickfix.FieldNotFound;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.OrdRejReason;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.SecondaryExecID;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TimeInForce;
import quickfix.field.TransactTime;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelRequest;

@ApplicationScoped
public class ExecutionReportService {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionReportService.class.getName());
    private static final AtomicLong execIdCounter = new AtomicLong(System.currentTimeMillis());
    private static final AtomicLong orderIdCounter = new AtomicLong(System.currentTimeMillis());

    @Inject
    Exchange exchange;

    @Inject
    OrderDao orderDao;

    /**
     * Send NEW acknowledgment execution report
     */
    public void sendAck(OrderEntity order, SessionID sessionID) throws SessionNotFound {
        LOG.info("Sending NEW ack for order: {}", order.getClOrdId());
        
        ExecutionReport report = createBaseReport(order);
        report.set(new ExecType(ExecType.NEW));
        report.set(new OrdStatus(OrdStatus.NEW));
        report.set(new LastQty(0));
        report.set(new LastPx(0));
        
        Session.sendToTarget(report, sessionID);
        LOG.info("NEW ack sent for order: {}", order.getClOrdId());
    }

    /**
     * Send FILL execution report
     */
    public void sendFill(OrderEntity order, MatchingEngine.Fill fill, SessionID sessionID) throws SessionNotFound {
        LOG.info("Sending FILL for order: {} qty={} @ {}", order.getClOrdId(), fill.quantity, fill.price);
        
        ExecutionReport report = createBaseReport(order);
        
        boolean partialFill = order.getLeavesQty() > 0;
        report.set(new ExecType(ExecType.TRADE));
        report.set(new OrdStatus(partialFill ? OrdStatus.PARTIALLY_FILLED : OrdStatus.FILLED));
        report.set(new ExecID(fill.execId));
        report.set(new LastQty(fill.quantity));
        report.set(new LastPx(fill.price));
        
        // Add trade details
        if (fill.tradeId != null) {
            report.set(new SecondaryExecID(fill.tradeId));
        }
        
        Session.sendToTarget(report, sessionID);
        LOG.info("FILL sent for order: {} - {} @ {}", order.getClOrdId(), fill.quantity, fill.price);
    }

    /**
     * Send CANCELED execution report
     */
    public void sendCancel(OrderEntity order, SessionID sessionID) throws SessionNotFound {
        LOG.info("Sending CANCELED for order: {}", order.getClOrdId());
        
        ExecutionReport report = createBaseReport(order);
        report.set(new ExecType(ExecType.CANCELED));
        report.set(new OrdStatus(OrdStatus.CANCELED));
        report.set(new CumQty(order.getFilledQty() != null ? order.getFilledQty() : 0L));
        report.set(new LeavesQty(0));
        report.set(new LastQty(0));
        report.set(new LastPx(0));
        
        Session.sendToTarget(report, sessionID);
        LOG.info("CANCELED sent for order: {}", order.getClOrdId());
    }

    /**
     * Send REPLACED execution report
     */
    public void sendReplace(OrderEntity order, String origClOrdId, SessionID sessionID) throws SessionNotFound {
        LOG.info("Sending REPLACED for order: {} (orig: {})", order.getClOrdId(), origClOrdId);
        
        ExecutionReport report = createBaseReport(order);
        report.set(new ExecType(ExecType.REPLACED));
        report.set(new OrdStatus(OrdStatus.REPLACED));
        report.set(new OrigClOrdID(origClOrdId));
        report.set(new LastQty(0));
        report.set(new LastPx(0));
        
        Session.sendToTarget(report, sessionID);
        LOG.info("REPLACED sent for order: {}", order.getClOrdId());
    }

    /**
     * Send ORDER_STATUS execution report
     */
    public void sendOrderStatus(OrderEntity order, SessionID sessionID) throws SessionNotFound {
        LOG.info("Sending ORDER_STATUS for order: {}", order.getClOrdId());
        
        ExecutionReport report = createBaseReport(order);
        report.set(new ExecType(ExecType.ORDER_STATUS));
        report.set(new OrdStatus(mapStatus(order.getStatus())));
        report.set(new LastQty(0));
        report.set(new LastPx(0));
        
        Session.sendToTarget(report, sessionID);
        LOG.info("ORDER_STATUS sent for order: {}", order.getClOrdId());
    }

    /**
     * Send REJECTED execution report
     */
    public void sendReject(OrderEntity order, int rejectReason, String rejectText, SessionID sessionID) throws SessionNotFound {
        LOG.info("Sending REJECTED for order: {} - {}", order.getClOrdId(), rejectText);
        
        ExecutionReport report = createBaseReport(order);
        report.set(new ExecType(ExecType.REJECTED));
        report.set(new OrdStatus(OrdStatus.REJECTED));
        report.set(new OrdRejReason(rejectReason));
        report.set(new Text(rejectText));
        report.set(new LastQty(0));
        report.set(new LastPx(0));
        
        Session.sendToTarget(report, sessionID);
        LOG.info("REJECTED sent for order: {}", order.getClOrdId());
    }

    /**
     * Process contra fill - update contra order and send fill report
     * This method handles the transactional update of the contra (resting) order
     */
    @Transactional
    public void processContraFill(MatchingEngine.Fill fill, SessionID sessionID) {
        try {
            // Find the contra order by orderId
            OrderEntity contraOrder = orderDao.findByOrderRefNumber(fill.contraOrderId);
            if (contraOrder == null) {
                LOG.warn("Contra order not found for fill: {}", fill.contraOrderId);
                return;
            }
            
            // Update contra order's fill quantities
            long currentFilled = contraOrder.getFilledQty() != null ? contraOrder.getFilledQty() : 0;
            long newFilled = currentFilled + fill.quantity;
            contraOrder.setFilledQty(newFilled);
            
            long currentLeaves = contraOrder.getLeavesQty() != null ? contraOrder.getLeavesQty() : contraOrder.getQuantity();
            long newLeaves = currentLeaves - fill.quantity;
            contraOrder.setLeavesQty(newLeaves);
            
            // Calculate new average price
            double currentAvg = contraOrder.getAvgPrice() != null ? contraOrder.getAvgPrice() : 0;
            double newAvg = (currentAvg * currentFilled + fill.price * fill.quantity) / newFilled;
            contraOrder.setAvgPrice(newAvg);
            
            // Update status
            if (newLeaves <= 0) {
                contraOrder.setStatus("FILLED");
            } else {
                contraOrder.setStatus("PARTIALLY_FILLED");
            }
            
            contraOrder.setUpdatedAt(java.time.LocalDateTime.now());
            orderDao.updateOrder(contraOrder);
            
            LOG.info("Contra order {} updated - filled {} @ {}, status={}", 
                    contraOrder.getClOrdId(), fill.quantity, fill.price, contraOrder.getStatus());
            
            // For most simulator scenarios both sides are on the same FIX session.
            if (sessionID != null) {
                try {
                    sendFill(contraOrder, fill, sessionID);
                    return;
                } catch (SessionNotFound e) {
                    LOG.debug("Active session not found for contra fill, trying derived session IDs");
                }
            }

            if (contraOrder.getSenderCompId() != null && contraOrder.getTargetCompId() != null) {
                String beginString = sessionID != null ? sessionID.getBeginString() : "FIX.4.4";
                SessionID primary = new SessionID(beginString, contraOrder.getSenderCompId(), contraOrder.getTargetCompId());
                SessionID fallback = new SessionID(beginString, contraOrder.getTargetCompId(), contraOrder.getSenderCompId());

                try {
                    sendFill(contraOrder, fill, primary);
                    return;
                } catch (SessionNotFound ignored) {
                    // Try alternate direction next.
                }

                try {
                    sendFill(contraOrder, fill, fallback);
                } catch (SessionNotFound e) {
                    LOG.warn("Session not found for contra order {}, skipping FIX message", contraOrder.getClOrdId());
                }
            } else {
                LOG.debug("Contra order {} has no FIX session info, skipping FIX message", contraOrder.getClOrdId());
            }
                    
        } catch (Exception e) {
            LOG.error("Error processing contra fill for {}: {}", fill.contraOrderId, e.getMessage(), e);
        }
    }

    /**
     * Create base execution report with common fields
     */
    private ExecutionReport createBaseReport(OrderEntity order) {
        ExecutionReport report = new ExecutionReport();
        
        // Required fields
        String orderId = order.getOrderRefNumber();
        if (orderId == null || orderId.isEmpty()) {
            orderId = "ORD-" + orderIdCounter.incrementAndGet();
        }
        
        report.set(new OrderID(orderId));
        report.set(new ExecID("EXEC-" + execIdCounter.incrementAndGet()));
        report.set(new ClOrdID(order.getClOrdId()));
        report.set(new Symbol(order.getSymbol()));
        report.set(new Side(parseSide(order.getSide())));
        
        // Quantity fields
        long orderQty = order.getQuantity() != null ? order.getQuantity() : 0L;
        long filledQty = order.getFilledQty() != null ? order.getFilledQty() : 0L;
        long leavesQty = order.getLeavesQty() != null ? order.getLeavesQty() : (orderQty - filledQty);
        double avgPx = order.getAvgPrice() != null ? order.getAvgPrice() : 0.0;
        
        report.set(new OrderQty(orderQty));
        report.set(new LeavesQty(leavesQty));
        report.set(new CumQty(filledQty));
        report.set(new AvgPx(avgPx));
        
        // Optional fields
        if (order.getPrice() != null && order.getPrice() > 0) {
            report.set(new Price(order.getPrice()));
        }
        
        if (order.getOrderType() != null) {
            report.set(new OrdType(mapOrdType(order.getOrderType())));
        }
        
        if (order.getTimeInForce() != null) {
            report.set(new TimeInForce(mapTimeInForce(order.getTimeInForce())));
        }
        
        report.set(new TransactTime());
        
        return report;
    }

    private char parseSide(String side) {
        if (side == null) return Side.BUY;
        if ("2".equals(side) || "SELL".equalsIgnoreCase(side)) return Side.SELL;
        return Side.BUY;
    }

    private char mapOrdType(String orderType) {
        if (orderType == null) return OrdType.LIMIT;
        switch (orderType.toUpperCase()) {
            case "MARKET": return OrdType.MARKET;
            case "STOP": return '3'; // FIX Stop order type
            case "STOP_LIMIT": return OrdType.STOP_LIMIT;
            default: return OrdType.LIMIT;
        }
    }

    private char mapTimeInForce(String tif) {
        if (tif == null) return TimeInForce.DAY;
        switch (tif.toUpperCase()) {
            case "GTC": return TimeInForce.GOOD_TILL_CANCEL;
            case "IOC": return TimeInForce.IMMEDIATE_OR_CANCEL;
            case "FOK": return TimeInForce.FILL_OR_KILL;
            case "GTD": return TimeInForce.GOOD_TILL_DATE;
            default: return TimeInForce.DAY;
        }
    }

    private char mapStatus(String status) {
        if (status == null) return OrdStatus.NEW;
        switch (status.toUpperCase()) {
            case "NEW": return OrdStatus.NEW;
            case "PARTIALLY_FILLED": return OrdStatus.PARTIALLY_FILLED;
            case "FILLED": return OrdStatus.FILLED;
            case "CANCELED": return OrdStatus.CANCELED;
            case "REJECTED": return OrdStatus.REJECTED;
            case "PENDING_NEW": return OrdStatus.PENDING_NEW;
            case "PENDING_CANCEL": return OrdStatus.PENDING_CANCEL;
            case "PENDING_REPLACE": return OrdStatus.PENDING_REPLACE;
            case "EXPIRED": return OrdStatus.EXPIRED;
            default: return OrdStatus.NEW;
        }
    }

    // Legacy methods for backward compatibility

    @Transactional
    public void executionReport(NewOrderSingle newOrderSingle, SessionID sessionID) throws FieldNotFound, SessionNotFound {
        LOG.info("Legacy: Processing NewOrderSingle from FIX");
        
        String clOrdId = newOrderSingle.getClOrdID().getValue();
        String symbol = newOrderSingle.getSymbol().getValue();
        char side = newOrderSingle.getSide().getValue();
        double qty = newOrderSingle.getOrderQty().getValue();
        double price = 0;
        try {
            price = newOrderSingle.getPrice().getValue();
        } catch (FieldNotFound e) {
            // Market order - no price
        }
        
        // Generate unique order ID
        String orderID = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        
        // Persist order in Exchange database
        OrderEntity order = new OrderEntity();
        order.setClOrdId(clOrdId);
        order.setOrderRefNumber(orderID);
        order.setSymbol(symbol);
        order.setSide(String.valueOf(side));
        order.setQuantity((long) qty);
        order.setPrice(price);
        order.setStatus("NEW");
        order.setFilledQty(0L);
        order.setLeavesQty((long) qty);
        order.setCreatedAt(LocalDateTime.now());
        orderDao.persistOrder(order);
        
        LOG.info("Order persisted: {} -> {}", clOrdId, orderID);
        
        // Send NEW execution report
        sendAck(order, sessionID);
    }

    @Transactional
    public void executionReport(OrderCancelRequest orderCancelRequest, SessionID sessionID) throws FieldNotFound, SessionNotFound {
        LOG.info("Legacy: Processing OrderCancelRequest from FIX");
        
        String origClOrdId = orderCancelRequest.getOrigClOrdID().getValue();
        
        // Find the original order
        OrderEntity order = orderDao.findByClOrdId(origClOrdId);
        if (order == null) {
            LOG.warn("Order not found for cancel: {}", origClOrdId);
            return;
        }
        
        // Update order status
        order.setStatus("CANCELED");
        order.setUpdatedAt(LocalDateTime.now());
        orderDao.updateOrder(order);
        
        LOG.info("Order canceled: {}", origClOrdId);
        
        // Send CANCELED execution report
        sendCancel(order, sessionID);
    }
}
