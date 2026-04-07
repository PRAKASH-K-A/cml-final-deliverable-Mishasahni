package com.helesto.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.model.OrderEntity;

/**
 * Comprehensive Risk Management Service
 * - Pre-trade risk checks (position limits, order size, concentration)
 * - Real-time P&L monitoring
 * - Kill switch functionality
 * - Risk alerts and breaches
 * - Client-level and firm-level risk controls
 */
@ApplicationScoped
public class RiskManagementService {

    private static final Logger LOG = LoggerFactory.getLogger(RiskManagementService.class);
    
    @Inject
    ReferenceDataService referenceDataService;
    
    @Inject
    TelemetryService telemetryService;
    
    // Kill switch - stops all trading
    private final AtomicBoolean tradingHalted = new AtomicBoolean(false);
    private volatile String haltReason = null;
    private volatile LocalDateTime haltTime = null;
    
    // Risk limits configuration
    private final Map<String, ClientRiskLimits> clientLimits = new ConcurrentHashMap<>();
    private FirmRiskLimits firmLimits = new FirmRiskLimits();
    
    // Current risk positions
    private final Map<String, ClientRiskPosition> clientPositions = new ConcurrentHashMap<>();
    private final FirmRiskPosition firmPosition = new FirmRiskPosition();
    
    // Risk breach history
    private final List<RiskBreach> breachHistory = Collections.synchronizedList(new ArrayList<>());
    
    // Alert thresholds (% of limit)
    private static final double ALERT_THRESHOLD = 0.80; // 80% of limit triggers alert
    private static final double WARNING_THRESHOLD = 0.90; // 90% of limit triggers warning
    
    @PostConstruct
    public void init() {
        LOG.info("Initializing Risk Management Service...");
        initializeDefaultLimits();
        LOG.info("Risk Management Service initialized");
    }
    
    private void initializeDefaultLimits() {
        // Default firm-level limits
        firmLimits.maxDailyOrderCount = 1_000_000L;
        firmLimits.maxDailyNotional = 10_000_000_000.0; // $10B
        firmLimits.maxOpenOrders = 100_000L;
        firmLimits.maxPositionValue = 5_000_000_000.0; // $5B
        firmLimits.maxConcentrationPerSymbol = 0.20; // 20% max in single symbol
        firmLimits.maxDailyLoss = 50_000_000.0; // -$50M
        
        // Default client limits (can be customized)
        String[] defaultClients = {"CLIENT001", "CLIENT002", "BROKER"};
        for (String clientId : defaultClients) {
            ClientRiskLimits limits = new ClientRiskLimits();
            limits.clientId = clientId;
            limits.maxDailyOrderCount = 50_000L;
            limits.maxDailyNotional = 100_000_000.0; // $100M
            limits.maxOpenOrders = 5_000L;
            limits.maxPositionPerSymbol = 1_000_000L; // 1M shares per symbol
            limits.maxOrderSize = 100_000L; // 100K shares per order
            limits.maxOrderNotional = 10_000_000.0; // $10M per order
            limits.maxDailyLoss = 1_000_000.0; // -$1M
            limits.enabled = true;
            clientLimits.put(clientId, limits);
        }
    }
    
    // ==================== Pre-Trade Risk Checks ====================
    
    /**
     * Perform comprehensive pre-trade risk check
     * @return RiskCheckResult with approval status and any issues
     */
    public RiskCheckResult checkOrderRisk(OrderEntity order) {
        RiskCheckResult result = new RiskCheckResult();
        result.orderId = order.getClOrdId();
        result.clientId = order.getClientId();
        result.timestamp = LocalDateTime.now();
        
        // 1. Check kill switch
        if (tradingHalted.get()) {
            result.approved = false;
            result.rejectReason = "TRADING_HALTED";
            result.message = "Trading is currently halted: " + haltReason;
            recordBreach("KILL_SWITCH", null, order.getClientId(), result.message);
            return result;
        }
        
        // 2. Calculate order notional
        double orderNotional = calculateNotional(order);
        
        // 3. Check firm-level limits
        RiskCheckResult firmCheck = checkFirmLimits(order, orderNotional);
        if (!firmCheck.approved) {
            return firmCheck;
        }
        
        // 4. Check client-level limits
        RiskCheckResult clientCheck = checkClientLimits(order, orderNotional);
        if (!clientCheck.approved) {
            return clientCheck;
        }
        
        // 5. Check symbol-specific limits
        RiskCheckResult symbolCheck = checkSymbolLimits(order, orderNotional);
        if (!symbolCheck.approved) {
            return symbolCheck;
        }
        
        // 6. Check for suspicious patterns (optional)
        checkSuspiciousPatterns(order, result);
        
        result.approved = true;
        result.message = "Order passed all risk checks";
        
        return result;
    }
    
    /**
     * Simplified pre-trade risk check for use by orchestrator
     * @return RiskCheckResult with approval status
     */
    public RiskCheckResult preTradeRiskCheck(String clientId, String symbol, String side, long quantity, Double price) {
        // Create a temporary order for risk checking
        OrderEntity order = new OrderEntity();
        order.setClOrdId(UUID.randomUUID().toString());
        order.setClientId(clientId);
        order.setSymbol(symbol);
        order.setSide(side);
        order.setQuantity(quantity);
        order.setPrice(price);
        order.setOrderType("LIMIT");
        
        return checkOrderRisk(order);
    }
    
    private double calculateNotional(OrderEntity order) {
        Double price = order.getPrice();
        if (price == null || price <= 0) {
            // For market orders, use current market price
            ReferenceDataService.MarketData md = referenceDataService.getMarketData(order.getSymbol());
            price = md != null ? md.lastPrice : 100.0;
        }
        return price * order.getQuantity();
    }
    
    private RiskCheckResult checkFirmLimits(OrderEntity order, double orderNotional) {
        RiskCheckResult result = new RiskCheckResult();
        result.orderId = order.getClOrdId();
        result.approved = true;
        
        // Check daily order count
        if (firmPosition.dailyOrderCount.get() + 1 > firmLimits.maxDailyOrderCount) {
            result.approved = false;
            result.rejectReason = "FIRM_DAILY_ORDER_LIMIT";
            result.message = String.format("Firm daily order limit exceeded: %d / %d",
                    firmPosition.dailyOrderCount.get(), firmLimits.maxDailyOrderCount);
            recordBreach("FIRM_DAILY_ORDERS", order.getSymbol(), order.getClientId(), result.message);
            return result;
        }
        
        // Check daily notional
        if (firmPosition.dailyNotional.get() + orderNotional > firmLimits.maxDailyNotional) {
            result.approved = false;
            result.rejectReason = "FIRM_DAILY_NOTIONAL_LIMIT";
            result.message = String.format("Firm daily notional limit exceeded: %.2f / %.2f",
                    firmPosition.dailyNotional.get() + orderNotional, firmLimits.maxDailyNotional);
            recordBreach("FIRM_DAILY_NOTIONAL", order.getSymbol(), order.getClientId(), result.message);
            return result;
        }
        
        // Check open orders
        if (firmPosition.openOrders.get() + 1 > firmLimits.maxOpenOrders) {
            result.approved = false;
            result.rejectReason = "FIRM_OPEN_ORDERS_LIMIT";
            result.message = String.format("Firm open orders limit exceeded: %d / %d",
                    firmPosition.openOrders.get(), firmLimits.maxOpenOrders);
            recordBreach("FIRM_OPEN_ORDERS", order.getSymbol(), order.getClientId(), result.message);
            return result;
        }
        
        // Alert on approaching limits
        double orderCountPct = (double) firmPosition.dailyOrderCount.get() / firmLimits.maxDailyOrderCount;
        if (orderCountPct > WARNING_THRESHOLD) {
            LOG.warn("Firm order count at {}% of limit", String.format("%.1f", orderCountPct * 100));
            telemetryService.recordWarning();
        } else if (orderCountPct > ALERT_THRESHOLD) {
            LOG.info("Firm order count approaching limit: {}%", String.format("%.1f", orderCountPct * 100));
        }
        
        return result;
    }
    
    private RiskCheckResult checkClientLimits(OrderEntity order, double orderNotional) {
        RiskCheckResult result = new RiskCheckResult();
        result.orderId = order.getClOrdId();
        result.clientId = order.getClientId();
        result.approved = true;
        
        String clientId = order.getClientId();
        if (clientId == null || clientId.isEmpty()) {
            clientId = "DEFAULT";
        }
        
        // Get or create client limits
        ClientRiskLimits limits = clientLimits.computeIfAbsent(clientId, k -> createDefaultClientLimits(k));
        
        if (!limits.enabled) {
            // Client trading is disabled
            result.approved = false;
            result.rejectReason = "CLIENT_DISABLED";
            result.message = "Trading disabled for client: " + clientId;
            return result;
        }
        
        // Get or create client position
        ClientRiskPosition position = clientPositions.computeIfAbsent(clientId, k -> new ClientRiskPosition(k));
        
        // Check single order size
        if (order.getQuantity() > limits.maxOrderSize) {
            result.approved = false;
            result.rejectReason = "ORDER_SIZE_LIMIT";
            result.message = String.format("Order size %d exceeds limit %d for client %s",
                    order.getQuantity(), limits.maxOrderSize, clientId);
            recordBreach("CLIENT_ORDER_SIZE", order.getSymbol(), clientId, result.message);
            return result;
        }
        
        // Check single order notional
        if (orderNotional > limits.maxOrderNotional) {
            result.approved = false;
            result.rejectReason = "ORDER_NOTIONAL_LIMIT";
            result.message = String.format("Order notional %.2f exceeds limit %.2f for client %s",
                    orderNotional, limits.maxOrderNotional, clientId);
            recordBreach("CLIENT_ORDER_NOTIONAL", order.getSymbol(), clientId, result.message);
            return result;
        }
        
        // Check daily order count
        if (position.dailyOrderCount.get() + 1 > limits.maxDailyOrderCount) {
            result.approved = false;
            result.rejectReason = "CLIENT_DAILY_ORDER_LIMIT";
            result.message = String.format("Daily order limit exceeded for client %s: %d / %d",
                    clientId, position.dailyOrderCount.get(), limits.maxDailyOrderCount);
            recordBreach("CLIENT_DAILY_ORDERS", order.getSymbol(), clientId, result.message);
            return result;
        }
        
        // Check daily notional
        if (position.dailyNotional.get() + orderNotional > limits.maxDailyNotional) {
            result.approved = false;
            result.rejectReason = "CLIENT_DAILY_NOTIONAL_LIMIT";
            result.message = String.format("Daily notional limit exceeded for client %s: %.2f / %.2f",
                    clientId, position.dailyNotional.get() + orderNotional, limits.maxDailyNotional);
            recordBreach("CLIENT_DAILY_NOTIONAL", order.getSymbol(), clientId, result.message);
            return result;
        }
        
        // Check daily loss limit
        if (position.realizedPnL.get() < -limits.maxDailyLoss) {
            result.approved = false;
            result.rejectReason = "CLIENT_LOSS_LIMIT";
            result.message = String.format("Daily loss limit exceeded for client %s: %.2f",
                    clientId, (double) position.realizedPnL.get());
            recordBreach("CLIENT_LOSS_LIMIT", order.getSymbol(), clientId, result.message);
            return result;
        }
        
        return result;
    }
    
    private RiskCheckResult checkSymbolLimits(OrderEntity order, double orderNotional) {
        RiskCheckResult result = new RiskCheckResult();
        result.orderId = order.getClOrdId();
        result.approved = true;
        
        String symbol = order.getSymbol();
        
        // Check if symbol is tradeable
        if (!referenceDataService.isValidSymbol(symbol)) {
            result.approved = false;
            result.rejectReason = "INVALID_SYMBOL";
            result.message = "Symbol not recognized: " + symbol;
            return result;
        }
        
        ReferenceDataService.Security security = referenceDataService.getSecurity(symbol);
        if (security != null && !security.tradeable) {
            result.approved = false;
            result.rejectReason = "SYMBOL_NOT_TRADEABLE";
            result.message = "Trading suspended for symbol: " + symbol;
            return result;
        }
        
        // Check concentration limit at firm level
        double symbolPosition = firmPosition.positionBySymbol.getOrDefault(symbol, 0.0);
        double totalPosition = firmPosition.totalPositionValue.get();
        
        if (totalPosition > 0) {
            double concentration = (symbolPosition + orderNotional) / (totalPosition + orderNotional);
            if (concentration > firmLimits.maxConcentrationPerSymbol) {
                result.approved = false;
                result.rejectReason = "CONCENTRATION_LIMIT";
                result.message = String.format("Concentration limit exceeded for %s: %.2f%% > %.2f%%",
                        symbol, concentration * 100, firmLimits.maxConcentrationPerSymbol * 100);
                recordBreach("CONCENTRATION", symbol, order.getClientId(), result.message);
                return result;
            }
        }
        
        return result;
    }
    
    private void checkSuspiciousPatterns(OrderEntity order, RiskCheckResult result) {
        // Check for rapid order entry (possible spoofing)
        // This is a placeholder for more sophisticated pattern detection
        String clientId = order.getClientId();
        if (clientId != null) {
            ClientRiskPosition position = clientPositions.get(clientId);
            if (position != null) {
                long recentOrders = position.recentOrderCount.get();
                if (recentOrders > 100) { // More than 100 orders in short window
                    result.warnings.add("High order frequency detected - review for spoofing");
                    LOG.warn("High order frequency for client {}: {} orders", clientId, recentOrders);
                }
            }
        }
    }
    
    private ClientRiskLimits createDefaultClientLimits(String clientId) {
        ClientRiskLimits limits = new ClientRiskLimits();
        limits.clientId = clientId;
        limits.maxDailyOrderCount = 10_000L;
        limits.maxDailyNotional = 50_000_000.0;
        limits.maxOpenOrders = 1_000L;
        limits.maxPositionPerSymbol = 100_000L;
        limits.maxOrderSize = 50_000L;
        limits.maxOrderNotional = 5_000_000.0;
        limits.maxDailyLoss = 500_000.0;
        limits.enabled = true;
        return limits;
    }
    
    // ==================== Position Updates ====================
    
    /**
     * Record order submission for risk tracking
     */
    public void onOrderSubmitted(OrderEntity order, double notional) {
        String clientId = order.getClientId() != null ? order.getClientId() : "DEFAULT";
        
        // Update firm position
        firmPosition.dailyOrderCount.incrementAndGet();
        firmPosition.dailyNotional.addAndGet((long) notional);
        firmPosition.openOrders.incrementAndGet();
        
        // Update client position
        ClientRiskPosition clientPos = clientPositions.computeIfAbsent(clientId, k -> new ClientRiskPosition(k));
        clientPos.dailyOrderCount.incrementAndGet();
        clientPos.dailyNotional.addAndGet((long) notional);
        clientPos.openOrders.incrementAndGet();
        clientPos.recentOrderCount.incrementAndGet();
    }
    
    /**
     * Record order fill for risk tracking
     */
    public void onOrderFilled(String clientId, String symbol, String side, 
                              long quantity, double price, double fillNotional) {
        if (clientId == null) clientId = "DEFAULT";
        
        // Update firm position
        firmPosition.openOrders.decrementAndGet();
        firmPosition.dailyVolume.addAndGet(quantity);
        
        double positionDelta = "BUY".equals(side) || "1".equals(side) ? fillNotional : -fillNotional;
        firmPosition.positionBySymbol.merge(symbol, positionDelta, Double::sum);
        firmPosition.totalPositionValue.addAndGet((long) Math.abs(positionDelta));
        
        // Update client position
        ClientRiskPosition clientPos = clientPositions.computeIfAbsent(clientId, k -> new ClientRiskPosition(k));
        clientPos.openOrders.decrementAndGet();
        clientPos.dailyVolume.addAndGet(quantity);
        clientPos.positionBySymbol.merge(symbol, positionDelta, Double::sum);
    }
    
    /**
     * Record order cancellation
     */
    public void onOrderCancelled(String clientId) {
        if (clientId == null) clientId = "DEFAULT";
        
        firmPosition.openOrders.decrementAndGet();
        
        ClientRiskPosition clientPos = clientPositions.get(clientId);
        if (clientPos != null) {
            clientPos.openOrders.decrementAndGet();
        }
    }
    
    /**
     * Record P&L update
     */
    public void onPnLUpdate(String clientId, double realizedPnL) {
        if (clientId == null) clientId = "DEFAULT";
        
        ClientRiskPosition clientPos = clientPositions.computeIfAbsent(clientId, k -> new ClientRiskPosition(k));
        clientPos.realizedPnL.set((long) realizedPnL);
        
        // Check for loss limit
        ClientRiskLimits limits = clientLimits.get(clientId);
        if (limits != null && realizedPnL < -limits.maxDailyLoss) {
            LOG.error("Client {} exceeded daily loss limit: {}", clientId, realizedPnL);
            recordBreach("LOSS_LIMIT_EXCEEDED", null, clientId, 
                    String.format("P&L %.2f exceeded limit %.2f", realizedPnL, -limits.maxDailyLoss));
        }
    }
    
    // ==================== Kill Switch ====================
    
    /**
     * Halt all trading immediately
     */
    public void haltTrading(String reason) {
        tradingHalted.set(true);
        haltReason = reason;
        haltTime = LocalDateTime.now();
        LOG.error("TRADING HALTED: {}", reason);
        recordBreach("KILL_SWITCH_ACTIVATED", null, null, reason);
    }
    
    /**
     * Resume trading
     */
    public void resumeTrading() {
        LOG.info("Trading resumed after halt. Previous reason: {}", haltReason);
        tradingHalted.set(false);
        haltReason = null;
        haltTime = null;
    }
    
    public boolean isTradingHalted() {
        return tradingHalted.get();
    }
    
    public String getHaltReason() {
        return haltReason;
    }
    
    // ==================== Limit Management ====================
    
    public void updateClientLimits(String clientId, ClientRiskLimits limits) {
        limits.clientId = clientId;
        clientLimits.put(clientId, limits);
        LOG.info("Updated risk limits for client {}", clientId);
    }
    
    public void disableClient(String clientId) {
        ClientRiskLimits limits = clientLimits.get(clientId);
        if (limits != null) {
            limits.enabled = false;
            LOG.warn("Disabled trading for client {}", clientId);
        }
    }
    
    public void enableClient(String clientId) {
        ClientRiskLimits limits = clientLimits.get(clientId);
        if (limits != null) {
            limits.enabled = true;
            LOG.info("Enabled trading for client {}", clientId);
        }
    }
    
    // ==================== Metrics & Reporting ====================
    
    public Map<String, Object> getRiskMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        // Trading status
        metrics.put("tradingHalted", tradingHalted.get());
        metrics.put("haltReason", haltReason);
        metrics.put("haltTime", haltTime != null ? haltTime.toString() : null);
        
        // Firm metrics
        Map<String, Object> firmMetrics = new HashMap<>();
        firmMetrics.put("dailyOrderCount", firmPosition.dailyOrderCount.get());
        firmMetrics.put("dailyNotional", firmPosition.dailyNotional.get());
        firmMetrics.put("openOrders", firmPosition.openOrders.get());
        firmMetrics.put("dailyVolume", firmPosition.dailyVolume.get());
        firmMetrics.put("totalPositionValue", firmPosition.totalPositionValue.get());
        firmMetrics.put("positionBySymbol", new HashMap<>(firmPosition.positionBySymbol));
        metrics.put("firm", firmMetrics);
        
        // Client metrics
        Map<String, Object> clientMetrics = new HashMap<>();
        for (Map.Entry<String, ClientRiskPosition> entry : clientPositions.entrySet()) {
            Map<String, Object> pos = new HashMap<>();
            ClientRiskPosition p = entry.getValue();
            pos.put("dailyOrderCount", p.dailyOrderCount.get());
            pos.put("openOrders", p.openOrders.get());
            pos.put("dailyVolume", p.dailyVolume.get());
            pos.put("realizedPnL", p.realizedPnL.get());
            clientMetrics.put(entry.getKey(), pos);
        }
        metrics.put("clients", clientMetrics);
        
        // Breach history (last 100)
        List<Map<String, Object>> breaches = new ArrayList<>();
        int startIdx = Math.max(0, breachHistory.size() - 100);
        for (int i = startIdx; i < breachHistory.size(); i++) {
            RiskBreach breach = breachHistory.get(i);
            Map<String, Object> b = new HashMap<>();
            b.put("type", breach.type);
            b.put("symbol", breach.symbol);
            b.put("clientId", breach.clientId);
            b.put("message", breach.message);
            b.put("timestamp", breach.timestamp.toString());
            breaches.add(b);
        }
        metrics.put("recentBreaches", breaches);
        
        return metrics;
    }
    
    public ClientRiskLimits getClientLimits(String clientId) {
        return clientLimits.get(clientId);
    }
    
    public FirmRiskLimits getFirmLimits() {
        return firmLimits;
    }
    
    /**
     * Reset daily counters (call at start of trading day)
     */
    public void resetDailyCounters() {
        LOG.info("Resetting daily risk counters");
        
        firmPosition.dailyOrderCount.set(0);
        firmPosition.dailyNotional.set(0);
        firmPosition.dailyVolume.set(0);
        
        for (ClientRiskPosition pos : clientPositions.values()) {
            pos.dailyOrderCount.set(0);
            pos.dailyNotional.set(0);
            pos.dailyVolume.set(0);
            pos.realizedPnL.set(0);
            pos.recentOrderCount.set(0);
        }
    }
    
    private void recordBreach(String type, String symbol, String clientId, String message) {
        RiskBreach breach = new RiskBreach();
        breach.type = type;
        breach.symbol = symbol;
        breach.clientId = clientId;
        breach.message = message;
        breach.timestamp = LocalDateTime.now();
        breachHistory.add(breach);
        telemetryService.recordError();
        LOG.warn("Risk breach: {} - {} - {}", type, clientId, message);
    }
    
    // ==================== Data Classes ====================
    
    public static class RiskCheckResult {
        public String orderId;
        public String clientId;
        public boolean approved = false;
        public String rejectReason;
        public String message;
        public List<String> warnings = new ArrayList<>();
        public LocalDateTime timestamp;
        
        public boolean isApproved() { return approved; }
        public String getRejectReason() { return rejectReason; }
        public String getMessage() { return message; }
        
        @Override
        public String toString() {
            return String.format("RiskCheckResult{approved=%s, reason=%s, message='%s'}",
                    approved, rejectReason, message);
        }
    }
    
    public static class ClientRiskLimits {
        public String clientId;
        public long maxDailyOrderCount;
        public double maxDailyNotional;
        public long maxOpenOrders;
        public long maxPositionPerSymbol;
        public long maxOrderSize;
        public double maxOrderNotional;
        public double maxDailyLoss;
        public boolean enabled = true;
    }
    
    public static class FirmRiskLimits {
        public long maxDailyOrderCount;
        public double maxDailyNotional;
        public long maxOpenOrders;
        public double maxPositionValue;
        public double maxConcentrationPerSymbol;
        public double maxDailyLoss;
    }
    
    public static class ClientRiskPosition {
        public final String clientId;
        public final AtomicLong dailyOrderCount = new AtomicLong(0);
        public final AtomicLong dailyNotional = new AtomicLong(0);
        public final AtomicLong openOrders = new AtomicLong(0);
        public final AtomicLong dailyVolume = new AtomicLong(0);
        public final AtomicLong realizedPnL = new AtomicLong(0);
        public final AtomicLong recentOrderCount = new AtomicLong(0);
        public final Map<String, Double> positionBySymbol = new ConcurrentHashMap<>();
        
        public ClientRiskPosition(String clientId) {
            this.clientId = clientId;
        }
    }
    
    public static class FirmRiskPosition {
        public final AtomicLong dailyOrderCount = new AtomicLong(0);
        public final AtomicLong dailyNotional = new AtomicLong(0);
        public final AtomicLong openOrders = new AtomicLong(0);
        public final AtomicLong dailyVolume = new AtomicLong(0);
        public final AtomicLong totalPositionValue = new AtomicLong(0);
        public final Map<String, Double> positionBySymbol = new ConcurrentHashMap<>();
    }
    
    public static class RiskBreach {
        public String type;
        public String symbol;
        public String clientId;
        public String message;
        public LocalDateTime timestamp;
    }
}
