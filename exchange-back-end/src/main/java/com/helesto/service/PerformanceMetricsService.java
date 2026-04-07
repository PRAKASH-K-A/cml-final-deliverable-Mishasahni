package com.helesto.service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Performance Metrics Service with Histogram-based Latency Tracking
 * - HDR (High Dynamic Range) histogram for accurate percentile calculations
 * - Operation latency tracking (order processing, matching, etc.)
 * - Throughput monitoring
 * - SLA compliance tracking
 * - Time series data for trending
 */
@ApplicationScoped
public class PerformanceMetricsService {

    private static final Logger LOG = LoggerFactory.getLogger(PerformanceMetricsService.class);
    
    // Latency histograms for different operations
    private final Map<String, LatencyHistogram> histograms = new ConcurrentHashMap<>();
    
    // Throughput counters
    private final Map<String, ThroughputCounter> throughputCounters = new ConcurrentHashMap<>();
    
    // SLA definitions
    private final Map<String, SLADefinition> slaDefinitions = new ConcurrentHashMap<>();
    
    // Time series data (rolling window)
    private final Map<String, TimeSeries> timeSeriesData = new ConcurrentHashMap<>();
    
    // Overall statistics
    private final AtomicLong totalOperations = new AtomicLong(0);
    private final AtomicLong totalLatencyNanos = new AtomicLong(0);
    private LocalDateTime startTime;
    
    // Standard operation names
    public static final String OP_ORDER_RECEIVE = "order.receive";
    public static final String OP_ORDER_VALIDATE = "order.validate";
    public static final String OP_ORDER_MATCH = "order.match";
    public static final String OP_ORDER_TOTAL = "order.total";
    public static final String OP_FIX_PARSE = "fix.parse";
    public static final String OP_FIX_SEND = "fix.send";
    public static final String OP_DB_WRITE = "db.write";
    public static final String OP_DB_READ = "db.read";
    public static final String OP_WEBSOCKET_BROADCAST = "ws.broadcast";
    public static final String OP_PRICING_COMPUTE = "pricing.compute";
    
    @PostConstruct
    public void init() {
        LOG.info("Initializing Performance Metrics Service...");
        startTime = LocalDateTime.now();
        
        // Initialize standard histograms
        String[] operations = {
            OP_ORDER_RECEIVE, OP_ORDER_VALIDATE, OP_ORDER_MATCH, OP_ORDER_TOTAL,
            OP_FIX_PARSE, OP_FIX_SEND, OP_DB_WRITE, OP_DB_READ,
            OP_WEBSOCKET_BROADCAST, OP_PRICING_COMPUTE
        };
        
        for (String op : operations) {
            histograms.put(op, new LatencyHistogram(op));
            throughputCounters.put(op, new ThroughputCounter());
            timeSeriesData.put(op, new TimeSeries(60)); // 60 second window
        }
        
        // Define default SLAs
        defineSLA(OP_ORDER_TOTAL, 10_000_000, 50_000_000, 100_000_000); // 10ms p50, 50ms p99, 100ms p999
        defineSLA(OP_ORDER_MATCH, 1_000_000, 5_000_000, 10_000_000);    // 1ms p50, 5ms p99, 10ms p999
        defineSLA(OP_FIX_PARSE, 100_000, 500_000, 1_000_000);           // 0.1ms p50, 0.5ms p99, 1ms p999
        
        LOG.info("Performance Metrics Service initialized");
    }
    
    // ==================== Recording Methods ====================
    
    /**
     * Record an operation latency
     */
    public void recordLatency(String operation, long latencyNanos) {
        LatencyHistogram histogram = histograms.computeIfAbsent(
            operation, k -> new LatencyHistogram(k));
        histogram.record(latencyNanos);
        
        ThroughputCounter counter = throughputCounters.computeIfAbsent(
            operation, k -> new ThroughputCounter());
        counter.increment();
        
        TimeSeries ts = timeSeriesData.computeIfAbsent(
            operation, k -> new TimeSeries(60));
        ts.record(latencyNanos);
        
        totalOperations.incrementAndGet();
        totalLatencyNanos.addAndGet(latencyNanos);
    }
    
    /**
     * Start timing an operation (returns a context to stop)
     */
    public TimingContext startTiming(String operation) {
        return new TimingContext(operation, System.nanoTime());
    }
    
    /**
     * Record an error for an operation
     */
    public void recordError(String operation) {
        LatencyHistogram histogram = histograms.get(operation);
        if (histogram != null) {
            histogram.recordError();
        }
    }
    
    // ==================== Query Methods ====================
    
    /**
     * Get latency statistics for an operation
     */
    public LatencyStats getLatencyStats(String operation) {
        LatencyHistogram histogram = histograms.get(operation);
        if (histogram == null) {
            return new LatencyStats(operation);
        }
        return histogram.getStats();
    }
    
    /**
     * Get all latency statistics
     */
    public Map<String, LatencyStats> getAllLatencyStats() {
        Map<String, LatencyStats> stats = new HashMap<>();
        for (Map.Entry<String, LatencyHistogram> entry : histograms.entrySet()) {
            stats.put(entry.getKey(), entry.getValue().getStats());
        }
        return stats;
    }
    
    /**
     * Get throughput for an operation (ops/second)
     */
    public double getThroughput(String operation) {
        ThroughputCounter counter = throughputCounters.get(operation);
        if (counter == null) return 0;
        return counter.getThroughput();
    }
    
    /**
     * Get SLA compliance status
     */
    public SLAStatus getSLAStatus(String operation) {
        SLADefinition sla = slaDefinitions.get(operation);
        if (sla == null) {
            return new SLAStatus(operation, true, "No SLA defined");
        }
        
        LatencyStats stats = getLatencyStats(operation);
        SLAStatus status = new SLAStatus(operation, true, "Within SLA");
        
        if (stats.p50 > sla.p50Target) {
            status.compliant = false;
            status.message = String.format("P50 %.2fms exceeds target %.2fms",
                    stats.p50 / 1_000_000.0, sla.p50Target / 1_000_000.0);
        } else if (stats.p99 > sla.p99Target) {
            status.compliant = false;
            status.message = String.format("P99 %.2fms exceeds target %.2fms",
                    stats.p99 / 1_000_000.0, sla.p99Target / 1_000_000.0);
        } else if (stats.p999 > sla.p999Target) {
            status.compliant = false;
            status.message = String.format("P99.9 %.2fms exceeds target %.2fms",
                    stats.p999 / 1_000_000.0, sla.p999Target / 1_000_000.0);
        }
        
        return status;
    }
    
    /**
     * Get all SLA status
     */
    public Map<String, SLAStatus> getAllSLAStatus() {
        Map<String, SLAStatus> statuses = new HashMap<>();
        for (String operation : slaDefinitions.keySet()) {
            statuses.put(operation, getSLAStatus(operation));
        }
        return statuses;
    }
    
    /**
     * Get recent time series data
     */
    public List<TimeSeriesPoint> getTimeSeries(String operation, int seconds) {
        TimeSeries ts = timeSeriesData.get(operation);
        if (ts == null) return Collections.emptyList();
        return ts.getRecentData(seconds);
    }
    
    /**
     * Get overall performance summary
     */
    public PerformanceSummary getSummary() {
        PerformanceSummary summary = new PerformanceSummary();
        summary.startTime = this.startTime;
        summary.totalOperations = totalOperations.get();
        summary.totalLatencyNanos = totalLatencyNanos.get();
        
        if (summary.totalOperations > 0) {
            summary.avgLatencyNanos = summary.totalLatencyNanos / summary.totalOperations;
        }
        
        summary.operationStats = getAllLatencyStats();
        summary.slaStatus = getAllSLAStatus();
        
        // Calculate overall throughput
        long elapsedSeconds = java.time.Duration.between(startTime, LocalDateTime.now()).getSeconds();
        if (elapsedSeconds > 0) {
            summary.overallThroughput = (double) summary.totalOperations / elapsedSeconds;
        }
        
        return summary;
    }
    
    // ==================== SLA Management ====================
    
    /**
     * Define SLA for an operation
     */
    public void defineSLA(String operation, long p50TargetNanos, long p99TargetNanos, long p999TargetNanos) {
        SLADefinition sla = new SLADefinition();
        sla.operation = operation;
        sla.p50Target = p50TargetNanos;
        sla.p99Target = p99TargetNanos;
        sla.p999Target = p999TargetNanos;
        slaDefinitions.put(operation, sla);
    }
    
    // ==================== Reset ====================
    
    /**
     * Reset all metrics
     */
    public void reset() {
        for (LatencyHistogram h : histograms.values()) {
            h.reset();
        }
        for (ThroughputCounter c : throughputCounters.values()) {
            c.reset();
        }
        for (TimeSeries ts : timeSeriesData.values()) {
            ts.reset();
        }
        totalOperations.set(0);
        totalLatencyNanos.set(0);
        startTime = LocalDateTime.now();
        LOG.info("Performance metrics reset");
    }
    
    /**
     * Reset metrics for a specific operation
     */
    public void reset(String operation) {
        LatencyHistogram h = histograms.get(operation);
        if (h != null) h.reset();
        
        ThroughputCounter c = throughputCounters.get(operation);
        if (c != null) c.reset();
        
        TimeSeries ts = timeSeriesData.get(operation);
        if (ts != null) ts.reset();
    }
    
    // ==================== Helper Classes ====================
    
    /**
     * Timing context for measuring operation duration
     */
    public class TimingContext implements AutoCloseable {
        private final String operation;
        private final long startNanos;
        
        TimingContext(String operation, long startNanos) {
            this.operation = operation;
            this.startNanos = startNanos;
        }
        
        public void stop() {
            long elapsed = System.nanoTime() - startNanos;
            recordLatency(operation, elapsed);
        }
        
        @Override
        public void close() {
            stop();
        }
    }
    
    /**
     * HDR-style Histogram for latency tracking
     */
    private static class LatencyHistogram {
        private final String name;
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong errors = new AtomicLong(0);
        private final AtomicLong totalNanos = new AtomicLong(0);
        private final AtomicLong minNanos = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxNanos = new AtomicLong(0);
        
        // Buckets for histogram: 0-1us, 1-10us, 10-100us, 100us-1ms, 1-10ms, 10-100ms, 100ms-1s, 1s+
        private final LongAdder[] buckets = new LongAdder[8];
        private static final long[] BUCKET_BOUNDS = {
            1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000, 1_000_000_000, Long.MAX_VALUE
        };
        
        // Percentile reservoir (last N values for accurate percentiles)
        private final long[] reservoir = new long[10_000];
        private final AtomicLong reservoirIndex = new AtomicLong(0);
        
        LatencyHistogram(String name) {
            this.name = name;
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = new LongAdder();
            }
        }
        
        void record(long nanos) {
            count.incrementAndGet();
            totalNanos.addAndGet(nanos);
            
            // Update min/max
            long currentMin;
            while (nanos < (currentMin = minNanos.get())) {
                if (minNanos.compareAndSet(currentMin, nanos)) break;
            }
            long currentMax;
            while (nanos > (currentMax = maxNanos.get())) {
                if (maxNanos.compareAndSet(currentMax, nanos)) break;
            }
            
            // Add to bucket
            for (int i = 0; i < BUCKET_BOUNDS.length; i++) {
                if (nanos <= BUCKET_BOUNDS[i]) {
                    buckets[i].increment();
                    break;
                }
            }
            
            // Add to reservoir for percentile calculation
            int idx = (int) (reservoirIndex.getAndIncrement() % reservoir.length);
            reservoir[idx] = nanos;
        }
        
        void recordError() {
            errors.incrementAndGet();
        }
        
        LatencyStats getStats() {
            LatencyStats stats = new LatencyStats(name);
            stats.count = count.get();
            stats.errors = errors.get();
            
            if (stats.count > 0) {
                stats.avgNanos = totalNanos.get() / stats.count;
                stats.minNanos = minNanos.get();
                stats.maxNanos = maxNanos.get();
                
                // Calculate percentiles from reservoir
                int sampleSize = (int) Math.min(stats.count, reservoir.length);
                long[] samples = new long[sampleSize];
                System.arraycopy(reservoir, 0, samples, 0, sampleSize);
                Arrays.sort(samples);
                
                stats.p50 = samples[(int) (sampleSize * 0.50)];
                stats.p90 = samples[(int) (sampleSize * 0.90)];
                stats.p95 = samples[(int) (sampleSize * 0.95)];
                stats.p99 = samples[(int) (sampleSize * 0.99)];
                stats.p999 = samples[(int) Math.min(sampleSize - 1, sampleSize * 0.999)];
                
                // Build histogram
                String[] bucketNames = {
                    "0-1μs", "1-10μs", "10-100μs", "100μs-1ms",
                    "1-10ms", "10-100ms", "100ms-1s", "1s+"
                };
                for (int i = 0; i < buckets.length; i++) {
                    stats.histogram.put(bucketNames[i], buckets[i].sum());
                }
            }
            
            return stats;
        }
        
        void reset() {
            count.set(0);
            errors.set(0);
            totalNanos.set(0);
            minNanos.set(Long.MAX_VALUE);
            maxNanos.set(0);
            for (LongAdder bucket : buckets) {
                bucket.reset();
            }
            Arrays.fill(reservoir, 0);
            reservoirIndex.set(0);
        }
    }
    
    /**
     * Throughput counter with sliding window
     */
    private static class ThroughputCounter {
        private final long[] windowSeconds = new long[60]; // 60-second sliding window
        private final AtomicLong totalCount = new AtomicLong(0);
        private volatile long lastSecond = 0;
        
        void increment() {
            totalCount.incrementAndGet();
            long currentSecond = System.currentTimeMillis() / 1000;
            int idx = (int) (currentSecond % windowSeconds.length);
            
            if (currentSecond != lastSecond) {
                lastSecond = currentSecond;
                windowSeconds[idx] = 1;
            } else {
                windowSeconds[idx]++;
            }
        }
        
        double getThroughput() {
            long currentSecond = System.currentTimeMillis() / 1000;
            long count = 0;
            int windowSize = 0;
            
            for (int i = 0; i < windowSeconds.length; i++) {
                if (windowSeconds[i] > 0) {
                    count += windowSeconds[i];
                    windowSize++;
                }
            }
            
            return windowSize > 0 ? (double) count / windowSize : 0;
        }
        
        void reset() {
            totalCount.set(0);
            Arrays.fill(windowSeconds, 0);
        }
    }
    
    /**
     * Time series data for trending
     */
    private static class TimeSeries {
        private final TimeSeriesPoint[] points;
        private final AtomicLong writeIndex = new AtomicLong(0);
        
        TimeSeries(int windowSeconds) {
            points = new TimeSeriesPoint[windowSeconds];
            for (int i = 0; i < points.length; i++) {
                points[i] = new TimeSeriesPoint();
            }
        }
        
        void record(long latencyNanos) {
            long currentSecond = System.currentTimeMillis() / 1000;
            int idx = (int) (currentSecond % points.length);
            TimeSeriesPoint point = points[idx];
            
            synchronized (point) {
                if (point.timestamp != currentSecond) {
                    point.reset(currentSecond);
                }
                point.count++;
                point.sumNanos += latencyNanos;
                point.maxNanos = Math.max(point.maxNanos, latencyNanos);
                point.minNanos = Math.min(point.minNanos, latencyNanos);
            }
        }
        
        List<TimeSeriesPoint> getRecentData(int seconds) {
            List<TimeSeriesPoint> result = new ArrayList<>();
            long currentSecond = System.currentTimeMillis() / 1000;
            
            for (int i = 0; i < Math.min(seconds, points.length); i++) {
                int idx = (int) ((currentSecond - i) % points.length);
                if (idx < 0) idx += points.length;
                
                TimeSeriesPoint point = points[idx];
                if (point.timestamp >= currentSecond - seconds) {
                    result.add(point.copy());
                }
            }
            
            Collections.reverse(result);
            return result;
        }
        
        void reset() {
            for (TimeSeriesPoint point : points) {
                point.reset(0);
            }
        }
    }
    
    // ==================== Data Classes ====================
    
    public static class LatencyStats {
        public String operation;
        public long count;
        public long errors;
        public long avgNanos;
        public long minNanos;
        public long maxNanos;
        public long p50;
        public long p90;
        public long p95;
        public long p99;
        public long p999;
        public Map<String, Long> histogram = new LinkedHashMap<>();
        
        LatencyStats(String operation) {
            this.operation = operation;
        }
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("operation", operation);
            map.put("count", count);
            map.put("errors", errors);
            map.put("avgMs", avgNanos / 1_000_000.0);
            map.put("minMs", minNanos / 1_000_000.0);
            map.put("maxMs", maxNanos / 1_000_000.0);
            map.put("p50Ms", p50 / 1_000_000.0);
            map.put("p90Ms", p90 / 1_000_000.0);
            map.put("p95Ms", p95 / 1_000_000.0);
            map.put("p99Ms", p99 / 1_000_000.0);
            map.put("p999Ms", p999 / 1_000_000.0);
            map.put("histogram", histogram);
            return map;
        }
    }
    
    public static class TimeSeriesPoint {
        public long timestamp;
        public long count;
        public long sumNanos;
        public long minNanos = Long.MAX_VALUE;
        public long maxNanos;
        
        void reset(long timestamp) {
            this.timestamp = timestamp;
            this.count = 0;
            this.sumNanos = 0;
            this.minNanos = Long.MAX_VALUE;
            this.maxNanos = 0;
        }
        
        TimeSeriesPoint copy() {
            TimeSeriesPoint p = new TimeSeriesPoint();
            p.timestamp = this.timestamp;
            p.count = this.count;
            p.sumNanos = this.sumNanos;
            p.minNanos = this.minNanos;
            p.maxNanos = this.maxNanos;
            return p;
        }
        
        public double getAvgMs() {
            return count > 0 ? (sumNanos / count) / 1_000_000.0 : 0;
        }
    }
    
    public static class SLADefinition {
        public String operation;
        public long p50Target;
        public long p99Target;
        public long p999Target;
    }
    
    public static class SLAStatus {
        public String operation;
        public boolean compliant;
        public String message;
        
        SLAStatus(String operation, boolean compliant, String message) {
            this.operation = operation;
            this.compliant = compliant;
            this.message = message;
        }
    }
    
    public static class PerformanceSummary {
        public LocalDateTime startTime;
        public long totalOperations;
        public long totalLatencyNanos;
        public long avgLatencyNanos;
        public double overallThroughput;
        public Map<String, LatencyStats> operationStats;
        public Map<String, SLAStatus> slaStatus;
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("startTime", startTime.toString());
            map.put("totalOperations", totalOperations);
            map.put("avgLatencyMs", avgLatencyNanos / 1_000_000.0);
            map.put("throughputOpsPerSec", overallThroughput);
            
            Map<String, Object> ops = new LinkedHashMap<>();
            for (Map.Entry<String, LatencyStats> entry : operationStats.entrySet()) {
                ops.put(entry.getKey(), entry.getValue().toMap());
            }
            map.put("operations", ops);
            
            Map<String, Object> slas = new LinkedHashMap<>();
            for (Map.Entry<String, SLAStatus> entry : slaStatus.entrySet()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("compliant", entry.getValue().compliant);
                s.put("message", entry.getValue().message);
                slas.put(entry.getKey(), s);
            }
            map.put("slaStatus", slas);
            
            return map;
        }
    }
}
