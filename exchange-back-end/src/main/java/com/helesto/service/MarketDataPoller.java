package com.helesto.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.socket.WebSocketAggregator;

/**
 * G3-M1: Market Data Poller
 * - Flat-file reader with configurable polling interval
 * - Delta updates detection
 * - Efficient broadcast to subscribers
 * - Simulated price updates for demo
 */
@ApplicationScoped
public class MarketDataPoller {

    private static final Logger LOG = LoggerFactory.getLogger(MarketDataPoller.class);
    
    @Inject
    ReferenceDataService referenceDataService;
    
    @Inject
    WebSocketAggregator webSocketAggregator;
    
    @Inject
    TelemetryService telemetryService;

    @Inject
    BlackScholesPricingService pricingService;
    
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, MarketDataSnapshot> lastSnapshots = new ConcurrentHashMap<>();
    private final List<MarketDataListener> listeners = new CopyOnWriteArrayList<>();
    
    // Configuration
    private Path marketDataFile;
    private long pollIntervalMs = 1000; // 1 second default
    private boolean simulationMode = true; // Enable price simulation
    private final Random random = new Random();
    
    // Base prices for simulation
    private final Map<String, Double> basePrices = new ConcurrentHashMap<>();
    private final Map<String, List<OptionContract>> optionContractsByUnderlying = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void init() {
        LOG.info("Market Data Poller initializing...");
        initializeBasePrices();
        initializeOptionContracts();
        
        // Start simulation/polling
        if (simulationMode) {
            scheduler.scheduleAtFixedRate(this::simulatePriceUpdates, 
                    1000, pollIntervalMs, TimeUnit.MILLISECONDS);
            LOG.info("Market Data Poller started in SIMULATION mode with {}ms interval", pollIntervalMs);
        } else {
            scheduler.scheduleAtFixedRate(this::pollMarketDataFile, 
                    1000, pollIntervalMs, TimeUnit.MILLISECONDS);
            LOG.info("Market Data Poller started in FILE mode");
        }
    }
    
    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOG.info("Market Data Poller shutdown");
    }
    
    private void initializeBasePrices() {
        // Technology
        basePrices.put("AAPL", 178.50);
        basePrices.put("MSFT", 378.90);
        basePrices.put("GOOGL", 141.80);
        basePrices.put("NVDA", 721.30);
        basePrices.put("META", 485.20);
        basePrices.put("INTC", 42.85);
        basePrices.put("AMD", 165.40);
        basePrices.put("CRM", 275.30);
        basePrices.put("ORCL", 118.65);
        basePrices.put("CSCO", 48.90);
        basePrices.put("ADBE", 570.25);
        basePrices.put("IBM", 168.40);
        
        // Consumer
        basePrices.put("AMZN", 178.25);
        basePrices.put("TSLA", 201.45);
        basePrices.put("WMT", 165.80);
        basePrices.put("HD", 345.60);
        basePrices.put("NKE", 98.75);
        basePrices.put("MCD", 285.90);
        basePrices.put("SBUX", 92.30);
        basePrices.put("TGT", 138.45);
        basePrices.put("COST", 715.20);
        
        // Entertainment
        basePrices.put("DIS", 95.80);
        basePrices.put("NFLX", 485.70);
        basePrices.put("SPOT", 238.50);
        basePrices.put("WBD", 8.45);
        basePrices.put("PARA", 12.35);
        
        // Finance
        basePrices.put("JPM", 195.20);
        basePrices.put("V", 278.60);
        basePrices.put("MA", 458.30);
        basePrices.put("BAC", 35.75);
        basePrices.put("GS", 425.80);
        basePrices.put("MS", 92.45);
        basePrices.put("BLK", 785.60);
        basePrices.put("AXP", 215.30);
        basePrices.put("BRK.B", 385.90);
        
        // Healthcare
        basePrices.put("JNJ", 156.80);
        basePrices.put("UNH", 525.40);
        basePrices.put("PFE", 27.35);
        basePrices.put("MRK", 125.60);
        basePrices.put("ABBV", 168.90);
        basePrices.put("LLY", 765.30);
        basePrices.put("TMO", 535.80);
        
        // Consumer Staples
        basePrices.put("PG", 158.45);
        basePrices.put("KO", 58.90);
        basePrices.put("PEP", 168.30);
        basePrices.put("PM", 95.60);
        basePrices.put("CL", 82.45);
        
        // Industrial
        basePrices.put("BA", 205.80);
        basePrices.put("CAT", 295.60);
        basePrices.put("GE", 155.40);
        basePrices.put("HON", 198.75);
        basePrices.put("UPS", 148.30);
        basePrices.put("RTX", 98.65);
        basePrices.put("LMT", 475.30);
        basePrices.put("DE", 418.60);
        
        // Energy
        basePrices.put("XOM", 105.40);
        basePrices.put("CVX", 148.90);
        basePrices.put("COP", 115.30);
        basePrices.put("SLB", 48.75);
        basePrices.put("EOG", 125.40);
        basePrices.put("MPC", 165.80);
        basePrices.put("VLO", 148.20);
        
        // Telecommunications
        basePrices.put("T", 17.85);
        basePrices.put("VZ", 41.20);
        basePrices.put("TMUS", 168.45);
        basePrices.put("CMCSA", 42.80);
        basePrices.put("CHTR", 295.60);
        
        // Materials
        basePrices.put("LIN", 458.90);
        basePrices.put("FCX", 42.65);
        basePrices.put("NEM", 38.45);
        basePrices.put("SHW", 328.70);
        basePrices.put("DOW", 55.80);
        basePrices.put("APD", 285.40);
        
        // Real Estate
        basePrices.put("PLD", 128.45);
        basePrices.put("AMT", 215.60);
        basePrices.put("EQIX", 825.30);
        basePrices.put("SPG", 152.80);
        basePrices.put("O", 58.45);
        
        // Utilities
        basePrices.put("NEE", 78.90);
        basePrices.put("DUK", 102.45);
        basePrices.put("SO", 72.60);
        basePrices.put("D", 52.80);
        basePrices.put("AEP", 88.35);
        
        LOG.info("Initialized {} base prices for simulation", basePrices.size());
    }

    private void initializeOptionContracts() {
        optionContractsByUnderlying.clear();

        Collection<ReferenceDataService.Security> optionSecurities = referenceDataService.getSecuritiesByType("OPTION");
        for (ReferenceDataService.Security security : optionSecurities) {
            if (security.underlyingSymbol == null || security.strikePrice == null || security.optionType == null || security.expiryDate == null) {
                continue;
            }

            OptionContract contract = new OptionContract();
            contract.symbol = security.symbol;
            contract.underlying = security.underlyingSymbol;
            contract.optionType = security.optionType;
            contract.strike = security.strikePrice;
            try {
                contract.expiry = LocalDate.parse(security.expiryDate);
            } catch (Exception ex) {
                continue;
            }

            optionContractsByUnderlying
                .computeIfAbsent(contract.underlying, k -> new CopyOnWriteArrayList<>())
                .add(contract);
        }

        optionContractsByUnderlying.values().forEach(list ->
            list.sort(Comparator.comparingDouble((OptionContract c) -> c.strike)
                .thenComparing(c -> c.optionType)
                .thenComparing(c -> c.expiry))
        );

        LOG.info("Loaded {} underlyings with option contracts", optionContractsByUnderlying.size());
    }

    private void updateOptionPricesForUnderlying(String underlying, double spotPrice, long timestamp) {
        List<OptionContract> contracts = optionContractsByUnderlying.get(underlying);
        if (contracts == null || contracts.isEmpty()) {
            return;
        }

        for (OptionContract contract : contracts) {
            double timeToExpiryDays = Math.max(1, ChronoUnit.DAYS.between(LocalDate.now(), contract.expiry));
            double timeToExpiry = timeToExpiryDays / 365.0;
            double volatility = estimateOptionVolatility(spotPrice, contract.strike);
            boolean isCall = "CALL".equalsIgnoreCase(contract.optionType);
            double theoPrice = isCall
                ? pricingService.callPrice(spotPrice, contract.strike, timeToExpiry, 0.05, volatility)
                : pricingService.putPrice(spotPrice, contract.strike, timeToExpiry, 0.05, volatility);

            double boundedPrice = Math.max(0.01, theoPrice);
            double spread = Math.max(0.01, boundedPrice * 0.01);
            double bid = Math.max(0.01, boundedPrice - (spread / 2.0));
            double ask = boundedPrice + (spread / 2.0);

            MarketDataSnapshot last = lastSnapshots.get(contract.symbol);
            double open = last != null ? last.open : boundedPrice;

            MarketDataSnapshot snapshot = new MarketDataSnapshot();
            snapshot.symbol = contract.symbol;
            snapshot.lastPrice = round2(boundedPrice);
            snapshot.bid = round2(bid);
            snapshot.ask = round2(ask);
            snapshot.open = round2(open);
            snapshot.high = last != null ? Math.max(last.high, boundedPrice) : round2(boundedPrice);
            snapshot.low = last != null ? Math.min(last.low, boundedPrice) : round2(boundedPrice);
            snapshot.change = round2(snapshot.lastPrice - snapshot.open);
            snapshot.changePercent = snapshot.open > 0
                    ? round2((snapshot.lastPrice - snapshot.open) / snapshot.open * 100.0)
                    : 0;
            snapshot.volume = last != null ? last.volume + random.nextInt(100) : random.nextInt(10000);
            snapshot.timestamp = timestamp;

            lastSnapshots.put(contract.symbol, snapshot);
            referenceDataService.updateMarketData(contract.symbol, snapshot.lastPrice, snapshot.bid, snapshot.ask);
            notifyListeners(snapshot);

            try {
                webSocketAggregator.broadcastMarketData(
                    contract.symbol,
                    snapshot.lastPrice,
                    snapshot.bid,
                    snapshot.ask,
                    snapshot.change,
                    snapshot.changePercent
                );
            } catch (Exception e) {
                LOG.debug("Could not broadcast option price update for {}", contract.symbol);
            }
        }
    }

    private double estimateOptionVolatility(double spot, double strike) {
        double moneyness = strike / Math.max(spot, 0.01);
        double skew = Math.abs(moneyness - 1.0);
        return Math.min(0.80, 0.22 + (skew * 0.35));
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
    
    /**
     * Simulate price updates with realistic movement
     */
    private void simulatePriceUpdates() {
        try {
            for (Map.Entry<String, Double> entry : basePrices.entrySet()) {
                String symbol = entry.getKey();
                double basePrice = entry.getValue();
                
                // Get current price or start with base
                MarketDataSnapshot last = lastSnapshots.get(symbol);
                double currentPrice = last != null ? last.lastPrice : basePrice;
                
                // Random walk with mean reversion
                double volatility = 0.001 + random.nextDouble() * 0.002; // 0.1% to 0.3%
                double drift = (basePrice - currentPrice) / basePrice * 0.01; // Mean reversion
                double change = currentPrice * (drift + (random.nextGaussian() * volatility));
                
                double newPrice = Math.max(0.01, currentPrice + change);
                newPrice = Math.round(newPrice * 100.0) / 100.0; // Round to 2 decimals
                
                // Calculate spread (tighter for liquid stocks)
                double spreadPercent = 0.0001 + random.nextDouble() * 0.0005;
                double halfSpread = newPrice * spreadPercent;
                double bid = newPrice - halfSpread;
                double ask = newPrice + halfSpread;
                
                // Calculate change from base (open)
                double priceChange = newPrice - basePrice;
                double changePercent = (priceChange / basePrice) * 100;
                
                // Create snapshot
                MarketDataSnapshot snapshot = new MarketDataSnapshot();
                snapshot.symbol = symbol;
                snapshot.lastPrice = newPrice;
                snapshot.bid = Math.round(bid * 100.0) / 100.0;
                snapshot.ask = Math.round(ask * 100.0) / 100.0;
                snapshot.open = basePrice;
                snapshot.high = last != null ? Math.max(last.high, newPrice) : newPrice;
                snapshot.low = last != null ? Math.min(last.low, newPrice) : newPrice;
                snapshot.change = Math.round(priceChange * 100.0) / 100.0;
                snapshot.changePercent = Math.round(changePercent * 100.0) / 100.0;
                snapshot.volume = last != null ? last.volume + random.nextInt(1000) : random.nextInt(100000);
                snapshot.timestamp = System.currentTimeMillis();
                
                // Check for delta update
                if (isDeltaUpdate(symbol, snapshot)) {
                    lastSnapshots.put(symbol, snapshot);
                    notifyListeners(snapshot);
                    
                    // Record telemetry
                    telemetryService.recordMarketDataUpdate(symbol);
                    
                    // Broadcast via WebSocket
                    try {
                        webSocketAggregator.broadcastMarketData(
                            symbol, newPrice, snapshot.bid, snapshot.ask, 
                            snapshot.change, snapshot.changePercent
                        );
                        telemetryService.recordMarketDataBroadcast();
                    } catch (Exception e) {
                        // WebSocket may not be initialized yet
                    }
                    
                    // Update reference data service
                    referenceDataService.updateMarketData(symbol, newPrice, snapshot.bid, snapshot.ask);

                    // Reprice options linked to this underlying.
                    updateOptionPricesForUnderlying(symbol, newPrice, snapshot.timestamp);
                }
            }
        } catch (Exception e) {
            LOG.error("Error in price simulation: {}", e.getMessage());
        }
    }
    
    /**
     * Poll market data from flat file
     */
    private void pollMarketDataFile() {
        if (marketDataFile == null || !Files.exists(marketDataFile)) {
            return;
        }
        
        try (BufferedReader reader = Files.newBufferedReader(marketDataFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                
                MarketDataSnapshot snapshot = parseMarketDataLine(line);
                if (snapshot != null && isDeltaUpdate(snapshot.symbol, snapshot)) {
                    lastSnapshots.put(snapshot.symbol, snapshot);
                    notifyListeners(snapshot);
                }
            }
        } catch (IOException e) {
            LOG.error("Error reading market data file: {}", e.getMessage());
        }
    }
    
    private MarketDataSnapshot parseMarketDataLine(String line) {
        try {
            // Expected format: SYMBOL,LAST,BID,ASK,VOLUME
            String[] parts = line.split(",");
            if (parts.length < 4) return null;
            
            MarketDataSnapshot snapshot = new MarketDataSnapshot();
            snapshot.symbol = parts[0].trim();
            snapshot.lastPrice = Double.parseDouble(parts[1]);
            snapshot.bid = Double.parseDouble(parts[2]);
            snapshot.ask = Double.parseDouble(parts[3]);
            if (parts.length > 4) {
                snapshot.volume = Long.parseLong(parts[4]);
            }
            snapshot.timestamp = System.currentTimeMillis();
            
            return snapshot;
        } catch (Exception e) {
            LOG.warn("Error parsing market data line: {}", line);
            return null;
        }
    }
    
    private boolean isDeltaUpdate(String symbol, MarketDataSnapshot newSnapshot) {
        MarketDataSnapshot last = lastSnapshots.get(symbol);
        if (last == null) return true;
        
        // Check if price changed meaningfully (more than 0.001%)
        double priceChange = Math.abs(newSnapshot.lastPrice - last.lastPrice);
        return priceChange / last.lastPrice > 0.00001;
    }
    
    private void notifyListeners(MarketDataSnapshot snapshot) {
        for (MarketDataListener listener : listeners) {
            try {
                listener.onMarketData(snapshot);
            } catch (Exception e) {
                LOG.error("Error notifying listener: {}", e.getMessage());
            }
        }
    }
    
    // ================== Public API ==================
    
    public void setMarketDataFile(String path) {
        this.marketDataFile = Paths.get(path);
    }
    
    public void setPollInterval(long intervalMs) {
        this.pollIntervalMs = intervalMs;
    }
    
    public void setSimulationMode(boolean enabled) {
        this.simulationMode = enabled;
    }
    
    public MarketDataSnapshot getLatestSnapshot(String symbol) {
        return lastSnapshots.get(symbol);
    }
    
    public Collection<MarketDataSnapshot> getAllSnapshots() {
        return Collections.unmodifiableCollection(lastSnapshots.values());
    }
    
    public void addListener(MarketDataListener listener) {
        listeners.add(listener);
    }
    
    public void removeListener(MarketDataListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * G3-M4: Update price from trade execution
     * - Updates the last price when a trade executes
     * - Recalculates derived values (bid/ask spread, change)
     * - Broadcasts update to subscribers
     */
    public void updatePriceFromTrade(String symbol, double tradePrice, long tradeQuantity) {
        if (symbol == null || tradePrice <= 0) {
            LOG.warn("Invalid trade update: symbol={}, price={}", symbol, tradePrice);
            return;
        }
        
        Double basePrice = basePrices.get(symbol);
        if (basePrice == null) {
            // Add new symbol with trade price as base
            basePrices.put(symbol, tradePrice);
            basePrice = tradePrice;
        }
        
        MarketDataSnapshot last = lastSnapshots.get(symbol);
        double openPrice = last != null ? last.open : basePrice;
        
        // Calculate spread based on price level
        double spreadPercent = 0.0001 + (random.nextDouble() * 0.0003);
        double halfSpread = tradePrice * spreadPercent;
        
        // Create updated snapshot
        MarketDataSnapshot snapshot = new MarketDataSnapshot();
        snapshot.symbol = symbol;
        snapshot.lastPrice = Math.round(tradePrice * 100.0) / 100.0;
        snapshot.bid = Math.round((tradePrice - halfSpread) * 100.0) / 100.0;
        snapshot.ask = Math.round((tradePrice + halfSpread) * 100.0) / 100.0;
        snapshot.open = openPrice;
        snapshot.high = last != null ? Math.max(last.high, tradePrice) : tradePrice;
        snapshot.low = last != null ? Math.min(last.low, tradePrice) : tradePrice;
        snapshot.change = Math.round((tradePrice - openPrice) * 100.0) / 100.0;
        snapshot.changePercent = openPrice > 0 ? Math.round(((tradePrice - openPrice) / openPrice * 100) * 100.0) / 100.0 : 0;
        snapshot.volume = last != null ? last.volume + tradeQuantity : tradeQuantity;
        snapshot.timestamp = System.currentTimeMillis();
        
        // Store and notify
        lastSnapshots.put(symbol, snapshot);
        notifyListeners(snapshot);
        updateOptionPricesForUnderlying(symbol, tradePrice, snapshot.timestamp);
        
        // Broadcast via WebSocket
        try {
            webSocketAggregator.broadcastMarketData(
                symbol, snapshot.lastPrice, snapshot.bid, snapshot.ask,
                snapshot.change, snapshot.changePercent
            );
        } catch (Exception e) {
            LOG.debug("Could not broadcast trade price update: {}", e.getMessage());
        }
        
        LOG.debug("Updated price from trade: {} @ {} qty={}", symbol, tradePrice, tradeQuantity);
    }
    
    /**
     * Get current price for a symbol
     */
    public double getCurrentPrice(String symbol) {
        MarketDataSnapshot snapshot = lastSnapshots.get(symbol);
        if (snapshot != null) {
            return snapshot.lastPrice;
        }
        Double basePrice = basePrices.get(symbol);
        return basePrice != null ? basePrice : 0;
    }
    
    // ================== Inner Classes ==================
    
    public static class MarketDataSnapshot {
        public String symbol;
        public double lastPrice;
        public double bid;
        public double ask;
        public double open;
        public double high;
        public double low;
        public double change;
        public double changePercent;
        public long volume;
        public long timestamp;
        
        @Override
        public String toString() {
            return String.format("%s: %.2f (%.2f/%.2f) %+.2f (%.2f%%)", 
                    symbol, lastPrice, bid, ask, change, changePercent);
        }
    }

    private static class OptionContract {
        String symbol;
        String underlying;
        String optionType;
        double strike;
        LocalDate expiry;
    }
    
    public interface MarketDataListener {
        void onMarketData(MarketDataSnapshot snapshot);
    }
}
