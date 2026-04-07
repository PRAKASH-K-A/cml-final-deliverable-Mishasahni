package com.helesto.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * G3-M6: Observability & Telemetry Service
 * - Message lag tracking
 * - Update frequency monitoring
 * - Compute time measurements
 * - Health metrics aggregation
 * - REST endpoint exposure for monitoring
 */
@ApplicationScoped
public class TelemetryService {

    private static final Logger LOG = LoggerFactory.getLogger(TelemetryService.class);
    
    // ================== FIX Message Metrics ==================
    private final LongAdder fixMessagesReceived = new LongAdder();
    private final LongAdder fixMessagesSent = new LongAdder();
    private final LongAdder fixMessagesRejected = new LongAdder();
    private final AtomicLong lastFixMessageTimestamp = new AtomicLong(0);
    private final AtomicLong fixMessageLagNanos = new AtomicLong(0);
    
    // ================== Order Processing Metrics ==================
    private final LongAdder ordersReceived = new LongAdder();
    private final LongAdder ordersProcessed = new LongAdder();
    private final LongAdder ordersFilled = new LongAdder();
    private final LongAdder ordersCancelled = new LongAdder();
    private final LongAdder ordersRejected = new LongAdder();
    private final AtomicLong orderProcessingTimeNanos = new AtomicLong(0);
    private final AtomicLong orderProcessingCount = new AtomicLong(0);
    
    // ================== Matching Engine Metrics ==================
    private final LongAdder matchAttempts = new LongAdder();
    private final LongAdder matchSuccesses = new LongAdder();
    private final LongAdder tradesGenerated = new LongAdder();
    private final AtomicLong matchingTimeNanos = new AtomicLong(0);
    private final AtomicLong matchingCount = new AtomicLong(0);
    
    // ================== Market Data Metrics ==================
    private final LongAdder marketDataUpdates = new LongAdder();
    private final LongAdder marketDataBroadcasts = new LongAdder();
    private final AtomicLong lastMarketDataTimestamp = new AtomicLong(0);
    private final Map<String, AtomicLong> symbolUpdateCounts = new ConcurrentHashMap<>();
    
    // ================== WebSocket Metrics ==================
    private final LongAdder wsMessagesIn = new LongAdder();
    private final LongAdder wsMessagesOut = new LongAdder();
    private final AtomicLong activeWsConnections = new AtomicLong(0);
    private final LongAdder wsBroadcasts = new LongAdder();
    
    // ================== Options Pricing Metrics ==================
    private final LongAdder optionPricesComputed = new LongAdder();
    private final AtomicLong pricingComputeTimeNanos = new AtomicLong(0);
    private final AtomicLong pricingComputeCount = new AtomicLong(0);
    
    // ================== System Health ==================
    private final AtomicLong startTimeMs = new AtomicLong(0);
    private final LongAdder errors = new LongAdder();
    private final LongAdder warnings = new LongAdder();
    
    @PostConstruct
    void init() {
        startTimeMs.set(System.currentTimeMillis());
        LOG.info("TelemetryService initialized");
    }
    
    // ================== Recording Methods ==================
    
    public void recordFixMessageReceived() {
        fixMessagesReceived.increment();
        lastFixMessageTimestamp.set(System.currentTimeMillis());
    }
    
    public void recordFixMessageSent() {
        fixMessagesSent.increment();
    }
    
    public void recordFixMessageRejected() {
        fixMessagesRejected.increment();
    }
    
    public void recordFixMessageLag(long lagNanos) {
        fixMessageLagNanos.set(lagNanos);
    }
    
    public void recordOrderReceived() {
        ordersReceived.increment();
    }
    
    public void recordOrderProcessed(long processingTimeNanos) {
        ordersProcessed.increment();
        orderProcessingTimeNanos.addAndGet(processingTimeNanos);
        orderProcessingCount.incrementAndGet();
    }
    
    public void recordOrderProcessed() {
        ordersProcessed.increment();
    }
    
    public void recordOrderFilled() {
        ordersFilled.increment();
    }
    
    public void recordOrderCancelled() {
        ordersCancelled.increment();
    }
    
    public void recordOrderRejected() {
        ordersRejected.increment();
    }
    
    public void recordMatchAttempt(boolean success, long matchTimeNanos) {
        matchAttempts.increment();
        if (success) {
            matchSuccesses.increment();
        }
        matchingTimeNanos.addAndGet(matchTimeNanos);
        matchingCount.incrementAndGet();
    }
    
    public void recordTradeGenerated() {
        tradesGenerated.increment();
    }
    
    public void recordMarketDataUpdate(String symbol) {
        marketDataUpdates.increment();
        lastMarketDataTimestamp.set(System.currentTimeMillis());
        symbolUpdateCounts.computeIfAbsent(symbol, k -> new AtomicLong(0)).incrementAndGet();
    }
    
    public void recordMarketDataBroadcast() {
        marketDataBroadcasts.increment();
    }
    
    public void recordWsMessageIn() {
        wsMessagesIn.increment();
    }
    
    public void recordWsMessageOut() {
        wsMessagesOut.increment();
    }
    
    public void recordWsConnection(boolean connected) {
        if (connected) {
            activeWsConnections.incrementAndGet();
        } else {
            activeWsConnections.decrementAndGet();
        }
    }
    
    public void recordWsBroadcast() {
        wsBroadcasts.increment();
    }
    
    public void recordOptionPriceComputed(long computeTimeNanos) {
        optionPricesComputed.increment();
        pricingComputeTimeNanos.addAndGet(computeTimeNanos);
        pricingComputeCount.incrementAndGet();
    }
    
    public void recordError() {
        errors.increment();
    }
    
    public void recordWarning() {
        warnings.increment();
    }
    
    // ================== Query Methods ==================
    
    public Map<String, Object> getFixMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("messagesReceived", fixMessagesReceived.sum());
        metrics.put("messagesSent", fixMessagesSent.sum());
        metrics.put("messagesRejected", fixMessagesRejected.sum());
        metrics.put("lastMessageTimestamp", lastFixMessageTimestamp.get());
        metrics.put("currentLagMs", fixMessageLagNanos.get() / 1_000_000.0);
        return metrics;
    }
    
    public Map<String, Object> getOrderMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("received", ordersReceived.sum());
        metrics.put("processed", ordersProcessed.sum());
        metrics.put("filled", ordersFilled.sum());
        metrics.put("cancelled", ordersCancelled.sum());
        metrics.put("rejected", ordersRejected.sum());
        
        long count = orderProcessingCount.get();
        double avgProcessingMs = count > 0 
            ? (orderProcessingTimeNanos.get() / count) / 1_000_000.0 
            : 0;
        metrics.put("avgProcessingTimeMs", String.format("%.3f", avgProcessingMs));
        return metrics;
    }
    
    public Map<String, Object> getMatchingMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("attempts", matchAttempts.sum());
        metrics.put("successes", matchSuccesses.sum());
        metrics.put("trades", tradesGenerated.sum());
        
        long attempts = matchAttempts.sum();
        double matchRate = attempts > 0 ? (matchSuccesses.sum() * 100.0) / attempts : 0;
        metrics.put("matchRatePercent", String.format("%.2f", matchRate));
        
        long count = matchingCount.get();
        double avgMatchingMs = count > 0 
            ? (matchingTimeNanos.get() / count) / 1_000_000.0 
            : 0;
        metrics.put("avgMatchTimeMs", String.format("%.3f", avgMatchingMs));
        return metrics;
    }
    
    public Map<String, Object> getMarketDataMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("updates", marketDataUpdates.sum());
        metrics.put("broadcasts", marketDataBroadcasts.sum());
        metrics.put("lastUpdateTimestamp", lastMarketDataTimestamp.get());
        
        long now = System.currentTimeMillis();
        long lastUpdate = lastMarketDataTimestamp.get();
        long staleness = lastUpdate > 0 ? now - lastUpdate : -1;
        metrics.put("stalenessMs", staleness);
        
        // Calculate update frequency (updates per minute)
        long uptimeMs = now - startTimeMs.get();
        double updatesPerMinute = uptimeMs > 0 
            ? (marketDataUpdates.sum() * 60000.0) / uptimeMs 
            : 0;
        metrics.put("updatesPerMinute", String.format("%.1f", updatesPerMinute));
        
        return metrics;
    }
    
    public Map<String, Object> getWebSocketMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("messagesIn", wsMessagesIn.sum());
        metrics.put("messagesOut", wsMessagesOut.sum());
        metrics.put("activeConnections", activeWsConnections.get());
        metrics.put("broadcasts", wsBroadcasts.sum());
        return metrics;
    }
    
    public Map<String, Object> getOptionsPricingMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("pricesComputed", optionPricesComputed.sum());
        
        long count = pricingComputeCount.get();
        double avgComputeMs = count > 0 
            ? (pricingComputeTimeNanos.get() / count) / 1_000_000.0 
            : 0;
        metrics.put("avgComputeTimeMs", String.format("%.3f", avgComputeMs));
        
        long uptimeMs = System.currentTimeMillis() - startTimeMs.get();
        double computesPerSecond = uptimeMs > 0 
            ? (optionPricesComputed.sum() * 1000.0) / uptimeMs 
            : 0;
        metrics.put("computesPerSecond", String.format("%.1f", computesPerSecond));
        return metrics;
    }
    
    public Map<String, Object> getSystemHealthMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        long uptimeMs = System.currentTimeMillis() - startTimeMs.get();
        metrics.put("uptimeMs", uptimeMs);
        metrics.put("uptimeHuman", formatUptime(uptimeMs));
        metrics.put("errors", errors.sum());
        metrics.put("warnings", warnings.sum());
        metrics.put("startTime", startTimeMs.get());
        return metrics;
    }
    
    public Map<String, Object> getAllMetrics() {
        Map<String, Object> all = new HashMap<>();
        all.put("fix", getFixMetrics());
        all.put("orders", getOrderMetrics());
        all.put("matching", getMatchingMetrics());
        all.put("marketData", getMarketDataMetrics());
        all.put("webSocket", getWebSocketMetrics());
        all.put("optionsPricing", getOptionsPricingMetrics());
        all.put("system", getSystemHealthMetrics());
        all.put("timestamp", System.currentTimeMillis());
        return all;
    }
    
    private String formatUptime(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        
        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours % 24, minutes % 60);
        } else if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds % 60);
        } else {
            return String.format("%ds", seconds);
        }
    }
}
