package com.helesto.service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Circuit Breaker Service for Trading
 * - Symbol-level circuit breakers (LULD - Limit Up/Limit Down)
 * - Market-wide circuit breakers (S&P 500 based)
 * - Volatility halts
 * - News-based halts
 * - Automatic cooldown and resume
 */
@ApplicationScoped
public class CircuitBreakerService {

    private static final Logger LOG = LoggerFactory.getLogger(CircuitBreakerService.class);
    
    @Inject
    ReferenceDataService referenceDataService;
    
    @Inject
    TelemetryService telemetryService;
    
    // Circuit breaker states per symbol
    private final Map<String, SymbolCircuitBreaker> symbolBreakers = new ConcurrentHashMap<>();
    
    // Market-wide circuit breaker state
    private final MarketCircuitBreaker marketBreaker = new MarketCircuitBreaker();
    
    // LULD bands per symbol  
    private final Map<String, LULDBands> luldBands = new ConcurrentHashMap<>();
    
    // Event history
    private final List<CircuitBreakerEvent> eventHistory = Collections.synchronizedList(new ArrayList<>());
    
    // Configuration
    private CircuitBreakerConfig config = new CircuitBreakerConfig();
    
    // Scheduled executor for automatic band updates
    private ScheduledExecutorService scheduler;
    
    // Market index value for market-wide breakers
    private volatile double marketIndexValue = 4500.0; // S&P 500 baseline
    private volatile double marketIndexOpen = 4500.0;
    
    @PostConstruct
    public void init() {
        LOG.info("Initializing Circuit Breaker Service...");
        
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "circuit-breaker-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        // Update LULD bands every 15 seconds
        scheduler.scheduleAtFixedRate(this::updateLULDBands, 15, 15, TimeUnit.SECONDS);
        
        // Initialize bands for major symbols
        initializeLULDBands();
        
        LOG.info("Circuit Breaker Service initialized");
    }
    
    @PreDestroy
    public void cleanup() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
    
    private void initializeLULDBands() {
        String[] symbols = {"AAPL", "MSFT", "GOOGL", "AMZN", "META", "NVDA", "TSLA"};
        for (String symbol : symbols) {
            ReferenceDataService.MarketData md = referenceDataService.getMarketData(symbol);
            if (md != null) {
                calculateLULDBands(symbol, md.lastPrice);
            }
        }
    }
    
    // ==================== Price Checking ====================
    
    /**
     * Check if a trade price is within circuit breaker limits
     * @return true if price is acceptable, false if circuit breaker should trigger
     */
    public CircuitBreakerCheckResult checkPrice(String symbol, double price) {
        CircuitBreakerCheckResult result = new CircuitBreakerCheckResult();
        result.symbol = symbol;
        result.price = price;
        result.timestamp = LocalDateTime.now();
        
        // Check market-wide circuit breaker first
        if (marketBreaker.isHalted) {
            result.allowed = false;
            result.reason = "MARKET_WIDE_HALT";
            result.level = marketBreaker.currentLevel;
            result.message = "Market-wide trading halt in effect - Level " + marketBreaker.currentLevel;
            return result;
        }
        
        // Check symbol-specific circuit breaker
        SymbolCircuitBreaker breaker = symbolBreakers.get(symbol);
        if (breaker != null && breaker.isHalted) {
            result.allowed = false;
            result.reason = breaker.haltReason;
            result.message = String.format("Trading halted for %s: %s", symbol, breaker.haltMessage);
            result.resumeTime = breaker.expectedResumeTime;
            return result;
        }
        
        // Check LULD bands
        LULDBands bands = luldBands.get(symbol);
        if (bands != null) {
            if (price <= bands.lowerLimit) {
                result.allowed = false;
                result.reason = "LULD_LOWER_BAND";
                result.message = String.format("Price %.2f at or below lower LULD band %.2f", price, bands.lowerLimit);
                triggerLULDHalt(symbol, "LOWER", price, bands);
                return result;
            }
            if (price >= bands.upperLimit) {
                result.allowed = false;
                result.reason = "LULD_UPPER_BAND";
                result.message = String.format("Price %.2f at or above upper LULD band %.2f", price, bands.upperLimit);
                triggerLULDHalt(symbol, "UPPER", price, bands);
                return result;
            }
            
            // Check if approaching limits (warning)
            double percentToLower = (price - bands.lowerLimit) / bands.referencePrice;
            double percentToUpper = (bands.upperLimit - price) / bands.referencePrice;
            
            if (percentToLower < 0.02 || percentToUpper < 0.02) {
                result.warning = true;
                result.warningMessage = String.format("Price %.2f approaching LULD band (lower: %.2f, upper: %.2f)",
                        price, bands.lowerLimit, bands.upperLimit);
            }
        }
        
        // Check rapid price movement
        RapidMoveResult moveResult = checkRapidPriceMovement(symbol, price);
        if (moveResult.triggered) {
            result.allowed = false;
            result.reason = "RAPID_PRICE_MOVEMENT";
            result.message = moveResult.message;
            return result;
        }
        
        result.allowed = true;
        result.message = "Price within acceptable range";
        return result;
    }
    
    private RapidMoveResult checkRapidPriceMovement(String symbol, double price) {
        RapidMoveResult result = new RapidMoveResult();
        
        SymbolCircuitBreaker breaker = symbolBreakers.computeIfAbsent(symbol, k -> new SymbolCircuitBreaker(k));
        
        // Record price
        long now = System.currentTimeMillis();
        breaker.priceHistory.add(new PricePoint(now, price));
        
        // Remove old prices (older than 5 minutes)
        breaker.priceHistory.removeIf(p -> now - p.timestamp > 300_000);
        
        if (breaker.priceHistory.size() >= 2) {
            PricePoint oldest = breaker.priceHistory.get(0);
            PricePoint newest = breaker.priceHistory.get(breaker.priceHistory.size() - 1);
            
            long timeDiffMs = newest.timestamp - oldest.timestamp;
            if (timeDiffMs > 0 && timeDiffMs < 60_000) { // Within 1 minute
                double priceChange = Math.abs(newest.price - oldest.price) / oldest.price;
                
                // Trigger if >10% move in 1 minute
                if (priceChange > config.rapidMoveThreshold) {
                    result.triggered = true;
                    result.message = String.format("Rapid price movement: %.2f%% in %d seconds",
                            priceChange * 100, timeDiffMs / 1000);
                    
                    triggerVolatilityHalt(symbol, priceChange);
                }
            }
        }
        
        return result;
    }
    
    // ==================== Halt Triggers ====================
    
    private void triggerLULDHalt(String symbol, String direction, double triggerPrice, LULDBands bands) {
        SymbolCircuitBreaker breaker = symbolBreakers.computeIfAbsent(symbol, k -> new SymbolCircuitBreaker(k));
        
        if (!breaker.isHalted) {
            breaker.isHalted = true;
            breaker.haltReason = "LULD_" + direction;
            breaker.haltTime = LocalDateTime.now();
            breaker.haltTriggerPrice = triggerPrice;
            breaker.haltMessage = String.format("LULD %s band triggered at %.2f (band: %.2f)",
                    direction, triggerPrice, direction.equals("LOWER") ? bands.lowerLimit : bands.upperLimit);
            
            // Schedule resume after 5 minutes
            breaker.expectedResumeTime = breaker.haltTime.plusMinutes(config.luldHaltDurationMinutes);
            scheduleResume(symbol, config.luldHaltDurationMinutes);
            
            recordEvent(symbol, "LULD_HALT", breaker.haltMessage);
            LOG.warn("LULD halt triggered for {}: {}", symbol, breaker.haltMessage);
        }
    }
    
    private void triggerVolatilityHalt(String symbol, double volatility) {
        SymbolCircuitBreaker breaker = symbolBreakers.computeIfAbsent(symbol, k -> new SymbolCircuitBreaker(k));
        
        if (!breaker.isHalted) {
            breaker.isHalted = true;
            breaker.haltReason = "VOLATILITY";
            breaker.haltTime = LocalDateTime.now();
            breaker.haltMessage = String.format("Volatility halt: %.2f%% movement detected", volatility * 100);
            
            // Schedule resume after 10 minutes for volatility halt
            breaker.expectedResumeTime = breaker.haltTime.plusMinutes(config.volatilityHaltDurationMinutes);
            scheduleResume(symbol, config.volatilityHaltDurationMinutes);
            
            recordEvent(symbol, "VOLATILITY_HALT", breaker.haltMessage);
            LOG.warn("Volatility halt triggered for {}: {}", symbol, breaker.haltMessage);
        }
    }
    
    /**
     * Trigger news-based trading halt (manual)
     */
    public void triggerNewsHalt(String symbol, String newsReason) {
        SymbolCircuitBreaker breaker = symbolBreakers.computeIfAbsent(symbol, k -> new SymbolCircuitBreaker(k));
        
        breaker.isHalted = true;
        breaker.haltReason = "NEWS";
        breaker.haltTime = LocalDateTime.now();
        breaker.haltMessage = "News halt: " + newsReason;
        breaker.expectedResumeTime = null; // Manual resume required
        
        recordEvent(symbol, "NEWS_HALT", breaker.haltMessage);
        LOG.warn("News halt triggered for {}: {}", symbol, newsReason);
    }
    
    /**
     * Resume trading for a symbol
     */
    public void resumeTrading(String symbol) {
        SymbolCircuitBreaker breaker = symbolBreakers.get(symbol);
        if (breaker != null && breaker.isHalted) {
            breaker.isHalted = false;
            breaker.resumeCount++;
            
            LOG.info("Trading resumed for {}", symbol);
            recordEvent(symbol, "TRADING_RESUMED", "Trading resumed after " + breaker.haltReason + " halt");
            
            // Recalculate LULD bands on resume
            ReferenceDataService.MarketData md = referenceDataService.getMarketData(symbol);
            if (md != null) {
                calculateLULDBands(symbol, md.lastPrice);
            }
        }
    }
    
    private void scheduleResume(String symbol, int delayMinutes) {
        scheduler.schedule(() -> {
            resumeTrading(symbol);
        }, delayMinutes, TimeUnit.MINUTES);
    }
    
    // ==================== Market-Wide Circuit Breakers ====================
    
    /**
     * Update market index value and check for market-wide circuit breakers
     */
    public void updateMarketIndex(double indexValue) {
        this.marketIndexValue = indexValue;
        
        if (marketIndexOpen <= 0) {
            marketIndexOpen = indexValue;
            return;
        }
        
        double changePercent = (indexValue - marketIndexOpen) / marketIndexOpen * 100;
        
        // Level 1: 7% decline
        if (changePercent <= -7.0 && marketBreaker.currentLevel < 1) {
            triggerMarketWideHalt(1, changePercent);
        }
        // Level 2: 13% decline
        else if (changePercent <= -13.0 && marketBreaker.currentLevel < 2) {
            triggerMarketWideHalt(2, changePercent);
        }
        // Level 3: 20% decline (trading halted for day)
        else if (changePercent <= -20.0 && marketBreaker.currentLevel < 3) {
            triggerMarketWideHalt(3, changePercent);
        }
    }
    
    private void triggerMarketWideHalt(int level, double changePercent) {
        marketBreaker.isHalted = true;
        marketBreaker.currentLevel = level;
        marketBreaker.haltTime = LocalDateTime.now();
        marketBreaker.triggerPercent = changePercent;
        
        String message = String.format("Market-wide circuit breaker Level %d triggered: %.2f%% decline", 
                level, changePercent);
        
        LOG.error(message);
        recordEvent("MARKET", "MARKET_WIDE_HALT_L" + level, message);
        
        // Schedule resume based on time of day and level
        if (level < 3) { // Level 3 = halt for rest of day
            int haltMinutes;
            switch (level) {
                case 1: haltMinutes = isAfter325pm() ? 0 : 15; break; // Level 1 after 3:25 PM doesn't halt
                case 2: haltMinutes = isAfter325pm() ? 0 : 15; break;
                default: haltMinutes = 0;
            }
            
            if (haltMinutes > 0) {
                marketBreaker.expectedResumeTime = marketBreaker.haltTime.plusMinutes(haltMinutes);
                scheduler.schedule(this::resumeMarketWideTrading, haltMinutes, TimeUnit.MINUTES);
            } else {
                marketBreaker.isHalted = false;
            }
        }
    }
    
    private boolean isAfter325pm() {
        LocalTime now = LocalTime.now();
        return now.isAfter(LocalTime.of(15, 25));
    }
    
    public void resumeMarketWideTrading() {
        if (marketBreaker.isHalted && marketBreaker.currentLevel < 3) {
            marketBreaker.isHalted = false;
            LOG.info("Market-wide trading resumed after Level {} halt", marketBreaker.currentLevel);
            recordEvent("MARKET", "MARKET_WIDE_RESUMED", "Trading resumed after Level " + marketBreaker.currentLevel + " halt");
        }
    }
    
    /**
     * Set market opening index value (call at market open)
     */
    public void setMarketOpenValue(double openValue) {
        this.marketIndexOpen = openValue;
        this.marketIndexValue = openValue;
        marketBreaker.currentLevel = 0;
        marketBreaker.isHalted = false;
    }
    
    // ==================== LULD Band Management ====================
    
    private void calculateLULDBands(String symbol, double referencePrice) {
        LULDBands bands = new LULDBands();
        bands.symbol = symbol;
        bands.referencePrice = referencePrice;
        bands.calculationTime = LocalDateTime.now();
        
        // Determine band percentage based on price and tier
        double bandPercent;
        if (referencePrice >= 3.00) {
            bandPercent = config.tier1BandPercent; // 5% for Tier 1
        } else if (referencePrice >= 0.75) {
            bandPercent = config.tier2BandPercent; // 20% for Tier 2
        } else {
            bandPercent = 0.75; // Lesser of 75% or $0.15
        }
        
        // Apply wider bands during market open/close
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 45)) || now.isAfter(LocalTime.of(15, 35))) {
            bandPercent *= 2; // Double bands during first 15 and last 25 minutes
        }
        
        bands.bandPercent = bandPercent;
        bands.upperLimit = referencePrice * (1 + bandPercent);
        bands.lowerLimit = referencePrice * (1 - bandPercent);
        
        luldBands.put(symbol, bands);
    }
    
    private void updateLULDBands() {
        for (String symbol : luldBands.keySet()) {
            ReferenceDataService.MarketData md = referenceDataService.getMarketData(symbol);
            if (md != null && md.lastPrice > 0) {
                calculateLULDBands(symbol, md.lastPrice);
            }
        }
    }
    
    // ==================== Status & Metrics ====================
    
    public boolean isTradingAllowed(String symbol) {
        if (marketBreaker.isHalted) {
            return false;
        }
        
        SymbolCircuitBreaker breaker = symbolBreakers.get(symbol);
        return breaker == null || !breaker.isHalted;
    }
    
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        
        // Market-wide status
        Map<String, Object> marketStatus = new HashMap<>();
        marketStatus.put("isHalted", marketBreaker.isHalted);
        marketStatus.put("currentLevel", marketBreaker.currentLevel);
        marketStatus.put("indexValue", marketIndexValue);
        marketStatus.put("indexOpen", marketIndexOpen);
        marketStatus.put("changePercent", marketIndexOpen > 0 ? 
                (marketIndexValue - marketIndexOpen) / marketIndexOpen * 100 : 0);
        if (marketBreaker.haltTime != null) {
            marketStatus.put("haltTime", marketBreaker.haltTime.toString());
        }
        status.put("market", marketStatus);
        
        // Symbol-level status
        Map<String, Object> symbolStatus = new HashMap<>();
        for (Map.Entry<String, SymbolCircuitBreaker> entry : symbolBreakers.entrySet()) {
            SymbolCircuitBreaker breaker = entry.getValue();
            if (breaker.isHalted) {
                Map<String, Object> bs = new HashMap<>();
                bs.put("isHalted", true);
                bs.put("reason", breaker.haltReason);
                bs.put("message", breaker.haltMessage);
                if (breaker.haltTime != null) {
                    bs.put("haltTime", breaker.haltTime.toString());
                }
                if (breaker.expectedResumeTime != null) {
                    bs.put("expectedResumeTime", breaker.expectedResumeTime.toString());
                }
                symbolStatus.put(entry.getKey(), bs);
            }
        }
        status.put("symbolHalts", symbolStatus);
        
        // LULD bands
        Map<String, Object> bandsStatus = new HashMap<>();
        for (Map.Entry<String, LULDBands> entry : luldBands.entrySet()) {
            LULDBands bands = entry.getValue();
            Map<String, Object> b = new HashMap<>();
            b.put("referencePrice", bands.referencePrice);
            b.put("upperLimit", bands.upperLimit);
            b.put("lowerLimit", bands.lowerLimit);
            b.put("bandPercent", bands.bandPercent * 100);
            bandsStatus.put(entry.getKey(), b);
        }
        status.put("luldBands", bandsStatus);
        
        // Recent events
        List<Map<String, Object>> events = new ArrayList<>();
        int startIdx = Math.max(0, eventHistory.size() - 50);
        for (int i = startIdx; i < eventHistory.size(); i++) {
            CircuitBreakerEvent event = eventHistory.get(i);
            Map<String, Object> e = new HashMap<>();
            e.put("symbol", event.symbol);
            e.put("type", event.type);
            e.put("message", event.message);
            e.put("timestamp", event.timestamp.toString());
            events.add(e);
        }
        status.put("recentEvents", events);
        
        return status;
    }
    
    public LULDBands getLULDBands(String symbol) {
        return luldBands.get(symbol);
    }
    
    private void recordEvent(String symbol, String type, String message) {
        CircuitBreakerEvent event = new CircuitBreakerEvent();
        event.symbol = symbol;
        event.type = type;
        event.message = message;
        event.timestamp = LocalDateTime.now();
        eventHistory.add(event);
        
        telemetryService.recordWarning();
    }
    
    // ==================== Configuration ====================
    
    public void updateConfig(CircuitBreakerConfig newConfig) {
        this.config = newConfig;
        LOG.info("Circuit breaker configuration updated");
    }
    
    // ==================== Data Classes ====================
    
    public static class CircuitBreakerCheckResult {
        public String symbol;
        public double price;
        public boolean allowed;
        public String reason;
        public String message;
        public int level;
        public boolean warning;
        public String warningMessage;
        public LocalDateTime resumeTime;
        public LocalDateTime timestamp;
    }
    
    public static class CircuitBreakerConfig {
        public double rapidMoveThreshold = 0.10; // 10% move triggers halt
        public int luldHaltDurationMinutes = 5;
        public int volatilityHaltDurationMinutes = 10;
        public double tier1BandPercent = 0.05; // 5% for Tier 1 securities
        public double tier2BandPercent = 0.20; // 20% for Tier 2 securities
    }
    
    public static class LULDBands {
        public String symbol;
        public double referencePrice;
        public double upperLimit;
        public double lowerLimit;
        public double bandPercent;
        public LocalDateTime calculationTime;
    }
    
    private static class SymbolCircuitBreaker {
        public final String symbol;
        public volatile boolean isHalted = false;
        public volatile String haltReason;
        public volatile String haltMessage;
        public volatile LocalDateTime haltTime;
        public volatile LocalDateTime expectedResumeTime;
        public volatile double haltTriggerPrice;
        public int resumeCount = 0;
        public final List<PricePoint> priceHistory = Collections.synchronizedList(new ArrayList<>());
        
        public SymbolCircuitBreaker(String symbol) {
            this.symbol = symbol;
        }
    }
    
    private static class MarketCircuitBreaker {
        public volatile boolean isHalted = false;
        public volatile int currentLevel = 0;
        public volatile LocalDateTime haltTime;
        public volatile LocalDateTime expectedResumeTime;
        public volatile double triggerPercent;
    }
    
    private static class PricePoint {
        public final long timestamp;
        public final double price;
        
        public PricePoint(long timestamp, double price) {
            this.timestamp = timestamp;
            this.price = price;
        }
    }
    
    private static class CircuitBreakerEvent {
        public String symbol;
        public String type;
        public String message;
        public LocalDateTime timestamp;
    }
    
    private static class RapidMoveResult {
        public boolean triggered = false;
        public String message;
    }
}
