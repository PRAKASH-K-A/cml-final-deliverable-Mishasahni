package com.helesto.service;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import com.helesto.dao.OrderDao;
import com.helesto.model.OrderEntity;

import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.OrdType;
import quickfix.field.TimeInForce;
import quickfix.fix44.NewOrderSingle;

@ApplicationScoped
public class QuickFixJOrderIntakeEngine {

    private final Set<String> processedClOrdIds = ConcurrentHashMap.newKeySet();

    @Inject
    OrderValidationService orderValidationService;

    @Inject
    OrderDao orderDao;

    @Inject
    TelemetryService telemetryService;

    @Inject
    PerformanceMetricsService performanceMetricsService;

    @Inject
    OrderCacheService orderCacheService;

    public IntakeResult intake(NewOrderSingle newOrderSingle, SessionID sessionID) throws FieldNotFound {
        return intake((Message) newOrderSingle, sessionID);
    }

    public IntakeResult intake(Message newOrderSingle, SessionID sessionID) throws FieldNotFound {
        long startTime = System.nanoTime();
        telemetryService.recordFixMessageReceived();
        telemetryService.recordOrderReceived();

        String clOrdId = newOrderSingle.getString(11);
        if (clOrdId == null || clOrdId.isBlank()) {
            telemetryService.recordFixMessageRejected();
            telemetryService.recordOrderRejected();
            return IntakeResult.rejected("UNKNOWN", "UNKNOWN", '1', 0, "ClOrdID is required");
        }

        if (orderDao.findByClOrdId(clOrdId) != null) {
            telemetryService.recordFixMessageRejected();
            telemetryService.recordOrderRejected();
            return IntakeResult.rejected(clOrdId, "UNKNOWN", '1', 0, "Duplicate ClOrdID");
        }

        String symbol = newOrderSingle.getString(55);
        char side = newOrderSingle.getChar(54);
        double qty = newOrderSingle.getDouble(38);
        char ordType = newOrderSingle.getChar(40);

        double price = 0;
        if (ordType == OrdType.LIMIT || ordType == OrdType.STOP_LIMIT) {
            if (newOrderSingle.isSetField(44)) {
                price = newOrderSingle.getDouble(44);
            } else {
                telemetryService.recordFixMessageRejected();
                telemetryService.recordOrderRejected();
                return IntakeResult.rejected(clOrdId, symbol, side, qty, "Price required for LIMIT orders");
            }
        }

        String timeInForce = "DAY";
        if (newOrderSingle.isSetField(59)) {
            char tif = newOrderSingle.getChar(59);
            timeInForce = mapTimeInForce(tif);
        }

        OrderEntity order = new OrderEntity();
        order.setClOrdId(clOrdId);
        order.setSymbol(symbol);
        order.setSide(String.valueOf(side));
        order.setQuantity((long) qty);
        order.setPrice(price);
        order.setOrderType(mapOrdType(ordType));
        order.setTimeInForce(timeInForce);
        order.setCreatedAt(LocalDateTime.now());
        order.setSenderCompId(sessionID.getSenderCompID());
        order.setTargetCompId(sessionID.getTargetCompID());

        OrderValidationService.ValidationResult validationResult = orderValidationService.validateOrder(order);
        if (!validationResult.isValid()) {
            telemetryService.recordFixMessageRejected();
            telemetryService.recordOrderRejected();
            return IntakeResult.rejected(clOrdId, symbol, side, qty, String.join("; ", validationResult.getErrors()));
        }

        if (!processedClOrdIds.add(clOrdId)) {
            telemetryService.recordFixMessageRejected();
            telemetryService.recordOrderRejected();
            return IntakeResult.rejected(clOrdId, symbol, side, qty, "Duplicate ClOrdID");
        }

        orderValidationService.enrichOrder(order);
        order.setStatus("NEW");
        try {
            orderDao.persistOrder(order);
            orderCacheService.addToCache(order);
        } catch (RuntimeException e) {
            processedClOrdIds.remove(clOrdId);
            throw e;
        }

        long totalLatency = System.nanoTime() - startTime;
        performanceMetricsService.recordLatency("fix.intake.total", totalLatency);
        telemetryService.recordOrderProcessed(totalLatency);

        return IntakeResult.accepted(order, clOrdId, symbol, side, qty);
    }

    private String mapOrdType(char ordType) {
        switch (ordType) {
            case OrdType.MARKET: return "MARKET";
            case OrdType.LIMIT: return "LIMIT";
            case '3': return "STOP";
            case OrdType.STOP_LIMIT: return "STOP_LIMIT";
            default: return "LIMIT";
        }
    }

    private String mapTimeInForce(char tif) {
        switch (tif) {
            case TimeInForce.DAY: return "DAY";
            case TimeInForce.GOOD_TILL_CANCEL: return "GTC";
            case TimeInForce.IMMEDIATE_OR_CANCEL: return "IOC";
            case TimeInForce.FILL_OR_KILL: return "FOK";
            default: return "DAY";
        }
    }

    public static class IntakeResult {
        public final boolean accepted;
        public final OrderEntity order;
        public final String clOrdId;
        public final String symbol;
        public final char side;
        public final double quantity;
        public final String rejectReason;

        private IntakeResult(boolean accepted, OrderEntity order, String clOrdId, String symbol, char side,
                             double quantity, String rejectReason) {
            this.accepted = accepted;
            this.order = order;
            this.clOrdId = clOrdId;
            this.symbol = symbol;
            this.side = side;
            this.quantity = quantity;
            this.rejectReason = rejectReason;
        }

        public static IntakeResult accepted(OrderEntity order, String clOrdId, String symbol, char side, double quantity) {
            return new IntakeResult(true, order, clOrdId, symbol, side, quantity, null);
        }

        public static IntakeResult rejected(String clOrdId, String symbol, char side, double quantity, String reason) {
            return new IntakeResult(false, null, clOrdId, symbol, side, quantity, reason);
        }
    }
}
