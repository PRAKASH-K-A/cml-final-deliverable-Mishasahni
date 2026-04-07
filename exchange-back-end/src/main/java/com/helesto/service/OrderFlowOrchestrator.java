package com.helesto.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.dao.OrderDao;
import com.helesto.model.OrderEntity;

/**
 * Order Flow Orchestrator
 * Coordinates all services in the order processing pipeline:
 * 1. Market State Check - is market open for trading?
 * 2. Rate Limiting - client rate limits, throttling
 * 3. Order Validation - symbol, price, quantity validation
 * 4. Risk Management - position limits, P&L limits, kill switch
 * 5. Circuit Breaker Check - LULD bands, halted symbols
 * 6. Order Enrichment - timestamps, reference numbers
 * 7. Audit Trail - log order received
 * 8. Performance Metrics - track latencies
 * 9. Matching Engine - attempt to match
 * 10. Position Tracking - update positions on fills
 */
@ApplicationScoped
public class OrderFlowOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(OrderFlowOrchestrator.class);
    
    @Inject
    MarketStateManager marketStateManager;
    
    @Inject
    OrderRateLimiter orderRateLimiter;
    
    @Inject
    OrderValidationService validationService;
    
    @Inject
    RiskManagementService riskManagementService;
    
    @Inject
    CircuitBreakerService circuitBreakerService;
    
    @Inject
    AuditTrailService auditTrailService;
    
    @Inject
    PerformanceMetricsService performanceMetricsService;
    
    @Inject
    MatchingEngine matchingEngine;
    
    @Inject
    OrderBookManager orderBookManager;
    
    @Inject
    PositionTrackingService positionTrackingService;
    
    @Inject
    TradeService tradeService;
    
    @Inject
    OrderDao orderDao;
    
    @Inject
    TelemetryService telemetryService;

    @Inject
    OrderCacheService orderCacheService;
    
    /**
     * Process an order through the complete pipeline
     * @return OrderResult with success/failure status and details
     */
    public OrderResult processOrder(OrderRequest request) {
        long startTime = System.nanoTime();
        String clOrdId = request.clOrdId != null ? request.clOrdId : UUID.randomUUID().toString();
        
        LOG.debug("Processing order: clOrdId={}, symbol={}, side={}", clOrdId, request.symbol, request.side);
        
        try {
            // 1. Market State Check
            long stepStart = System.nanoTime();
            if (!marketStateManager.isTradingAllowed()) {
                String reason = "Market is closed: " + marketStateManager.getCurrentState();
                recordRejection(clOrdId, request, "MARKET_CLOSED", reason, startTime);
                return OrderResult.rejected(clOrdId, "MARKET_CLOSED", reason);
            }
            performanceMetricsService.recordLatency("order.market_state_check", System.nanoTime() - stepStart);
            
            // Check if market accepts this order type in current phase
            if (!isOrderTypeAllowedInPhase(request.orderType)) {
                String reason = "Order type " + request.orderType + " not allowed in " + marketStateManager.getCurrentPhase();
                recordRejection(clOrdId, request, "ORDER_TYPE_NOT_ALLOWED", reason, startTime);
                return OrderResult.rejected(clOrdId, "ORDER_TYPE_NOT_ALLOWED", reason);
            }
            
            // 2. Rate Limiting Check
            stepStart = System.nanoTime();
            String clientId = request.clientId != null ? request.clientId : "DEFAULT";
            OrderRateLimiter.RateLimitResult rateLimitResult = orderRateLimiter.checkRateLimit(clientId, request.symbol);
            if (!rateLimitResult.allowed) {
                String reason = rateLimitResult.reason;
                recordRejection(clOrdId, request, "RATE_LIMIT_EXCEEDED", reason, startTime);
                return OrderResult.rejected(clOrdId, "RATE_LIMIT_EXCEEDED", reason);
            }
            performanceMetricsService.recordLatency("order.rate_limit_check", System.nanoTime() - stepStart);
            
            // 3. Create and Validate Order Entity
            stepStart = System.nanoTime();
            OrderEntity order = createOrderEntity(request, clOrdId, clientId);
            
            OrderValidationService.ValidationResult validation = validationService.validateOrder(order);
            if (!validation.isValid()) {
                String reason = String.join("; ", validation.getErrors());
                recordRejection(clOrdId, request, validation.getRejectReason(), reason, startTime);
                return OrderResult.rejected(clOrdId, validation.getRejectReason(), reason);
            }
            performanceMetricsService.recordLatency("order.validate", System.nanoTime() - stepStart);
            
            // Log warnings
            for (String warning : validation.getWarnings()) {
                LOG.warn("Order warning for {}: {}", clOrdId, warning);
            }
            
            // 4. Circuit Breaker Check
            stepStart = System.nanoTime();
            CircuitBreakerService.CircuitBreakerCheckResult cbResult = 
                    circuitBreakerService.checkPrice(order.getSymbol(), order.getPrice());
            if (!cbResult.allowed) {
                String reason = cbResult.reason;
                recordRejection(clOrdId, request, "CIRCUIT_BREAKER", reason, startTime);
                return OrderResult.rejected(clOrdId, "CIRCUIT_BREAKER", reason);
            }
            performanceMetricsService.recordLatency("order.circuit_breaker_check", System.nanoTime() - stepStart);
            
            // 5. Risk Management Check
            stepStart = System.nanoTime();
            RiskManagementService.RiskCheckResult riskResult = riskManagementService.preTradeRiskCheck(
                    clientId, order.getSymbol(), order.getSide(), order.getQuantity(), order.getPrice());
            if (!riskResult.isApproved()) {
                String reason = riskResult.getRejectReason();
                recordRejection(clOrdId, request, riskResult.getRejectReason(), reason, startTime);
                return OrderResult.rejected(clOrdId, "RISK_CHECK_FAILED", reason);
            }
            performanceMetricsService.recordLatency("order.risk_check", System.nanoTime() - stepStart);
            
            // 6. Order Enrichment
            stepStart = System.nanoTime();
            validationService.enrichOrder(order);
            performanceMetricsService.recordLatency("order.enrich", System.nanoTime() - stepStart);
            
            // 7. Audit Trail - Order Received
            auditTrailService.logOrderReceived(clOrdId, order.getOrderRefNumber(), clientId, 
                    order.getSymbol(), order.getSide(), order.getQuantity(), order.getPrice(), order.getOrderType());
            
            // 8. Persist Order
            stepStart = System.nanoTime();
            order.setStatus("NEW");
            order.setFilledQty(0L);
            order.setLeavesQty(order.getQuantity());
            order.setAvgPrice(0.0);
            orderDao.persistOrder(order);
            orderCacheService.addToCache(order);
            performanceMetricsService.recordLatency("db.write", System.nanoTime() - stepStart);
            
            auditTrailService.logOrderAccepted(clOrdId, order.getOrderRefNumber());
            
            // 9. Match Order
            stepStart = System.nanoTime();
            OrderBookManager.BookOrder bookOrder = createBookOrder(order);
            MatchingEngine.MatchResult matchResult = matchingEngine.matchOrder(bookOrder);
            performanceMetricsService.recordLatency("order.match", System.nanoTime() - stepStart);
            
            // 10. Update Order with Match Result
            stepStart = System.nanoTime();
            order.setStatus(matchResult.status.name());
            order.setFilledQty((long) matchResult.filledQty);
            order.setLeavesQty((long) matchResult.leavesQty);
            order.setAvgPrice(matchResult.avgPrice);
            orderDao.updateOrder(order);
            orderCacheService.addToCache(order);
            performanceMetricsService.recordLatency("db.write", System.nanoTime() - stepStart);
            
            // 11. Process Fills
            List<FillInfo> fills = new ArrayList<>();
            for (MatchingEngine.Fill fill : matchResult.fills) {
                // Create trade record
                com.helesto.model.TradeEntity trade = tradeService.createTrade(
                        fill, order.getOrderRefNumber(), order.getClOrdId(),
                        order.getClientId(), order.getSide(), order.getSymbol());
                
                // Update positions
                positionTrackingService.recordTrade(
                        clientId, order.getSymbol(), order.getSide(),
                        fill.quantity, fill.price, trade.getTradeId());
                
                // Audit trail
                auditTrailService.logOrderFill(clOrdId, order.getOrderRefNumber(),
                        fill.quantity, fill.price, trade.getTradeId());
                
                fills.add(new FillInfo(trade.getTradeId(), fill.quantity, fill.price, fill.contraOrderId));
            }
            
            // Log final status
            if (matchResult.status == MatchingEngine.OrderStatus.FILLED) {
                auditTrailService.logOrderFilled(clOrdId, order.getOrderRefNumber());
            } else if (matchResult.status == MatchingEngine.OrderStatus.PARTIALLY_FILLED) {
                auditTrailService.logOrderPartialFill(clOrdId, order.getOrderRefNumber(),
                        matchResult.filledQty, matchResult.leavesQty);
            }
            
            // Record total latency
            long totalLatency = System.nanoTime() - startTime;
            performanceMetricsService.recordLatency("order.total", totalLatency);
            telemetryService.recordOrderProcessed();
            telemetryService.recordOrderProcessed(totalLatency);
            telemetryService.recordMatchAttempt(matchResult.filledQty > 0, 0L);
            
            LOG.info("Order processed: clOrdId={}, orderRef={}, status={}, filled={}/{}", 
                    clOrdId, order.getOrderRefNumber(), matchResult.status, 
                    matchResult.filledQty, order.getQuantity());
            
            return OrderResult.success(clOrdId, order.getOrderRefNumber(), matchResult, fills, matchResult.addedToBook);
            
        } catch (Exception e) {
            LOG.error("Error processing order: clOrdId={}", clOrdId, e);
            performanceMetricsService.recordLatency("order.total", System.nanoTime() - startTime);
            auditTrailService.logOrderError(clOrdId, null, e.getMessage());
            return OrderResult.error(clOrdId, "INTERNAL_ERROR", e.getMessage());
        }
    }
    
    /**
     * Cancel an order through the orchestrated pipeline
     */
    public CancelResult cancelOrder(String orderRefNumber, String clientId, String reason) {
        long startTime = System.nanoTime();
        
        try {
            // Audit trail - cancel request received
            auditTrailService.logCancelReceived(orderRefNumber, clientId, reason);
            
            // Find the order
            OrderEntity order = orderDao.findByOrderRefNumber(orderRefNumber);
            if (order == null) {
                return CancelResult.notFound(orderRefNumber);
            }
            
            // Check if order can be canceled
            if ("FILLED".equals(order.getStatus()) || "CANCELED".equals(order.getStatus())) {
                return CancelResult.alreadyTerminal(orderRefNumber, order.getStatus());
            }
            
            // Remove from order book
            orderBookManager.cancelOrder(order.getSymbol(), order.getSide(), orderRefNumber);
            
            // Update order status
            long canceledQty = order.getLeavesQty();
            order.setStatus("CANCELED");
            order.setLeavesQty(0L);
            orderDao.updateOrder(order);
            orderCacheService.addToCache(order);
            
            // Audit trail
            auditTrailService.logOrderCanceled(order.getClOrdId(), orderRefNumber, reason);
            
            performanceMetricsService.recordLatency("order.cancel", System.nanoTime() - startTime);
            
            return CancelResult.success(orderRefNumber, canceledQty, order.getFilledQty());
            
        } catch (Exception e) {
            LOG.error("Error canceling order: orderRef={}", orderRefNumber, e);
            return CancelResult.error(orderRefNumber, e.getMessage());
        }
    }
    
    // ================== Helper Methods ==================
    
    private boolean isOrderTypeAllowedInPhase(String orderType) {
        MarketStateManager.TradingPhase phase = marketStateManager.getCurrentPhase();
        String normalizedType = normalizeOrderType(orderType);

        if (phase == MarketStateManager.TradingPhase.CONTINUOUS) {
            return true; // All order types allowed
        }
        if (phase == MarketStateManager.TradingPhase.OPENING_AUCTION ||
            phase == MarketStateManager.TradingPhase.CLOSING_AUCTION) {
            // Only limit orders in auctions
            return "LIMIT".equalsIgnoreCase(normalizedType);
        }
        if (phase == MarketStateManager.TradingPhase.PRE_OPEN ||
            phase == MarketStateManager.TradingPhase.POST_CLOSE) {
            // Extended sessions: allow non-market passive orders
            return "LIMIT".equalsIgnoreCase(normalizedType) ||
                   "STOP_LIMIT".equalsIgnoreCase(normalizedType);
        }
        return false;
    }
    
    private OrderEntity createOrderEntity(OrderRequest request, String clOrdId, String clientId) {
        OrderEntity order = new OrderEntity();
        order.setClOrdId(clOrdId);
        order.setSymbol(request.symbol);
        order.setSide(normalizeSide(request.side));
        order.setQuantity((long) request.quantity);
        order.setPrice(request.price);
        order.setOrderType(normalizeOrderType(request.orderType));
        order.setTimeInForce(normalizeTimeInForce(request.timeInForce));
        order.setClientId(clientId);
        return order;
    }
    
    private OrderBookManager.BookOrder createBookOrder(OrderEntity order) {
        OrderBookManager.BookOrder bookOrder = new OrderBookManager.BookOrder();
        bookOrder.orderId = order.getOrderRefNumber();
        bookOrder.clOrdId = order.getClOrdId();
        bookOrder.symbol = order.getSymbol();
        bookOrder.side = order.getSide();
        bookOrder.price = order.getPrice();
        bookOrder.originalQty = order.getQuantity().intValue();
        bookOrder.leavesQty = order.getQuantity().intValue();
        bookOrder.orderType = order.getOrderType();
        bookOrder.timeInForce = order.getTimeInForce();
        bookOrder.clientId = order.getClientId();
        return bookOrder;
    }
    
    private void recordRejection(String clOrdId, OrderRequest request, String rejectCode, String reason, long startTime) {
        auditTrailService.logOrderRejected(clOrdId, rejectCode, reason);
        performanceMetricsService.recordLatency("order.total", System.nanoTime() - startTime);
        telemetryService.recordOrderRejected();
        LOG.warn("Order rejected: clOrdId={}, code={}, reason={}", clOrdId, rejectCode, reason);
    }
    
    private String normalizeSide(String side) {
        if (side == null) return null;
        if ("BUY".equalsIgnoreCase(side)) return "1";
        if ("SELL".equalsIgnoreCase(side)) return "2";
        return side;
    }
    
    private String normalizeOrderType(String type) {
        if (type == null) return "LIMIT";
        switch (type.toUpperCase()) {
            case "1": case "MARKET": return "MARKET";
            case "2": case "LIMIT": return "LIMIT";
            case "3": case "STOP": return "STOP";
            case "4": case "STOP_LIMIT": return "STOP_LIMIT";
            default: return type.toUpperCase();
        }
    }
    
    private String normalizeTimeInForce(String tif) {
        if (tif == null) return "DAY";
        switch (tif.toUpperCase()) {
            case "0": return "DAY";
            case "1": return "GTC";
            case "3": return "IOC";
            case "4": return "FOK";
            case "6": return "GTD";
            default: return tif.toUpperCase();
        }
    }
    
    // ================== Data Classes ==================
    
    public static class OrderRequest {
        public String clOrdId;
        public String symbol;
        public String side;
        public int quantity;
        public Double price;
        public String orderType;
        public String timeInForce;
        public String clientId;
        
        // Builder pattern for convenience
        public static OrderRequest create(String symbol, String side, int quantity, Double price) {
            OrderRequest req = new OrderRequest();
            req.symbol = symbol;
            req.side = side;
            req.quantity = quantity;
            req.price = price;
            return req;
        }
        
        public OrderRequest withClientId(String clientId) { this.clientId = clientId; return this; }
        public OrderRequest withOrderType(String orderType) { this.orderType = orderType; return this; }
        public OrderRequest withTimeInForce(String tif) { this.timeInForce = tif; return this; }
        public OrderRequest withClOrdId(String clOrdId) { this.clOrdId = clOrdId; return this; }
    }
    
    public static class OrderResult {
        public final boolean success;
        public final String clOrdId;
        public final String orderRefNumber;
        public final String status;
        public final String rejectCode;
        public final String rejectReason;
        public final int filledQty;
        public final int leavesQty;
        public final double avgPrice;
        public final List<FillInfo> fills;
        public final boolean addedToBook;
        
        private OrderResult(boolean success, String clOrdId, String orderRefNumber, String status,
                           String rejectCode, String rejectReason, int filledQty, int leavesQty,
                           double avgPrice, List<FillInfo> fills, boolean addedToBook) {
            this.success = success;
            this.clOrdId = clOrdId;
            this.orderRefNumber = orderRefNumber;
            this.status = status;
            this.rejectCode = rejectCode;
            this.rejectReason = rejectReason;
            this.filledQty = filledQty;
            this.leavesQty = leavesQty;
            this.avgPrice = avgPrice;
            this.fills = fills != null ? fills : new ArrayList<>();
            this.addedToBook = addedToBook;
        }
        
        public static OrderResult success(String clOrdId, String orderRefNumber, 
                                          MatchingEngine.MatchResult matchResult,
                                          List<FillInfo> fills, boolean addedToBook) {
            return new OrderResult(true, clOrdId, orderRefNumber, matchResult.status.name(),
                    null, null, matchResult.filledQty, matchResult.leavesQty,
                    matchResult.avgPrice, fills, addedToBook);
        }
        
        public static OrderResult rejected(String clOrdId, String rejectCode, String reason) {
            return new OrderResult(false, clOrdId, null, "REJECTED",
                    rejectCode, reason, 0, 0, 0.0, null, false);
        }
        
        public static OrderResult error(String clOrdId, String errorCode, String message) {
            return new OrderResult(false, clOrdId, null, "ERROR",
                    errorCode, message, 0, 0, 0.0, null, false);
        }
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("success", success);
            map.put("clOrdId", clOrdId);
            map.put("orderRefNumber", orderRefNumber);
            map.put("status", status);
            if (rejectCode != null) map.put("rejectCode", rejectCode);
            if (rejectReason != null) map.put("rejectReason", rejectReason);
            map.put("filledQty", filledQty);
            map.put("leavesQty", leavesQty);
            map.put("avgPrice", avgPrice);
            map.put("fillCount", fills.size());
            map.put("addedToBook", addedToBook);
            return map;
        }
    }
    
    public static class FillInfo {
        public final String tradeId;
        public final int quantity;
        public final double price;
        public final String counterOrderId;
        
        public FillInfo(String tradeId, int quantity, double price, String counterOrderId) {
            this.tradeId = tradeId;
            this.quantity = quantity;
            this.price = price;
            this.counterOrderId = counterOrderId;
        }
    }
    
    public static class CancelResult {
        public final boolean success;
        public final String orderRefNumber;
        public final String message;
        public final long canceledQuantity;
        public final long filledQuantity;
        
        private CancelResult(boolean success, String orderRefNumber, String message,
                            long canceledQty, long filledQty) {
            this.success = success;
            this.orderRefNumber = orderRefNumber;
            this.message = message;
            this.canceledQuantity = canceledQty;
            this.filledQuantity = filledQty;
        }
        
        public static CancelResult success(String orderRef, long canceledQty, long filledQty) {
            return new CancelResult(true, orderRef, "Order canceled successfully", canceledQty, filledQty);
        }
        
        public static CancelResult notFound(String orderRef) {
            return new CancelResult(false, orderRef, "Order not found", 0, 0);
        }
        
        public static CancelResult alreadyTerminal(String orderRef, String status) {
            return new CancelResult(false, orderRef, "Order already in terminal state: " + status, 0, 0);
        }
        
        public static CancelResult error(String orderRef, String message) {
            return new CancelResult(false, orderRef, "Error: " + message, 0, 0);
        }
    }
}
