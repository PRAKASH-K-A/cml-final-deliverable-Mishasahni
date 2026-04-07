package com.helesto.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.dao.OrderDao;
import com.helesto.model.OrderEntity;

/**
 * G1-M6: Order Cache Service
 * - In-memory order cache (by symbol, by client, by status)
 * - Cache invalidation/update on DB write & execution updates
 * - High-performance lookups for matching engine
 */
@ApplicationScoped
public class OrderCacheService {

    private static final Logger LOG = LoggerFactory.getLogger(OrderCacheService.class);
    
    @Inject
    OrderDao orderDao;
    
    // Primary cache: Order ID -> Order
    private final ConcurrentMap<Long, OrderEntity> orderById = new ConcurrentHashMap<>();
    
    // Order Ref Number -> Order (for FIX lookups)
    private final ConcurrentMap<String, OrderEntity> orderByRefNumber = new ConcurrentHashMap<>();
    
    // ClOrdId -> Order (for client lookups)
    private final ConcurrentMap<String, OrderEntity> orderByClOrdId = new ConcurrentHashMap<>();
    
    // Symbol -> Orders (for matching engine)
    private final ConcurrentMap<String, Set<OrderEntity>> ordersBySymbol = new ConcurrentHashMap<>();
    
    // Client -> Orders
    private final ConcurrentMap<String, Set<OrderEntity>> ordersByClient = new ConcurrentHashMap<>();
    
    // Status -> Orders
    private final ConcurrentMap<String, Set<OrderEntity>> ordersByStatus = new ConcurrentHashMap<>();
    
    // Cache statistics
    private final CacheStats stats = new CacheStats();
    
    // Active orders only (excludes terminal states)
    private static final Set<String> ACTIVE_STATUSES = Set.of(
        "NEW", "PENDING_NEW", "PARTIALLY_FILLED", "PARTIAL_FILL", "PENDING_CANCEL", "PENDING_REPLACE"
    );
    
    @PostConstruct
    void init() {
        LOG.info("Initializing Order Cache Service...");
        loadFromDatabase();
    }
    
    /**
     * Load active orders from database into cache
     */
    public void loadFromDatabase() {
        try {
            List<OrderEntity> orders = orderDao.findAll();
            int loaded = 0;
            
            for (OrderEntity order : orders) {
                addToCache(order);
                loaded++;
            }
            
            LOG.info("Order cache initialized with {} orders", loaded);
            stats.cacheLoads.incrementAndGet();
        } catch (Exception e) {
            LOG.error("Error loading orders into cache: {}", e.getMessage());
        }
    }
    
    /**
     * Add or update order in cache
     */
    public void addToCache(OrderEntity order) {
        if (order == null || order.getId() == null) return;
        
        // Remove from old indexes if updating
        OrderEntity existing = orderById.get(order.getId());
        if (existing != null) {
            removeFromIndexes(existing);
        }
        
        // Add to primary cache
        orderById.put(order.getId(), order);
        
        if (order.getOrderRefNumber() != null) {
            orderByRefNumber.put(order.getOrderRefNumber(), order);
        }
        
        if (order.getClOrdId() != null) {
            orderByClOrdId.put(order.getClOrdId(), order);
        }
        
        // Add to symbol index
        ordersBySymbol.computeIfAbsent(order.getSymbol(), k -> ConcurrentHashMap.newKeySet())
            .add(order);
        
        // Add to client index
        if (order.getClientId() != null) {
            ordersByClient.computeIfAbsent(order.getClientId(), k -> ConcurrentHashMap.newKeySet())
                .add(order);
        }
        
        // Add to status index
        ordersByStatus.computeIfAbsent(order.getStatus(), k -> ConcurrentHashMap.newKeySet())
            .add(order);
        
        stats.cacheWrites.incrementAndGet();
    }
    
    /**
     * Remove order from cache
     */
    public void removeFromCache(OrderEntity order) {
        if (order == null || order.getId() == null) return;
        
        orderById.remove(order.getId());
        
        if (order.getOrderRefNumber() != null) {
            orderByRefNumber.remove(order.getOrderRefNumber());
        }
        
        if (order.getClOrdId() != null) {
            orderByClOrdId.remove(order.getClOrdId());
        }
        
        removeFromIndexes(order);
        stats.cacheEvictions.incrementAndGet();
    }
    
    private void removeFromIndexes(OrderEntity order) {
        Set<OrderEntity> symbolOrders = ordersBySymbol.get(order.getSymbol());
        if (symbolOrders != null) symbolOrders.remove(order);
        
        if (order.getClientId() != null) {
            Set<OrderEntity> clientOrders = ordersByClient.get(order.getClientId());
            if (clientOrders != null) clientOrders.remove(order);
        }
        
        Set<OrderEntity> statusOrders = ordersByStatus.get(order.getStatus());
        if (statusOrders != null) statusOrders.remove(order);
    }
    
    // ================== Query Methods ==================
    
    /**
     * Get order by ID (O(1) lookup)
     */
    public OrderEntity getById(Long id) {
        stats.cacheReads.incrementAndGet();
        OrderEntity order = orderById.get(id);
        if (order != null) stats.cacheHits.incrementAndGet();
        else stats.cacheMisses.incrementAndGet();
        return order;
    }
    
    /**
     * Get order by reference number (O(1) lookup)
     */
    public OrderEntity getByOrderRefNumber(String orderRefNumber) {
        stats.cacheReads.incrementAndGet();
        OrderEntity order = orderByRefNumber.get(orderRefNumber);
        if (order != null) stats.cacheHits.incrementAndGet();
        else stats.cacheMisses.incrementAndGet();
        return order;
    }
    
    /**
     * Get order by ClOrdId (O(1) lookup)
     */
    public OrderEntity getByClOrdId(String clOrdId) {
        stats.cacheReads.incrementAndGet();
        OrderEntity order = orderByClOrdId.get(clOrdId);
        if (order != null) stats.cacheHits.incrementAndGet();
        else stats.cacheMisses.incrementAndGet();
        return order;
    }
    
    /**
     * Get all orders for a symbol
     */
    public List<OrderEntity> getBySymbol(String symbol) {
        stats.cacheReads.incrementAndGet();
        Set<OrderEntity> orders = ordersBySymbol.get(symbol);
        return orders != null ? new ArrayList<>(orders) : Collections.emptyList();
    }
    
    /**
     * Get active orders for a symbol (for matching engine)
     */
    public List<OrderEntity> getActiveOrdersBySymbol(String symbol) {
        stats.cacheReads.incrementAndGet();
        Set<OrderEntity> orders = ordersBySymbol.get(symbol);
        if (orders == null) return Collections.emptyList();
        
        return orders.stream()
            .filter(o -> ACTIVE_STATUSES.contains(o.getStatus()))
            .collect(Collectors.toList());
    }
    
    /**
     * Get all orders for a client
     */
    public List<OrderEntity> getByClient(String clientId) {
        stats.cacheReads.incrementAndGet();
        Set<OrderEntity> orders = ordersByClient.get(clientId);
        return orders != null ? new ArrayList<>(orders) : Collections.emptyList();
    }
    
    /**
     * Get active orders for a client
     */
    public List<OrderEntity> getActiveOrdersByClient(String clientId) {
        stats.cacheReads.incrementAndGet();
        Set<OrderEntity> orders = ordersByClient.get(clientId);
        if (orders == null) return Collections.emptyList();
        
        return orders.stream()
            .filter(o -> ACTIVE_STATUSES.contains(o.getStatus()))
            .collect(Collectors.toList());
    }
    
    /**
     * Get orders by status
     */
    public List<OrderEntity> getByStatus(String status) {
        stats.cacheReads.incrementAndGet();
        Set<OrderEntity> orders = ordersByStatus.get(status);
        return orders != null ? new ArrayList<>(orders) : Collections.emptyList();
    }
    
    /**
     * Get all active orders
     */
    public List<OrderEntity> getAllActiveOrders() {
        stats.cacheReads.incrementAndGet();
        return orderById.values().stream()
            .filter(o -> ACTIVE_STATUSES.contains(o.getStatus()))
            .collect(Collectors.toList());
    }
    
    /**
     * Count orders by symbol and status
     */
    public OrderCounts getOrderCounts() {
        OrderCounts counts = new OrderCounts();
        counts.totalOrders = orderById.size();
        counts.activeOrders = (int) orderById.values().stream()
            .filter(o -> ACTIVE_STATUSES.contains(o.getStatus()))
            .count();
        
        // Count by status
        for (Map.Entry<String, Set<OrderEntity>> entry : ordersByStatus.entrySet()) {
            counts.byStatus.put(entry.getKey(), entry.getValue().size());
        }
        
        // Count by symbol (active only)
        for (Map.Entry<String, Set<OrderEntity>> entry : ordersBySymbol.entrySet()) {
            int activeCount = (int) entry.getValue().stream()
                .filter(o -> ACTIVE_STATUSES.contains(o.getStatus()))
                .count();
            if (activeCount > 0) {
                counts.bySymbol.put(entry.getKey(), activeCount);
            }
        }
        
        return counts;
    }
    
    /**
     * Get cache statistics
     */
    public CacheStats getStats() {
        return stats;
    }
    
    /**
     * Clear the cache and reload from database
     */
    public void refresh() {
        LOG.info("Refreshing order cache...");
        orderById.clear();
        orderByRefNumber.clear();
        orderByClOrdId.clear();
        ordersBySymbol.clear();
        ordersByClient.clear();
        ordersByStatus.clear();
        loadFromDatabase();
    }
    
    /**
     * Get cache size
     */
    public int size() {
        return orderById.size();
    }
    
    // ================== Inner Classes ==================
    
    public static class CacheStats {
        public final AtomicLong cacheReads = new AtomicLong(0);
        public final AtomicLong cacheWrites = new AtomicLong(0);
        public final AtomicLong cacheHits = new AtomicLong(0);
        public final AtomicLong cacheMisses = new AtomicLong(0);
        public final AtomicLong cacheEvictions = new AtomicLong(0);
        public final AtomicLong cacheLoads = new AtomicLong(0);
        
        public double getHitRate() {
            long reads = cacheReads.get();
            return reads > 0 ? (double) cacheHits.get() / reads : 0;
        }
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("reads", cacheReads.get());
            map.put("writes", cacheWrites.get());
            map.put("hits", cacheHits.get());
            map.put("misses", cacheMisses.get());
            map.put("evictions", cacheEvictions.get());
            map.put("loads", cacheLoads.get());
            map.put("hitRate", String.format("%.2f%%", getHitRate() * 100));
            return map;
        }
    }
    
    public static class OrderCounts {
        public int totalOrders;
        public int activeOrders;
        public Map<String, Integer> byStatus = new HashMap<>();
        public Map<String, Integer> bySymbol = new HashMap<>();
    }
}
