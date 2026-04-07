package com.helesto.service;

import java.time.LocalDateTime;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.dao.OrderDao;
import com.helesto.model.OrderEntity;

import quickfix.SessionID;

/**
 * Dedicated FIX order management engine used by ExchangeApplication.
 * Centralizes submit match flow, cancel, and replace lifecycle handling.
 */
@ApplicationScoped
public class FixOrderManagementEngine {

    private static final Logger LOG = LoggerFactory.getLogger(FixOrderManagementEngine.class);

    @Inject
    OrderDao orderDao;

    @Inject
    MatchingEngine matchingEngine;

    @Inject
    OrderBookManager orderBookManager;

    @Inject
    TradeService tradeService;

    @Inject
    ExecutionReportService executionReportService;

    @Inject
    TelemetryService telemetryService;

    public void processOrderMatch(OrderEntity order, SessionID sessionID) {
        try {
            long preMatchFilled = order.getFilledQty() != null ? order.getFilledQty() : 0L;
            long orderQty = order.getQuantity() != null ? order.getQuantity() : 0L;

            OrderBookManager.BookOrder bookOrder = new OrderBookManager.BookOrder();
            bookOrder.orderId = order.getOrderRefNumber();
            bookOrder.clOrdId = order.getClOrdId();
            bookOrder.symbol = order.getSymbol();
            bookOrder.side = order.getSide();
            bookOrder.price = order.getPrice();
            bookOrder.originalQty = order.getQuantity().intValue();
            bookOrder.leavesQty = order.getLeavesQty() != null ? order.getLeavesQty().intValue() : bookOrder.originalQty;
            bookOrder.orderType = order.getOrderType();
            bookOrder.timeInForce = order.getTimeInForce();
            bookOrder.clientId = order.getClientId();
            bookOrder.timestamp = System.currentTimeMillis();

            MatchingEngine.MatchResult result = matchingEngine.matchOrder(bookOrder);

            if (result.filledQty > 0) {
                order.setFilledQty((long) result.filledQty);
                order.setLeavesQty((long) result.leavesQty);

                double totalValue = 0;
                for (MatchingEngine.Fill fill : result.fills) {
                    totalValue += fill.price * fill.quantity;

                    tradeService.createTrade(fill, order.getOrderRefNumber(), order.getClOrdId(),
                            order.getClientId(), order.getSide(), order.getSymbol());

                    executionReportService.processContraFill(fill, sessionID);
                }
                order.setAvgPrice(totalValue / result.filledQty);

                if (result.leavesQty == 0) {
                    order.setStatus("FILLED");
                    if (telemetryService != null) {
                        telemetryService.recordOrderFilled();
                    }
                } else {
                    order.setStatus("PARTIALLY_FILLED");
                }

                order.setUpdatedAt(LocalDateTime.now());
                orderDao.updateOrder(order);

                long runningFilled = preMatchFilled;
                for (MatchingEngine.Fill fill : result.fills) {
                    runningFilled += fill.quantity;
                    long runningLeaves = Math.max(0L, orderQty - runningFilled);

                    order.setFilledQty(runningFilled);
                    order.setLeavesQty(runningLeaves);
                    order.setStatus(runningLeaves == 0 ? "FILLED" : "PARTIALLY_FILLED");

                    executionReportService.sendFill(order, fill, sessionID);
                }
            }

            if (result.addedToBook) {
                LOG.info("Order {} added to book with leaves qty {}", order.getClOrdId(), result.leavesQty);
            }
        } catch (Exception e) {
            LOG.error("Error in matching process", e);
        }
    }

    public CancelResult cancel(String origClOrdId) {
        OrderEntity order = orderDao.findByClOrdId(origClOrdId);
        if (order == null) {
            return CancelResult.notFound();
        }

        String status = order.getStatus();
        if ("FILLED".equals(status) || "CANCELED".equals(status) || "REJECTED".equals(status)) {
            return CancelResult.tooLate(order, "Order already " + status);
        }

        if ("PENDING_CANCEL".equals(status)) {
            return CancelResult.pending(order, "Order already pending cancel");
        }

        orderBookManager.removeOrder(order.getSymbol(), order.getOrderRefNumber());
        order.setStatus("CANCELED");
        order.setUpdatedAt(LocalDateTime.now());
        orderDao.updateOrder(order);
        return CancelResult.success(order);
    }

    public ReplaceResult replace(String origClOrdId, String newClOrdId, Double newQty, Double newPrice) {
        OrderEntity order = orderDao.findByClOrdId(origClOrdId);
        if (order == null) {
            return ReplaceResult.notFound();
        }

        String status = order.getStatus();
        if ("FILLED".equals(status) || "CANCELED".equals(status) || "REJECTED".equals(status)) {
            return ReplaceResult.tooLate(order, "Order already " + status);
        }

        orderBookManager.removeOrder(order.getSymbol(), order.getOrderRefNumber());

        if (newQty != null) {
            long qty = newQty.longValue();
            order.setQuantity(qty);
            long filled = order.getFilledQty() != null ? order.getFilledQty() : 0L;
            order.setLeavesQty(Math.max(0L, qty - filled));
        }

        if (newPrice != null) {
            order.setPrice(newPrice);
        }

        order.setClOrdId(newClOrdId);
        order.setStatus("NEW");
        order.setUpdatedAt(LocalDateTime.now());
        orderDao.updateOrder(order);
        return ReplaceResult.success(order);
    }

    public static class CancelResult {
        public final boolean success;
        public final boolean notFound;
        public final boolean tooLate;
        public final boolean pending;
        public final String reason;
        public final OrderEntity order;

        private CancelResult(boolean success, boolean notFound, boolean tooLate, boolean pending, String reason, OrderEntity order) {
            this.success = success;
            this.notFound = notFound;
            this.tooLate = tooLate;
            this.pending = pending;
            this.reason = reason;
            this.order = order;
        }

        public static CancelResult success(OrderEntity order) {
            return new CancelResult(true, false, false, false, null, order);
        }

        public static CancelResult notFound() {
            return new CancelResult(false, true, false, false, "Order not found", null);
        }

        public static CancelResult tooLate(OrderEntity order, String reason) {
            return new CancelResult(false, false, true, false, reason, order);
        }

        public static CancelResult pending(OrderEntity order, String reason) {
            return new CancelResult(false, false, false, true, reason, order);
        }
    }

    public static class ReplaceResult {
        public final boolean success;
        public final boolean notFound;
        public final boolean tooLate;
        public final String reason;
        public final OrderEntity order;

        private ReplaceResult(boolean success, boolean notFound, boolean tooLate, String reason, OrderEntity order) {
            this.success = success;
            this.notFound = notFound;
            this.tooLate = tooLate;
            this.reason = reason;
            this.order = order;
        }

        public static ReplaceResult success(OrderEntity order) {
            return new ReplaceResult(true, false, false, null, order);
        }

        public static ReplaceResult notFound() {
            return new ReplaceResult(false, true, false, "Order not found", null);
        }

        public static ReplaceResult tooLate(OrderEntity order, String reason) {
            return new ReplaceResult(false, false, true, reason, order);
        }
    }
}
