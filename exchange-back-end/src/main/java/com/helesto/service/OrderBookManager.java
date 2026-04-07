package com.helesto.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.model.OrderEntity;

/**
 * G2-M1: Order-Book Manager
 * - Manages bid/ask order books per symbol
 * - Price-time priority FIFO matching
 * - Thread-safe operations with fine-grained locking
 * - Recovers active orders from database on startup
 */
@ApplicationScoped
public class OrderBookManager {

    private static final Logger LOG = LoggerFactory.getLogger(OrderBookManager.class);
    
    // Order books per symbol
    private final Map<String, OrderBook> orderBooks = new ConcurrentHashMap<>();
    
    // Lock per symbol for thread safety
    private final Map<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();
    
    @Inject
    ReferenceDataService referenceDataService;
    
    @Inject
    EntityManager entityManager;
    
    @PostConstruct
    public void init() {
        LOG.info("OrderBook Manager initializing...");
        recoverActiveOrders();
        LOG.info("OrderBook Manager initialized");
    }
    
    /**
     * Recover active orders from database and add to order book
     * Called on startup to restore order book state
     */
    private void recoverActiveOrders() {
        try {
            // Load all NEW and PARTIALLY_FILLED orders
            List<OrderEntity> activeOrders = entityManager.createQuery(
                    "SELECT o FROM OrderEntity o WHERE o.status IN ('NEW', 'PARTIALLY_FILLED') " +
                    "AND o.leavesQty > 0 ORDER BY o.createdAt ASC", OrderEntity.class)
                    .getResultList();
            
            int recovered = 0;
            for (OrderEntity order : activeOrders) {
                // Only add LIMIT orders to book (market orders execute immediately)
                if ("LIMIT".equals(order.getOrderType())) {
                    BookOrder bookOrder = new BookOrder();
                    bookOrder.orderId = order.getOrderRefNumber();
                    bookOrder.clOrdId = order.getClOrdId();
                    bookOrder.symbol = order.getSymbol();
                    bookOrder.side = order.getSide();
                    bookOrder.price = order.getPrice();
                    bookOrder.originalQty = order.getQuantity().intValue();
                    bookOrder.leavesQty = order.getLeavesQty().intValue();
                    bookOrder.orderType = order.getOrderType();
                    bookOrder.timeInForce = order.getTimeInForce();
                    bookOrder.clientId = order.getClientId();
                    bookOrder.timestamp = order.getCreatedAt() != null ? 
                            order.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() :
                            System.currentTimeMillis();
                    
                    addOrder(bookOrder);
                    recovered++;
                }
            }
            LOG.info("Recovered {} active orders to order book from database", recovered);
        } catch (Exception e) {
            LOG.error("Failed to recover orders from database: {}", e.getMessage());
        }
    }
    
    /**
     * Add an order to the book
     */
    public void addOrder(BookOrder order) {
        String symbol = order.symbol;
        ReentrantReadWriteLock lock = getLock(symbol);
        lock.writeLock().lock();
        try {
            OrderBook book = orderBooks.computeIfAbsent(symbol, k -> new OrderBook(k));
            book.addOrder(order);
            LOG.debug("Added order {} to {} book for {}", order.orderId, order.side, symbol);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Remove an order from the book
     */
    public boolean removeOrder(String symbol, String orderId) {
        ReentrantReadWriteLock lock = getLock(symbol);
        lock.writeLock().lock();
        try {
            OrderBook book = orderBooks.get(symbol);
            if (book != null) {
                boolean removed = book.removeOrder(orderId);
                LOG.debug("Removed order {} from book for {}: {}", orderId, symbol, removed);
                return removed;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Cancel (remove) an order from the book — the side parameter is accepted for API compatibility
     * but is not needed since the book locates the order by ID alone.
     */
    public void cancelOrder(String symbol, String side, String orderId) {
        removeOrder(symbol, orderId);
    }

    /**
     * Update order quantity (after partial fill)
     */
    public void updateOrderQuantity(String symbol, String orderId, int newLeavesQty) {
        ReentrantReadWriteLock lock = getLock(symbol);
        lock.writeLock().lock();
        try {
            OrderBook book = orderBooks.get(symbol);
            if (book != null) {
                book.updateQuantity(orderId, newLeavesQty);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Rebuild in-memory books from active DB state.
     * This drops any stale in-memory entries and reloads only active orders.
     */
    public synchronized void rebuildFromActiveOrders() {
        orderBooks.clear();
        locks.clear();
        recoverActiveOrders();
    }
    
    /**
     * Get the best bid price for a symbol
     */
    public Double getBestBid(String symbol) {
        ReentrantReadWriteLock lock = getLock(symbol);
        lock.readLock().lock();
        try {
            OrderBook book = orderBooks.get(symbol);
            return book != null ? book.getBestBid() : null;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get the best ask price for a symbol
     */
    public Double getBestAsk(String symbol) {
        ReentrantReadWriteLock lock = getLock(symbol);
        lock.readLock().lock();
        try {
            OrderBook book = orderBooks.get(symbol);
            return book != null ? book.getBestAsk() : null;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get all bid orders at a price level
     */
    public List<BookOrder> getBidsAtPrice(String symbol, double price) {
        ReentrantReadWriteLock lock = getLock(symbol);
        lock.readLock().lock();
        try {
            OrderBook book = orderBooks.get(symbol);
            return book != null ? book.getBidsAtPrice(price) : Collections.emptyList();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get all ask orders at a price level
     */
    public List<BookOrder> getAsksAtPrice(String symbol, double price) {
        ReentrantReadWriteLock lock = getLock(symbol);
        lock.readLock().lock();
        try {
            OrderBook book = orderBooks.get(symbol);
            return book != null ? book.getAsksAtPrice(price) : Collections.emptyList();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get top N bid levels
     */
    public List<PriceLevel> getTopBids(String symbol, int levels) {
        ReentrantReadWriteLock lock = getLock(symbol);
        lock.readLock().lock();
        try {
            OrderBook book = orderBooks.get(symbol);
            return book != null ? book.getTopBids(levels) : Collections.emptyList();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get top N ask levels
     */
    public List<PriceLevel> getTopAsks(String symbol, int levels) {
        ReentrantReadWriteLock lock = getLock(symbol);
        lock.readLock().lock();
        try {
            OrderBook book = orderBooks.get(symbol);
            return book != null ? book.getTopAsks(levels) : Collections.emptyList();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get order book snapshot
     */
    public OrderBookSnapshot getSnapshot(String symbol, int depth) {
        ReentrantReadWriteLock lock = getLock(symbol);
        lock.readLock().lock();
        try {
            OrderBook book = orderBooks.get(symbol);
            if (book == null) {
                return new OrderBookSnapshot(symbol, Collections.emptyList(), Collections.emptyList());
            }
            return new OrderBookSnapshot(symbol, book.getTopBids(depth), book.getTopAsks(depth));
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get matchable orders on opposite side
     * For a BUY order, get asks <= limit price
     * For a SELL order, get bids >= limit price
     */
    public List<BookOrder> getMatchableOrders(String symbol, String side, double limitPrice) {
        ReentrantReadWriteLock lock = getLock(symbol);
        lock.readLock().lock();
        try {
            OrderBook book = orderBooks.get(symbol);
            if (book == null) {
                return Collections.emptyList();
            }
            
            if ("BUY".equals(side) || "1".equals(side)) {
                // Buy order matches against asks at or below limit price
                return book.getAsksAtOrBelowPrice(limitPrice);
            } else {
                // Sell order matches against bids at or above limit price
                return book.getBidsAtOrAbovePrice(limitPrice);
            }
        } finally {
            lock.readLock().unlock();
        }
    }
    
    private ReentrantReadWriteLock getLock(String symbol) {
        return locks.computeIfAbsent(symbol, k -> new ReentrantReadWriteLock());
    }
    
    // ================== Inner Classes ==================
    
    /**
     * Order Book for a single symbol
     */
    public static class OrderBook {
        private final String symbol;
        
        // Bids: descending by price, then by time (FIFO)
        // TreeMap key is negative price for descending order
        private final TreeMap<Double, LinkedList<BookOrder>> bids = new TreeMap<>();
        
        // Asks: ascending by price, then by time (FIFO)
        private final TreeMap<Double, LinkedList<BookOrder>> asks = new TreeMap<>();
        
        // Quick lookup by orderId
        private final Map<String, BookOrder> orderIndex = new HashMap<>();
        
        public OrderBook(String symbol) {
            this.symbol = symbol;
        }
        
        public void addOrder(BookOrder order) {
            TreeMap<Double, LinkedList<BookOrder>> book;
            Double key;
            
            if ("BUY".equals(order.side) || "1".equals(order.side)) {
                book = bids;
                key = -order.price; // Negative for descending order
            } else {
                book = asks;
                key = order.price;
            }
            
            book.computeIfAbsent(key, k -> new LinkedList<>()).addLast(order);
            orderIndex.put(order.orderId, order);
        }
        
        public boolean removeOrder(String orderId) {
            BookOrder order = orderIndex.remove(orderId);
            if (order == null) return false;
            
            TreeMap<Double, LinkedList<BookOrder>> book;
            Double key;
            
            if ("BUY".equals(order.side) || "1".equals(order.side)) {
                book = bids;
                key = -order.price;
            } else {
                book = asks;
                key = order.price;
            }
            
            LinkedList<BookOrder> level = book.get(key);
            if (level != null) {
                level.remove(order);
                if (level.isEmpty()) {
                    book.remove(key);
                }
            }
            return true;
        }
        
        public void updateQuantity(String orderId, int newQty) {
            BookOrder order = orderIndex.get(orderId);
            if (order != null) {
                order.leavesQty = newQty;
                if (newQty <= 0) {
                    removeOrder(orderId);
                }
            }
        }
        
        public Double getBestBid() {
            Map.Entry<Double, LinkedList<BookOrder>> entry = bids.firstEntry();
            return entry != null ? -entry.getKey() : null;
        }
        
        public Double getBestAsk() {
            Map.Entry<Double, LinkedList<BookOrder>> entry = asks.firstEntry();
            return entry != null ? entry.getKey() : null;
        }
        
        public List<BookOrder> getBidsAtPrice(double price) {
            LinkedList<BookOrder> level = bids.get(-price);
            return level != null ? new ArrayList<>(level) : Collections.emptyList();
        }
        
        public List<BookOrder> getAsksAtPrice(double price) {
            LinkedList<BookOrder> level = asks.get(price);
            return level != null ? new ArrayList<>(level) : Collections.emptyList();
        }
        
        public List<BookOrder> getBidsAtOrAbovePrice(double price) {
            List<BookOrder> result = new ArrayList<>();
            // bids with negative keys; -key >= price means key <= -price
            for (Map.Entry<Double, LinkedList<BookOrder>> entry : bids.entrySet()) {
                if (-entry.getKey() >= price) {
                    result.addAll(entry.getValue());
                } else {
                    break; // No more matching bids (prices decrease)
                }
            }
            return result;
        }
        
        public List<BookOrder> getAsksAtOrBelowPrice(double price) {
            List<BookOrder> result = new ArrayList<>();
            for (Map.Entry<Double, LinkedList<BookOrder>> entry : asks.entrySet()) {
                if (entry.getKey() <= price) {
                    result.addAll(entry.getValue());
                } else {
                    break; // No more matching asks (prices increase)
                }
            }
            return result;
        }
        
        public List<PriceLevel> getTopBids(int levels) {
            List<PriceLevel> result = new ArrayList<>();
            int count = 0;
            for (Map.Entry<Double, LinkedList<BookOrder>> entry : bids.entrySet()) {
                if (count >= levels) break;
                double price = -entry.getKey();
                int totalQty = entry.getValue().stream().mapToInt(o -> o.leavesQty).sum();
                int orderCount = entry.getValue().size();
                result.add(new PriceLevel(price, totalQty, orderCount));
                count++;
            }
            return result;
        }
        
        public List<PriceLevel> getTopAsks(int levels) {
            List<PriceLevel> result = new ArrayList<>();
            int count = 0;
            for (Map.Entry<Double, LinkedList<BookOrder>> entry : asks.entrySet()) {
                if (count >= levels) break;
                double price = entry.getKey();
                int totalQty = entry.getValue().stream().mapToInt(o -> o.leavesQty).sum();
                int orderCount = entry.getValue().size();
                result.add(new PriceLevel(price, totalQty, orderCount));
                count++;
            }
            return result;
        }
    }
    
    /**
     * Order representation in the book
     */
    public static class BookOrder {
        public String orderId;
        public String clOrdId;
        public String symbol;
        public String side; // BUY/SELL or 1/2
        public double price;
        public double stopPrice; // For STOP and STOP_LIMIT orders
        public int originalQty;
        public int leavesQty;
        public String orderType;
        public String timeInForce;
        public String clientId;
        public long timestamp;
        
        public BookOrder() {
            this.timestamp = System.currentTimeMillis();
        }
        
        public BookOrder(String orderId, String symbol, String side, double price, int qty) {
            this.orderId = orderId;
            this.symbol = symbol;
            this.side = side;
            this.price = price;
            this.originalQty = qty;
            this.leavesQty = qty;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    /**
     * Price level aggregation
     */
    public static class PriceLevel {
        public double price;
        public int totalQuantity;
        public int orderCount;
        
        public PriceLevel(double price, int totalQuantity, int orderCount) {
            this.price = price;
            this.totalQuantity = totalQuantity;
            this.orderCount = orderCount;
        }
    }
    
    /**
     * Order book snapshot for API responses
     */
    public static class OrderBookSnapshot {
        public String symbol;
        public List<PriceLevel> bids;
        public List<PriceLevel> asks;
        public long timestamp;
        
        public OrderBookSnapshot(String symbol, List<PriceLevel> bids, List<PriceLevel> asks) {
            this.symbol = symbol;
            this.bids = bids;
            this.asks = asks;
            this.timestamp = System.currentTimeMillis();
        }
    }
}
