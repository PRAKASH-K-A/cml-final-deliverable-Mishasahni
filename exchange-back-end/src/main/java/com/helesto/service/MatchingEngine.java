package com.helesto.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * G2-M2: Matching Engine
 * - Matching rules (market/limit, price-time priority)
 * - Partial fill handling
 * - Execution report generation
 */
@ApplicationScoped
public class MatchingEngine {

    private static final Logger LOG = LoggerFactory.getLogger(MatchingEngine.class);
    
    @Inject
    OrderBookManager orderBookManager;
    
    @Inject
    ReferenceDataService referenceDataService;
    
    @Inject
    TelemetryService telemetryService;
    
    private final AtomicLong execIdSequence = new AtomicLong(1);
    private final AtomicLong tradeIdSequence = new AtomicLong(1);
    
    /**
     * Process an incoming order and attempt to match
     * Returns a list of execution results
     */
    public MatchResult matchOrder(OrderBookManager.BookOrder incomingOrder) {
        long startTime = System.nanoTime();
        LOG.info("Processing order: {} {} {} @ {} qty {}", 
                incomingOrder.orderId, incomingOrder.side, incomingOrder.symbol, 
                incomingOrder.price, incomingOrder.leavesQty);
        
        MatchResult result = new MatchResult();
        result.orderId = incomingOrder.orderId;
        result.clOrdId = incomingOrder.clOrdId;
        result.symbol = incomingOrder.symbol;
        result.side = incomingOrder.side;
        result.originalQty = incomingOrder.originalQty;
        result.leavesQty = incomingOrder.leavesQty; // Initialize with order's leaves qty
        
        // Market order handling
        if ("1".equals(incomingOrder.orderType) || "MARKET".equals(incomingOrder.orderType)) {
            processMarketOrder(incomingOrder, result);
        }
        // Limit order handling
        else if ("2".equals(incomingOrder.orderType) || "LIMIT".equals(incomingOrder.orderType)) {
            processLimitOrder(incomingOrder, result);
        }
        // Stop order - converts to market order when stop price is hit
        else if ("3".equals(incomingOrder.orderType) || "STOP".equals(incomingOrder.orderType)) {
            processStopOrder(incomingOrder, result);
        }
        // Stop-Limit order - converts to limit order when stop price is hit
        else if ("4".equals(incomingOrder.orderType) || "STOP_LIMIT".equals(incomingOrder.orderType)) {
            processStopLimitOrder(incomingOrder, result);
        }
        else {
            LOG.warn("Unsupported order type: {}", incomingOrder.orderType);
            result.status = OrderStatus.REJECTED;
            result.rejectReason = "Unsupported order type: " + incomingOrder.orderType;
        }
        
        LOG.info("Match result for {}: status={}, filledQty={}, fills={}", 
                incomingOrder.orderId, result.status, result.filledQty, result.fills.size());
        
        // Record telemetry
        long matchTimeNanos = System.nanoTime() - startTime;
        boolean success = !result.fills.isEmpty();
        telemetryService.recordMatchAttempt(success, matchTimeNanos);
        for (int i = 0; i < result.fills.size(); i++) {
            telemetryService.recordTradeGenerated();
        }
        
        return result;
    }
    
    /**
     * Process market order - match immediately at best available price
     */
    private void processMarketOrder(OrderBookManager.BookOrder order, MatchResult result) {
        List<OrderBookManager.BookOrder> matchableOrders;
        
        if ("BUY".equals(order.side) || "1".equals(order.side)) {
            // Buy market order matches against all asks (price ascending)
            matchableOrders = new ArrayList<>();
            List<OrderBookManager.PriceLevel> asks = orderBookManager.getTopAsks(order.symbol, 100);
            for (OrderBookManager.PriceLevel level : asks) {
                matchableOrders.addAll(orderBookManager.getAsksAtPrice(order.symbol, level.price));
            }
        } else {
            // Sell market order matches against all bids (price descending)
            matchableOrders = new ArrayList<>();
            List<OrderBookManager.PriceLevel> bids = orderBookManager.getTopBids(order.symbol, 100);
            for (OrderBookManager.PriceLevel level : bids) {
                matchableOrders.addAll(orderBookManager.getBidsAtPrice(order.symbol, level.price));
            }
        }
        
        if (matchableOrders.isEmpty()) {
            // No liquidity - reject market order
            result.status = OrderStatus.REJECTED;
            result.rejectReason = "No liquidity available for market order";
            return;
        }
        
        // Match against available orders
        executeMatches(order, matchableOrders, result);
        
        // Market order must be fully filled or rejected
        if (result.leavesQty > 0) {
            // Partial fill still counts for IOC-like behavior on market orders
            if (result.filledQty > 0) {
                result.status = OrderStatus.FILLED; // Consider partially filled market order as filled
            } else {
                result.status = OrderStatus.REJECTED;
                result.rejectReason = "Insufficient liquidity for market order";
            }
        }
    }
    
    /**
     * Process limit order - match if price crosses, otherwise add to book
     */
    private void processLimitOrder(OrderBookManager.BookOrder order, MatchResult result) {
        // Get matchable orders on opposite side
        List<OrderBookManager.BookOrder> matchableOrders = 
            orderBookManager.getMatchableOrders(order.symbol, order.side, order.price);
        
        if (!matchableOrders.isEmpty()) {
            executeMatches(order, matchableOrders, result);
        }
        
        // Handle remaining quantity based on TimeInForce
        if (result.leavesQty > 0) {
            String tif = order.timeInForce;
            
            // IOC (Immediate or Cancel) - cancel unfilled portion
            if ("3".equals(tif) || "IOC".equals(tif)) {
                result.status = result.filledQty > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.CANCELED;
                result.canceledQty = result.leavesQty;
                result.leavesQty = 0;
            }
            // FOK (Fill or Kill) - cancel entire order if not fully filled
            else if ("4".equals(tif) || "FOK".equals(tif)) {
                if (result.filledQty < order.originalQty) {
                    // Undo any fills (in real system, would need rollback)
                    result.status = OrderStatus.REJECTED;
                    result.rejectReason = "FOK order could not be fully filled";
                    result.canceledQty = order.originalQty;
                    result.filledQty = 0;
                    result.leavesQty = 0;
                    result.fills.clear();
                }
            }
            // DAY or GTC - add remainder to book
            else {
                // Update order with remaining quantity and add to book
                order.leavesQty = result.leavesQty;
                orderBookManager.addOrder(order);
                result.status = result.filledQty > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.NEW;
                result.addedToBook = true;
            }
        }
    }
    
    /**
     * Execute matches between incoming order and resting orders
     */
    private void executeMatches(OrderBookManager.BookOrder incomingOrder, 
                               List<OrderBookManager.BookOrder> restingOrders,
                               MatchResult result) {
        int remainingQty = incomingOrder.leavesQty;
        double totalValue = 0;
        
        for (OrderBookManager.BookOrder restingOrder : restingOrders) {
            if (remainingQty <= 0) break;
            
            // Calculate fill quantity
            int fillQty = Math.min(remainingQty, restingOrder.leavesQty);
            double fillPrice = restingOrder.price; // Price improvement goes to incoming order
            
            // Create fill
            Fill fill = new Fill();
            fill.execId = generateExecId();
            fill.tradeId = generateTradeId();
            fill.price = fillPrice;
            fill.quantity = fillQty;
            fill.contraOrderId = restingOrder.orderId;
            fill.contraClientId = restingOrder.clientId;
            fill.timestamp = System.currentTimeMillis();
            
            result.fills.add(fill);
            result.filledQty += fillQty;
            totalValue += fillPrice * fillQty;
            remainingQty -= fillQty;
            
            // Update resting order in book
            int newRestingQty = restingOrder.leavesQty - fillQty;
            if (newRestingQty <= 0) {
                orderBookManager.removeOrder(restingOrder.symbol, restingOrder.orderId);
            } else {
                orderBookManager.updateOrderQuantity(restingOrder.symbol, restingOrder.orderId, newRestingQty);
            }
            
            LOG.debug("Fill: {} {} @ {} (contra: {})", 
                    fillQty, incomingOrder.symbol, fillPrice, restingOrder.orderId);
        }
        
        result.leavesQty = remainingQty;
        result.avgPrice = result.filledQty > 0 ? totalValue / result.filledQty : 0;
        
        // Determine status
        if (result.leavesQty == 0) {
            result.status = OrderStatus.FILLED;
        } else if (result.filledQty > 0) {
            result.status = OrderStatus.PARTIALLY_FILLED;
        }
    }
    
    /**
     * Process stop order - triggers as market order when stop price is reached
     * BUY STOP: triggers when price rises to or above stop price
     * SELL STOP: triggers when price falls to or below stop price
     */
    private void processStopOrder(OrderBookManager.BookOrder order, MatchResult result) {
        double stopPrice = order.stopPrice > 0 ? order.stopPrice : order.price;
        double currentMarketPrice = getCurrentMarketPrice(order.symbol, order.side);

        boolean triggered = false;
        if ("BUY".equals(order.side) || "1".equals(order.side)) {
            triggered = currentMarketPrice >= stopPrice;
        } else {
            triggered = currentMarketPrice <= stopPrice;
        }

        if (triggered) {
            LOG.info("Stop order triggered for {} at market price {} (stop: {})", 
                    order.symbol, currentMarketPrice, stopPrice);
            // Execute as market order
            processMarketOrder(order, result);
        } else {
            // Add to book as a stop order (pending trigger)
            order.leavesQty = result.leavesQty;
            orderBookManager.addOrder(order);
            result.status = OrderStatus.NEW;
            result.addedToBook = true;
            LOG.info("Stop order queued for {} - awaiting price trigger (stop: {}, current: {})",
                    order.symbol, stopPrice, currentMarketPrice);
        }
    }

    /**
     * Process stop-limit order - triggers as limit order when stop price is reached
     */
    private void processStopLimitOrder(OrderBookManager.BookOrder order, MatchResult result) {
        double stopPrice = order.stopPrice > 0 ? order.stopPrice : order.price;
        double currentMarketPrice = getCurrentMarketPrice(order.symbol, order.side);

        boolean triggered = false;
        if ("BUY".equals(order.side) || "1".equals(order.side)) {
            triggered = currentMarketPrice >= stopPrice;
        } else {
            triggered = currentMarketPrice <= stopPrice;
        }

        if (triggered) {
            LOG.info("Stop-Limit order triggered for {} at market price {} (stop: {}, limit: {})",
                    order.symbol, currentMarketPrice, stopPrice, order.price);
            // Execute as limit order
            processLimitOrder(order, result);
        } else {
            // Add to book pending trigger
            order.leavesQty = result.leavesQty;
            orderBookManager.addOrder(order);
            result.status = OrderStatus.NEW;
            result.addedToBook = true;
            LOG.info("Stop-Limit order queued for {} - awaiting price trigger", order.symbol);
        }
    }

    /**
     * Get current best market price for a symbol
     */
    private double getCurrentMarketPrice(String symbol, String side) {
        if ("BUY".equals(side) || "1".equals(side)) {
            Double bestAsk = orderBookManager.getBestAsk(symbol);
            return bestAsk != null ? bestAsk : 0.0;
        } else {
            Double bestBid = orderBookManager.getBestBid(symbol);
            return bestBid != null ? bestBid : Double.MAX_VALUE;
        }
    }

    private String generateExecId() {
        return "EXEC-" + System.currentTimeMillis() + "-" + execIdSequence.getAndIncrement();
    }
    
    private String generateTradeId() {
        return "TRADE-" + System.currentTimeMillis() + "-" + tradeIdSequence.getAndIncrement();
    }
    
    // ================== Result Classes ==================
    
    public enum OrderStatus {
        NEW,
        PARTIALLY_FILLED,
        FILLED,
        CANCELED,
        REJECTED,
        PENDING_NEW,
        PENDING_CANCEL
    }
    
    public static class MatchResult {
        public String orderId;
        public String clOrdId;
        public String symbol;
        public String side;
        public int originalQty;
        public int filledQty = 0;
        public int leavesQty;
        public int canceledQty = 0;
        public double avgPrice = 0;
        public OrderStatus status = OrderStatus.PENDING_NEW;
        public String rejectReason;
        public boolean addedToBook = false;
        public List<Fill> fills = new ArrayList<>();
        
        public MatchResult() {
            this.leavesQty = 0;
        }
    }
    
    public static class Fill {
        public String execId;
        public String tradeId;
        public double price;
        public int quantity;
        public String contraOrderId;
        public String contraClientId;
        public long timestamp;
    }
}
