package com.helesto.service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Order Rate Limiting Service
 * - Token bucket algorithm for rate limiting
 * - Per-client and per-symbol rate limits
 * - Configurable burst allowances
 * - Fair queuing for order processing
 * - Throttling during high load
 */
@ApplicationScoped
public class OrderRateLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(OrderRateLimiter.class);
    
    @Inject
    TelemetryService telemetryService;
    
    // Rate limit buckets per client
    private final Map<String, TokenBucket> clientBuckets = new ConcurrentHashMap<>();
    
    // Rate limit buckets per symbol (to prevent concentration attacks)
    private final Map<String, TokenBucket> symbolBuckets = new ConcurrentHashMap<>();
    
    // Global rate limiter
    private TokenBucket globalBucket;
    
    // Per-client rate limit configurations
    private final Map<String, RateLimitConfig> clientConfigs = new ConcurrentHashMap<>();
    
    // Default configuration
    private RateLimitConfig defaultConfig = new RateLimitConfig();
    
    // Sliding window for order count tracking
    private final Map<String, SlidingWindowCounter> clientOrderCounts = new ConcurrentHashMap<>();
    
    // Rate limit violation tracking
    private final Map<String, AtomicLong> violationCounts = new ConcurrentHashMap<>();
    private final List<RateLimitEvent> eventHistory = Collections.synchronizedList(new ArrayList<>());
    
    // Cleanup scheduler
    private ScheduledExecutorService scheduler;
    
    @PostConstruct
    public void init() {
        LOG.info("Initializing Order Rate Limiter...");
        
        // Initialize global bucket
        globalBucket = new TokenBucket(
            defaultConfig.globalOrdersPerSecond,
            defaultConfig.globalBurstSize
        );
        
        // Start cleanup scheduler
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limiter-cleanup");
            t.setDaemon(true);
            return t;
        });
        
        // Clean up old buckets every minute
        scheduler.scheduleAtFixedRate(this::cleanup, 60, 60, TimeUnit.SECONDS);
        
        LOG.info("Order Rate Limiter initialized");
    }
    
    @PreDestroy
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
    
    // ==================== Rate Limit Checking ====================
    
    /**
     * Check if an order can be accepted based on rate limits
     * @return RateLimitResult indicating if order is allowed
     */
    public RateLimitResult checkRateLimit(String clientId, String symbol) {
        RateLimitResult result = new RateLimitResult();
        result.clientId = clientId;
        result.symbol = symbol;
        result.timestamp = LocalDateTime.now();
        
        // 1. Check global rate limit
        if (!globalBucket.tryConsume()) {
            result.allowed = false;
            result.reason = "GLOBAL_RATE_LIMIT";
            result.message = "Global order rate limit exceeded";
            result.retryAfterMs = calculateRetryTime(globalBucket);
            recordViolation(clientId, "GLOBAL");
            return result;
        }
        
        // 2. Check client rate limit
        RateLimitConfig config = clientConfigs.getOrDefault(clientId, defaultConfig);
        TokenBucket clientBucket = clientBuckets.computeIfAbsent(clientId, 
            k -> new TokenBucket(config.ordersPerSecond, config.burstSize));
        
        if (!clientBucket.tryConsume()) {
            result.allowed = false;
            result.reason = "CLIENT_RATE_LIMIT";
            result.message = String.format("Client %s rate limit exceeded (%d orders/sec)",
                    clientId, config.ordersPerSecond);
            result.retryAfterMs = calculateRetryTime(clientBucket);
            recordViolation(clientId, "CLIENT");
            return result;
        }
        
        // 3. Check symbol rate limit (to prevent abuse on single symbol)
        TokenBucket symbolBucket = symbolBuckets.computeIfAbsent(symbol,
            k -> new TokenBucket(config.ordersPerSymbolPerSecond, config.symbolBurstSize));
        
        if (!symbolBucket.tryConsume()) {
            result.allowed = false;
            result.reason = "SYMBOL_RATE_LIMIT";
            result.message = String.format("Symbol %s rate limit exceeded for client %s",
                    symbol, clientId);
            result.retryAfterMs = calculateRetryTime(symbolBucket);
            recordViolation(clientId, "SYMBOL_" + symbol);
            return result;
        }
        
        // 4. Check sliding window order count
        SlidingWindowCounter counter = clientOrderCounts.computeIfAbsent(clientId,
            k -> new SlidingWindowCounter(60_000)); // 1 minute window
        
        long recentOrders = counter.getCount();
        if (recentOrders >= config.maxOrdersPerMinute) {
            result.allowed = false;
            result.reason = "MINUTE_RATE_LIMIT";
            result.message = String.format("Client %s exceeded %d orders/minute limit",
                    clientId, config.maxOrdersPerMinute);
            result.retryAfterMs = counter.getTimeUntilNextSlot();
            recordViolation(clientId, "MINUTE");
            return result;
        }
        
        // 5. Check for repeated violations (soft ban)
        AtomicLong violations = violationCounts.get(clientId);
        if (violations != null && violations.get() >= config.maxViolationsBeforeBlock) {
            result.allowed = false;
            result.reason = "BLOCKED_FOR_VIOLATIONS";
            result.message = String.format("Client %s temporarily blocked due to repeated rate limit violations",
                    clientId);
            result.retryAfterMs = 60_000; // 1 minute cooldown
            return result;
        }
        
        // All checks passed
        counter.increment();
        result.allowed = true;
        result.message = "Order accepted within rate limits";
        result.remainingTokens = (int) clientBucket.getAvailableTokens();
        
        return result;
    }
    
    /**
     * Simplified rate check - just returns if allowed
     */
    public boolean isAllowed(String clientId, String symbol) {
        return checkRateLimit(clientId, symbol).allowed;
    }
    
    private long calculateRetryTime(TokenBucket bucket) {
        // Time until 1 token is available
        return (long) (1000.0 / bucket.refillRate);
    }
    
    // ==================== Configuration ====================
    
    /**
     * Set rate limit configuration for a specific client
     */
    public void setClientConfig(String clientId, RateLimitConfig config) {
        clientConfigs.put(clientId, config);
        
        // Update existing bucket
        TokenBucket bucket = clientBuckets.get(clientId);
        if (bucket != null) {
            bucket.updateRate(config.ordersPerSecond, config.burstSize);
        }
        
        LOG.info("Updated rate limit config for client {}: {} orders/sec, {} burst",
                clientId, config.ordersPerSecond, config.burstSize);
    }
    
    /**
     * Set default rate limit configuration
     */
    public void setDefaultConfig(RateLimitConfig config) {
        this.defaultConfig = config;
        globalBucket.updateRate(config.globalOrdersPerSecond, config.globalBurstSize);
        LOG.info("Updated default rate limit config");
    }
    
    /**
     * Get rate limit configuration for a client
     */
    public RateLimitConfig getClientConfig(String clientId) {
        return clientConfigs.getOrDefault(clientId, defaultConfig);
    }
    
    // ==================== Violation Management ====================
    
    private void recordViolation(String clientId, String type) {
        violationCounts.computeIfAbsent(clientId, k -> new AtomicLong(0)).incrementAndGet();
        
        RateLimitEvent event = new RateLimitEvent();
        event.clientId = clientId;
        event.type = type;
        event.timestamp = LocalDateTime.now();
        eventHistory.add(event);
        
        telemetryService.recordWarning();
        LOG.warn("Rate limit violation for client {}: {}", clientId, type);
    }
    
    /**
     * Clear violation count for a client
     */
    public void clearViolations(String clientId) {
        violationCounts.remove(clientId);
        LOG.info("Cleared violations for client {}", clientId);
    }
    
    /**
     * Get current violation count for a client
     */
    public long getViolationCount(String clientId) {
        AtomicLong count = violationCounts.get(clientId);
        return count != null ? count.get() : 0;
    }
    
    // ==================== Metrics ====================
    
    /**
     * Get rate limiter metrics
     */
    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        // Global metrics
        Map<String, Object> global = new HashMap<>();
        global.put("availableTokens", globalBucket.getAvailableTokens());
        global.put("refillRate", globalBucket.refillRate);
        global.put("capacity", globalBucket.capacity);
        metrics.put("global", global);
        
        // Client metrics
        Map<String, Object> clients = new HashMap<>();
        for (Map.Entry<String, TokenBucket> entry : clientBuckets.entrySet()) {
            Map<String, Object> client = new HashMap<>();
            TokenBucket bucket = entry.getValue();
            client.put("availableTokens", bucket.getAvailableTokens());
            client.put("violations", getViolationCount(entry.getKey()));
            
            SlidingWindowCounter counter = clientOrderCounts.get(entry.getKey());
            if (counter != null) {
                client.put("ordersInWindow", counter.getCount());
            }
            
            clients.put(entry.getKey(), client);
        }
        metrics.put("clients", clients);
        
        // Recent events
        List<Map<String, Object>> events = new ArrayList<>();
        int startIdx = Math.max(0, eventHistory.size() - 100);
        for (int i = startIdx; i < eventHistory.size(); i++) {
            RateLimitEvent event = eventHistory.get(i);
            Map<String, Object> e = new HashMap<>();
            e.put("clientId", event.clientId);
            e.put("type", event.type);
            e.put("timestamp", event.timestamp.toString());
            events.add(e);
        }
        metrics.put("recentEvents", events);
        
        return metrics;
    }
    
    /**
     * Get status for a specific client
     */
    public ClientRateLimitStatus getClientStatus(String clientId) {
        ClientRateLimitStatus status = new ClientRateLimitStatus();
        status.clientId = clientId;
        
        RateLimitConfig config = clientConfigs.getOrDefault(clientId, defaultConfig);
        status.ordersPerSecond = config.ordersPerSecond;
        status.burstSize = config.burstSize;
        status.maxOrdersPerMinute = config.maxOrdersPerMinute;
        
        TokenBucket bucket = clientBuckets.get(clientId);
        status.availableTokens = bucket != null ? (int) bucket.getAvailableTokens() : config.burstSize;
        
        SlidingWindowCounter counter = clientOrderCounts.get(clientId);
        status.ordersInCurrentWindow = counter != null ? counter.getCount() : 0;
        
        status.violationCount = getViolationCount(clientId);
        status.isBlocked = status.violationCount >= config.maxViolationsBeforeBlock;
        
        return status;
    }
    
    // ==================== Cleanup ====================
    
    private void cleanup() {
        long now = System.currentTimeMillis();
        long staleThreshold = 300_000; // 5 minutes
        
        // Remove stale client buckets
        clientBuckets.entrySet().removeIf(entry -> 
            now - entry.getValue().lastAccessTime > staleThreshold);
        
        // Remove stale symbol buckets
        symbolBuckets.entrySet().removeIf(entry -> 
            now - entry.getValue().lastAccessTime > staleThreshold);
        
        // Decay violation counts
        for (AtomicLong count : violationCounts.values()) {
            long current = count.get();
            if (current > 0) {
                count.set((long) (current * 0.9)); // 10% decay
            }
        }
        
        // Remove zeros
        violationCounts.entrySet().removeIf(entry -> entry.getValue().get() <= 0);
        
        // Trim event history
        if (eventHistory.size() > 10000) {
            eventHistory.subList(0, eventHistory.size() - 5000).clear();
        }
    }
    
    // ==================== Data Classes ====================
    
    public static class RateLimitConfig {
        public int ordersPerSecond = 100;           // Per-client orders/second
        public int burstSize = 200;                  // Max burst
        public int ordersPerSymbolPerSecond = 50;   // Per-symbol orders/second
        public int symbolBurstSize = 100;           // Symbol burst
        public long maxOrdersPerMinute = 3000;      // Per-client orders/minute
        public int globalOrdersPerSecond = 10000;   // Global orders/second
        public int globalBurstSize = 20000;         // Global burst
        public int maxViolationsBeforeBlock = 10;   // Violations before soft block
    }
    
    public static class RateLimitResult {
        public String clientId;
        public String symbol;
        public boolean allowed;
        public String reason;
        public String message;
        public long retryAfterMs;
        public int remainingTokens;
        public LocalDateTime timestamp;
        
        @Override
        public String toString() {
            return String.format("RateLimitResult{allowed=%s, reason=%s, retryAfterMs=%d}",
                    allowed, reason, retryAfterMs);
        }
    }
    
    public static class ClientRateLimitStatus {
        public String clientId;
        public int ordersPerSecond;
        public int burstSize;
        public long maxOrdersPerMinute;
        public int availableTokens;
        public long ordersInCurrentWindow;
        public long violationCount;
        public boolean isBlocked;
    }
    
    private static class RateLimitEvent {
        public String clientId;
        public String type;
        public LocalDateTime timestamp;
    }
    
    /**
     * Token Bucket implementation for rate limiting
     */
    private static class TokenBucket {
        private double tokens;
        private double capacity;
        private double refillRate; // tokens per second
        private long lastRefillTime;
        private long lastAccessTime;
        
        public TokenBucket(int tokensPerSecond, int capacity) {
            this.capacity = capacity;
            this.tokens = capacity;
            this.refillRate = tokensPerSecond;
            this.lastRefillTime = System.currentTimeMillis();
            this.lastAccessTime = System.currentTimeMillis();
        }
        
        public synchronized boolean tryConsume() {
            refill();
            lastAccessTime = System.currentTimeMillis();
            
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
        
        private void refill() {
            long now = System.currentTimeMillis();
            double elapsed = (now - lastRefillTime) / 1000.0;
            tokens = Math.min(capacity, tokens + elapsed * refillRate);
            lastRefillTime = now;
        }
        
        public synchronized double getAvailableTokens() {
            refill();
            return tokens;
        }
        
        public synchronized void updateRate(int tokensPerSecond, int newCapacity) {
            this.refillRate = tokensPerSecond;
            this.capacity = newCapacity;
            if (tokens > capacity) {
                tokens = capacity;
            }
        }
    }
    
    /**
     * Sliding window counter for order tracking
     */
    private static class SlidingWindowCounter {
        private final long windowSizeMs;
        private final Deque<Long> timestamps = new ConcurrentLinkedDeque<>();
        
        public SlidingWindowCounter(long windowSizeMs) {
            this.windowSizeMs = windowSizeMs;
        }
        
        public void increment() {
            cleanup();
            timestamps.addLast(System.currentTimeMillis());
        }
        
        public long getCount() {
            cleanup();
            return timestamps.size();
        }
        
        public long getTimeUntilNextSlot() {
            cleanup();
            if (timestamps.isEmpty()) {
                return 0;
            }
            Long oldest = timestamps.peekFirst();
            if (oldest == null) {
                return 0;
            }
            return Math.max(0, windowSizeMs - (System.currentTimeMillis() - oldest));
        }
        
        private void cleanup() {
            long cutoff = System.currentTimeMillis() - windowSizeMs;
            while (!timestamps.isEmpty()) {
                Long first = timestamps.peekFirst();
                if (first != null && first < cutoff) {
                    timestamps.pollFirst();
                } else {
                    break;
                }
            }
        }
    }
}
