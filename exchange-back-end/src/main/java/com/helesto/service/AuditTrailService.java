package com.helesto.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.model.OrderEntity;
import com.helesto.model.TradeEntity;

/**
 * Audit Trail Service
 * - Comprehensive event logging for regulatory compliance (SEC Rule 613/CAT)
 * - Order lifecycle tracking
 * - FIX message audit log
 * - Trade reporting (15c3-5 compliance)
 * - Queryable audit history
 * - File-based and in-memory storage
 */
@ApplicationScoped
public class AuditTrailService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditTrailService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    
    @Inject
    TelemetryService telemetryService;
    
    // Audit event sequence
    private final AtomicLong eventSequence = new AtomicLong(1);
    
    // In-memory audit cache (most recent events)
    private final ConcurrentLinkedDeque<AuditEvent> eventCache = new ConcurrentLinkedDeque<>();
    private static final int MAX_CACHE_SIZE = 100_000;
    
    // Event queues for async writing
    private final BlockingQueue<AuditEvent> writeQueue = new LinkedBlockingQueue<>(50_000);
    
    // Order lifecycle tracking
    private final Map<String, OrderLifecycle> orderLifecycles = new ConcurrentHashMap<>();
    
    // Audit statistics
    private final AuditStatistics statistics = new AuditStatistics();
    
    // File writers
    private BufferedWriter orderAuditWriter;
    private BufferedWriter tradeAuditWriter;
    private BufferedWriter messageAuditWriter;
    private BufferedWriter systemAuditWriter;
    
    // Async writer executor
    private ExecutorService writerExecutor;
    private volatile boolean running = true;
    
    // Audit file configuration
    private String auditDirectory = "target/audit";
    private String currentDate;
    
    @PostConstruct
    public void init() {
        LOG.info("Initializing Audit Trail Service...");
        
        try {
            // Create audit directory
            Path auditPath = Paths.get(auditDirectory);
            if (!Files.exists(auditPath)) {
                Files.createDirectories(auditPath);
            }
            
            // Initialize audit files
            currentDate = LocalDate.now().format(DATE_FORMAT);
            initializeAuditFiles();
            
            // Start async writer
            writerExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "audit-writer");
                t.setDaemon(true);
                return t;
            });
            writerExecutor.submit(this::asyncWriteLoop);
            
            LOG.info("Audit Trail Service initialized - writing to {}", auditDirectory);
            
        } catch (Exception e) {
            LOG.error("Failed to initialize Audit Trail Service: {}", e.getMessage());
        }
    }
    
    @PreDestroy
    public void cleanup() {
        running = false;
        
        // Drain remaining events
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        if (writerExecutor != null) {
            writerExecutor.shutdown();
        }
        
        closeWriters();
    }
    
    private void initializeAuditFiles() throws IOException {
        String date = LocalDate.now().format(DATE_FORMAT);
        
        orderAuditWriter = createWriter("orders_" + date + ".csv", 
            "Timestamp,EventId,EventType,OrderId,ClOrdId,ClientId,Symbol,Side,OrderType,Quantity,Price,Status,Details");
        tradeAuditWriter = createWriter("trades_" + date + ".csv",
            "Timestamp,EventId,TradeId,ExecId,Symbol,Price,Quantity,BuyClientId,SellClientId,BuyOrderId,SellOrderId,Status");
        messageAuditWriter = createWriter("messages_" + date + ".csv",
            "Timestamp,EventId,Direction,MessageType,SenderCompId,TargetCompId,MsgSeqNum,Content");
        systemAuditWriter = createWriter("system_" + date + ".csv",
            "Timestamp,EventId,EventType,Component,Details,UserId");
    }
    
    private BufferedWriter createWriter(String filename, String header) throws IOException {
        Path filePath = Paths.get(auditDirectory, filename);
        boolean exists = Files.exists(filePath);
        BufferedWriter writer = Files.newBufferedWriter(filePath, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (!exists) {
            writer.write(header);
            writer.newLine();
        }
        return writer;
    }
    
    private void closeWriters() {
        try {
            if (orderAuditWriter != null) orderAuditWriter.close();
            if (tradeAuditWriter != null) tradeAuditWriter.close();
            if (messageAuditWriter != null) messageAuditWriter.close();
            if (systemAuditWriter != null) systemAuditWriter.close();
        } catch (IOException e) {
            LOG.error("Error closing audit writers: {}", e.getMessage());
        }
    }
    
    // ==================== Order Audit ====================
    
    /**
     * Log order received event
     */
    public void logOrderReceived(OrderEntity order, String source) {
        AuditEvent event = createOrderEvent(AuditEventType.ORDER_RECEIVED, order);
        event.details = "Source: " + source;
        queueEvent(event);
        
        // Start order lifecycle tracking
        OrderLifecycle lifecycle = new OrderLifecycle();
        lifecycle.orderId = order.getClOrdId();
        lifecycle.orderRefNumber = order.getOrderRefNumber();
        lifecycle.clientId = order.getClientId();
        lifecycle.symbol = order.getSymbol();
        lifecycle.side = order.getSide();
        lifecycle.orderType = order.getOrderType();
        lifecycle.quantity = order.getQuantity();
        lifecycle.price = order.getPrice();
        lifecycle.receivedTime = LocalDateTime.now();
        lifecycle.events.add(event);
        orderLifecycles.put(order.getClOrdId(), lifecycle);
        
        statistics.ordersReceived.incrementAndGet();
    }
    
    /**
     * Log order validated event
     */
    public void logOrderValidated(OrderEntity order, boolean valid, String validationMessage) {
        AuditEvent event = createOrderEvent(
            valid ? AuditEventType.ORDER_VALIDATED : AuditEventType.ORDER_REJECTED,
            order
        );
        event.details = validationMessage;
        queueEvent(event);
        
        OrderLifecycle lifecycle = orderLifecycles.get(order.getClOrdId());
        if (lifecycle != null) {
            lifecycle.validatedTime = LocalDateTime.now();
            lifecycle.events.add(event);
            if (!valid) {
                lifecycle.status = "REJECTED";
                lifecycle.rejectionReason = validationMessage;
            }
        }
        
        if (!valid) {
            statistics.ordersRejected.incrementAndGet();
        }
    }
    
    /**
     * Log order acknowledged event
     */
    public void logOrderAcknowledged(OrderEntity order) {
        AuditEvent event = createOrderEvent(AuditEventType.ORDER_ACKNOWLEDGED, order);
        queueEvent(event);
        
        OrderLifecycle lifecycle = orderLifecycles.get(order.getClOrdId());
        if (lifecycle != null) {
            lifecycle.acknowledgedTime = LocalDateTime.now();
            lifecycle.status = "NEW";
            lifecycle.events.add(event);
        }
        
        statistics.ordersAcknowledged.incrementAndGet();
    }
    
    /**
     * Log order partially filled event
     */
    public void logOrderPartialFill(OrderEntity order, long filledQty, double fillPrice, String execId) {
        AuditEvent event = createOrderEvent(AuditEventType.ORDER_PARTIAL_FILL, order);
        event.details = String.format("ExecId: %s, FilledQty: %d, FillPrice: %.4f", execId, filledQty, fillPrice);
        queueEvent(event);
        
        OrderLifecycle lifecycle = orderLifecycles.get(order.getClOrdId());
        if (lifecycle != null) {
            lifecycle.status = "PARTIALLY_FILLED";
            lifecycle.filledQuantity += filledQty;
            lifecycle.events.add(event);
        }
    }
    
    /**
     * Log order filled event
     */
    public void logOrderFilled(OrderEntity order, String execId) {
        AuditEvent event = createOrderEvent(AuditEventType.ORDER_FILLED, order);
        event.details = "ExecId: " + execId;
        queueEvent(event);
        
        OrderLifecycle lifecycle = orderLifecycles.get(order.getClOrdId());
        if (lifecycle != null) {
            lifecycle.completedTime = LocalDateTime.now();
            lifecycle.status = "FILLED";
            lifecycle.events.add(event);
        }
        
        statistics.ordersFilled.incrementAndGet();
    }
    
    /**
     * Log order cancelled event
     */
    public void logOrderCancelled(OrderEntity order, String reason) {
        AuditEvent event = createOrderEvent(AuditEventType.ORDER_CANCELLED, order);
        event.details = "Reason: " + reason;
        queueEvent(event);
        
        OrderLifecycle lifecycle = orderLifecycles.get(order.getClOrdId());
        if (lifecycle != null) {
            lifecycle.completedTime = LocalDateTime.now();
            lifecycle.status = "CANCELLED";
            lifecycle.cancellationReason = reason;
            lifecycle.events.add(event);
        }
        
        statistics.ordersCancelled.incrementAndGet();
    }
    
    /**
     * Log order replaced event
     */
    public void logOrderReplaced(OrderEntity originalOrder, OrderEntity newOrder) {
        AuditEvent event = createOrderEvent(AuditEventType.ORDER_REPLACED, originalOrder);
        event.details = String.format("NewClOrdId: %s, NewQty: %d, NewPrice: %.4f",
                newOrder.getClOrdId(), newOrder.getQuantity(), 
                newOrder.getPrice() != null ? newOrder.getPrice() : 0.0);
        queueEvent(event);
    }
    
    // ==================== Convenience Methods for Orchestrator ====================
    
    /**
     * Log order received with individual parameters
     */
    public void logOrderReceived(String clOrdId, String orderRefNumber, String clientId, 
                                 String symbol, String side, Long quantity, Double price, String orderType) {
        AuditEvent event = createBasicOrderEvent(AuditEventType.ORDER_RECEIVED, clOrdId, orderRefNumber,
                clientId, symbol, side, quantity, price, orderType);
        event.details = "Order received from client";
        queueEvent(event);
        startLifecycleTracking(clOrdId, orderRefNumber, clientId, symbol, side, quantity, price, orderType);
        statistics.ordersReceived.incrementAndGet();
    }
    
    /**
     * Log order accepted event
     */
    public void logOrderAccepted(String clOrdId, String orderRefNumber) {
        AuditEvent event = new AuditEvent();
        event.eventId = eventSequence.getAndIncrement();
        event.timestamp = LocalDateTime.now();
        event.eventType = AuditEventType.ORDER_ACKNOWLEDGED;
        event.category = AuditCategory.ORDER;
        event.clOrdId = clOrdId;
        event.orderId = orderRefNumber;
        event.status = "NEW";
        queueEvent(event);
        
        OrderLifecycle lifecycle = orderLifecycles.get(clOrdId);
        if (lifecycle != null) {
            lifecycle.acknowledgedTime = LocalDateTime.now();
            lifecycle.status = "NEW";
            lifecycle.events.add(event);
        }
        statistics.ordersAcknowledged.incrementAndGet();
    }
    
    /**
     * Log order rejected with individual parameters
     */
    public void logOrderRejected(String clOrdId, String rejectCode, String reason) {
        AuditEvent event = new AuditEvent();
        event.eventId = eventSequence.getAndIncrement();
        event.timestamp = LocalDateTime.now();
        event.eventType = AuditEventType.ORDER_REJECTED;
        event.category = AuditCategory.ORDER;
        event.clOrdId = clOrdId;
        event.status = "REJECTED";
        event.details = "RejectCode: " + rejectCode + ", Reason: " + reason;
        queueEvent(event);
        
        OrderLifecycle lifecycle = orderLifecycles.get(clOrdId);
        if (lifecycle != null) {
            lifecycle.status = "REJECTED";
            lifecycle.rejectionReason = reason;
            lifecycle.completedTime = LocalDateTime.now();
            lifecycle.events.add(event);
        }
        statistics.ordersRejected.incrementAndGet();
    }
    
    /**
     * Log order fill event
     */
    public void logOrderFill(String clOrdId, String orderRefNumber, int fillQty, double fillPrice, String tradeId) {
        AuditEvent event = new AuditEvent();
        event.eventId = eventSequence.getAndIncrement();
        event.timestamp = LocalDateTime.now();
        event.eventType = AuditEventType.ORDER_PARTIAL_FILL;
        event.category = AuditCategory.ORDER;
        event.clOrdId = clOrdId;
        event.orderId = orderRefNumber;
        event.tradeId = tradeId;
        event.quantity = (long) fillQty;
        event.price = fillPrice;
        event.details = String.format("Fill: %d @ %.4f, TradeId: %s", fillQty, fillPrice, tradeId);
        queueEvent(event);
        
        OrderLifecycle lifecycle = orderLifecycles.get(clOrdId);
        if (lifecycle != null) {
            lifecycle.filledQuantity += fillQty;
            lifecycle.events.add(event);
        }
    }
    
    /**
     * Log order completely filled
     */
    public void logOrderFilled(String clOrdId, String orderRefNumber) {
        AuditEvent event = new AuditEvent();
        event.eventId = eventSequence.getAndIncrement();
        event.timestamp = LocalDateTime.now();
        event.eventType = AuditEventType.ORDER_FILLED;
        event.category = AuditCategory.ORDER;
        event.clOrdId = clOrdId;
        event.orderId = orderRefNumber;
        event.status = "FILLED";
        queueEvent(event);
        
        OrderLifecycle lifecycle = orderLifecycles.get(clOrdId);
        if (lifecycle != null) {
            lifecycle.status = "FILLED";
            lifecycle.completedTime = LocalDateTime.now();
            lifecycle.events.add(event);
        }
        statistics.ordersFilled.incrementAndGet();
    }
    
    /**
     * Log order partial fill
     */
    public void logOrderPartialFill(String clOrdId, String orderRefNumber, int filledQty, int leavesQty) {
        AuditEvent event = new AuditEvent();
        event.eventId = eventSequence.getAndIncrement();
        event.timestamp = LocalDateTime.now();
        event.eventType = AuditEventType.ORDER_PARTIAL_FILL;
        event.category = AuditCategory.ORDER;
        event.clOrdId = clOrdId;
        event.orderId = orderRefNumber;
        event.status = "PARTIAL";
        event.details = String.format("Filled: %d, Leaves: %d", filledQty, leavesQty);
        queueEvent(event);
        
        OrderLifecycle lifecycle = orderLifecycles.get(clOrdId);
        if (lifecycle != null) {
            lifecycle.status = "PARTIALLY_FILLED";
            lifecycle.events.add(event);
        }
    }
    
    /**
     * Log cancel request received
     */
    public void logCancelReceived(String orderRefNumber, String clientId, String reason) {
        AuditEvent event = new AuditEvent();
        event.eventId = eventSequence.getAndIncrement();
        event.timestamp = LocalDateTime.now();
        event.eventType = AuditEventType.CANCEL_REQUEST;
        event.category = AuditCategory.ORDER;
        event.orderId = orderRefNumber;
        event.clientId = clientId;
        event.details = "Cancel requested: " + (reason != null ? reason : "No reason given");
        queueEvent(event);
    }
    
    /**
     * Log order canceled
     */
    public void logOrderCanceled(String clOrdId, String orderRefNumber, String reason) {
        AuditEvent event = new AuditEvent();
        event.eventId = eventSequence.getAndIncrement();
        event.timestamp = LocalDateTime.now();
        event.eventType = AuditEventType.ORDER_CANCELLED;
        event.category = AuditCategory.ORDER;
        event.clOrdId = clOrdId;
        event.orderId = orderRefNumber;
        event.status = "CANCELED";
        event.details = "Reason: " + reason;
        queueEvent(event);
        
        OrderLifecycle lifecycle = orderLifecycles.get(clOrdId);
        if (lifecycle != null) {
            lifecycle.status = "CANCELLED";
            lifecycle.cancellationReason = reason;
            lifecycle.completedTime = LocalDateTime.now();
            lifecycle.events.add(event);
        }
        statistics.ordersCancelled.incrementAndGet();
    }
    
    /**
     * Log order processing error
     */
    public void logOrderError(String clOrdId, String orderRefNumber, String errorMessage) {
        AuditEvent event = new AuditEvent();
        event.eventId = eventSequence.getAndIncrement();
        event.timestamp = LocalDateTime.now();
        event.eventType = AuditEventType.SYSTEM_EVENT;
        event.category = AuditCategory.ORDER;
        event.clOrdId = clOrdId;
        event.orderId = orderRefNumber;
        event.status = "ERROR";
        event.details = "Error: " + errorMessage;
        queueEvent(event);
    }
    
    private AuditEvent createBasicOrderEvent(AuditEventType type, String clOrdId, String orderRefNumber,
                                             String clientId, String symbol, String side, 
                                             Long quantity, Double price, String orderType) {
        AuditEvent event = new AuditEvent();
        event.eventId = eventSequence.getAndIncrement();
        event.timestamp = LocalDateTime.now();
        event.eventType = type;
        event.category = AuditCategory.ORDER;
        event.orderId = orderRefNumber;
        event.clOrdId = clOrdId;
        event.clientId = clientId;
        event.symbol = symbol;
        event.side = side;
        event.orderType = orderType;
        event.quantity = quantity;
        event.price = price;
        return event;
    }
    
    private void startLifecycleTracking(String clOrdId, String orderRefNumber, String clientId, 
                                        String symbol, String side, Long quantity, Double price, String orderType) {
        OrderLifecycle lifecycle = new OrderLifecycle();
        lifecycle.orderId = clOrdId;
        lifecycle.orderRefNumber = orderRefNumber;
        lifecycle.clientId = clientId;
        lifecycle.symbol = symbol;
        lifecycle.side = side;
        lifecycle.orderType = orderType;
        lifecycle.quantity = quantity;
        lifecycle.price = price;
        lifecycle.receivedTime = LocalDateTime.now();
        orderLifecycles.put(clOrdId, lifecycle);
    }
    
    private AuditEvent createOrderEvent(AuditEventType type, OrderEntity order) {
        AuditEvent event = new AuditEvent();
        event.eventId = eventSequence.getAndIncrement();
        event.timestamp = LocalDateTime.now();
        event.eventType = type;
        event.category = AuditCategory.ORDER;
        event.orderId = order.getOrderRefNumber();
        event.clOrdId = order.getClOrdId();
        event.clientId = order.getClientId();
        event.symbol = order.getSymbol();
        event.side = order.getSide();
        event.orderType = order.getOrderType();
        event.quantity = order.getQuantity();
        event.price = order.getPrice();
        event.status = order.getStatus();
        return event;
    }
    
    // ==================== Trade Audit ====================
    
    /**
     * Log trade execution
     */
    public void logTradeExecution(TradeEntity trade) {
        AuditEvent event = new AuditEvent();
        event.eventId = eventSequence.getAndIncrement();
        event.timestamp = LocalDateTime.now();
        event.eventType = AuditEventType.TRADE_EXECUTED;
        event.category = AuditCategory.TRADE;
        event.tradeId = trade.getTradeId();
        event.execId = trade.getExecId();
        event.symbol = trade.getSymbol();
        event.price = trade.getPrice();
        event.quantity = trade.getQuantity() != null ? trade.getQuantity().longValue() : 0L;
        event.buyClientId = trade.getBuyClientId();
        event.sellClientId = trade.getSellClientId();
        event.buyOrderId = trade.getBuyOrderId();
        event.sellOrderId = trade.getSellOrderId();
        event.details = "AggressorSide: " + trade.getAggressorSide();
        queueEvent(event);
        
        statistics.tradesExecuted.incrementAndGet();
    }
    
    /**
     * Log trade bust/cancel
     */
    public void logTradeBust(String tradeId, String reason) {
        AuditEvent event = new AuditEvent();
        event.eventId = eventSequence.getAndIncrement();
        event.timestamp = LocalDateTime.now();
        event.eventType = AuditEventType.TRADE_BUSTED;
        event.category = AuditCategory.TRADE;
        event.tradeId = tradeId;
        event.details = "Reason: " + reason;
        queueEvent(event);
        
        statistics.tradesBusted.incrementAndGet();
    }
    
    // ==================== FIX Message Audit ====================
    
    /**
     * Log FIX message received
     */
    public void logFixMessageReceived(String messageType, String senderCompId, String targetCompId, 
                                      long msgSeqNum, String rawMessage) {
        AuditEvent event = new AuditEvent();
        event.eventId = eventSequence.getAndIncrement();
        event.timestamp = LocalDateTime.now();
        event.eventType = AuditEventType.FIX_MESSAGE_RECEIVED;
        event.category = AuditCategory.MESSAGE;
        event.direction = "INBOUND";
        event.messageType = messageType;
        event.senderCompId = senderCompId;
        event.targetCompId = targetCompId;
        event.msgSeqNum = msgSeqNum;
        event.rawMessage = sanitizeMessage(rawMessage);
        queueEvent(event);
        
        statistics.messagesReceived.incrementAndGet();
    }
    
    /**
     * Log FIX message sent
     */
    public void logFixMessageSent(String messageType, String senderCompId, String targetCompId,
                                  long msgSeqNum, String rawMessage) {
        AuditEvent event = new AuditEvent();
        event.eventId = eventSequence.getAndIncrement();
        event.timestamp = LocalDateTime.now();
        event.eventType = AuditEventType.FIX_MESSAGE_SENT;
        event.category = AuditCategory.MESSAGE;
        event.direction = "OUTBOUND";
        event.messageType = messageType;
        event.senderCompId = senderCompId;
        event.targetCompId = targetCompId;
        event.msgSeqNum = msgSeqNum;
        event.rawMessage = sanitizeMessage(rawMessage);
        queueEvent(event);
        
        statistics.messagesSent.incrementAndGet();
    }
    
    private String sanitizeMessage(String message) {
        // Remove SOH characters and truncate for storage
        if (message == null) return "";
        String sanitized = message.replace('\001', '|');
        if (sanitized.length() > 2000) {
            sanitized = sanitized.substring(0, 2000) + "...";
        }
        return sanitized;
    }
    
    // ==================== System Audit ====================
    
    /**
     * Log system event
     */
    public void logSystemEvent(String component, String eventType, String details) {
        logSystemEvent(component, eventType, details, null);
    }
    
    /**
     * Log system event with user
     */
    public void logSystemEvent(String component, String eventType, String details, String userId) {
        AuditEvent event = new AuditEvent();
        event.eventId = eventSequence.getAndIncrement();
        event.timestamp = LocalDateTime.now();
        event.eventType = AuditEventType.SYSTEM_EVENT;
        event.category = AuditCategory.SYSTEM;
        event.component = component;
        event.details = eventType + ": " + details;
        event.userId = userId;
        queueEvent(event);
        
        statistics.systemEvents.incrementAndGet();
    }
    
    /**
     * Log configuration change
     */
    public void logConfigurationChange(String component, String setting, String oldValue, 
                                       String newValue, String userId) {
        AuditEvent event = new AuditEvent();
        event.eventId = eventSequence.getAndIncrement();
        event.timestamp = LocalDateTime.now();
        event.eventType = AuditEventType.CONFIG_CHANGE;
        event.category = AuditCategory.SYSTEM;
        event.component = component;
        event.details = String.format("Setting: %s, OldValue: %s, NewValue: %s", setting, oldValue, newValue);
        event.userId = userId;
        queueEvent(event);
    }
    
    /**
     * Log security event (login, permission change, etc.)
     */
    public void logSecurityEvent(String eventType, String details, String userId) {
        AuditEvent event = new AuditEvent();
        event.eventId = eventSequence.getAndIncrement();
        event.timestamp = LocalDateTime.now();
        event.eventType = AuditEventType.SECURITY_EVENT;
        event.category = AuditCategory.SYSTEM;
        event.details = eventType + ": " + details;
        event.userId = userId;
        queueEvent(event);
    }
    
    // ==================== Event Queue Management ====================
    
    private void queueEvent(AuditEvent event) {
        // Add to cache
        eventCache.addLast(event);
        while (eventCache.size() > MAX_CACHE_SIZE) {
            eventCache.pollFirst();
        }
        
        // Queue for async writing
        if (!writeQueue.offer(event)) {
            LOG.warn("Audit write queue full, event may be lost: {}", event.eventId);
            statistics.droppedEvents.incrementAndGet();
        }
    }
    
    private void asyncWriteLoop() {
        while (running || !writeQueue.isEmpty()) {
            try {
                AuditEvent event = writeQueue.poll(100, TimeUnit.MILLISECONDS);
                if (event != null) {
                    writeEvent(event);
                }
                
                // Check for date rollover
                String today = LocalDate.now().format(DATE_FORMAT);
                if (!today.equals(currentDate)) {
                    rolloverFiles();
                    currentDate = today;
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.error("Error writing audit event: {}", e.getMessage());
            }
        }
    }
    
    private void writeEvent(AuditEvent event) throws IOException {
        switch (event.category) {
            case ORDER:
                writeOrderEvent(event);
                break;
            case TRADE:
                writeTradeEvent(event);
                break;
            case MESSAGE:
                writeMessageEvent(event);
                break;
            case SYSTEM:
                writeSystemEvent(event);
                break;
        }
    }
    
    private void writeOrderEvent(AuditEvent event) throws IOException {
        String line = String.format("%s,%d,%s,%s,%s,%s,%s,%s,%s,%d,%.4f,%s,\"%s\"",
                event.timestamp.format(TIMESTAMP_FORMAT),
                event.eventId,
                event.eventType,
                nullSafe(event.orderId),
                nullSafe(event.clOrdId),
                nullSafe(event.clientId),
                nullSafe(event.symbol),
                nullSafe(event.side),
                nullSafe(event.orderType),
                event.quantity != null ? event.quantity : 0,
                event.price != null ? event.price : 0.0,
                nullSafe(event.status),
                nullSafe(event.details));
        orderAuditWriter.write(line);
        orderAuditWriter.newLine();
        orderAuditWriter.flush();
    }
    
    private void writeTradeEvent(AuditEvent event) throws IOException {
        String line = String.format("%s,%d,%s,%s,%s,%.4f,%d,%s,%s,%s,%s,%s",
                event.timestamp.format(TIMESTAMP_FORMAT),
                event.eventId,
                nullSafe(event.tradeId),
                nullSafe(event.execId),
                nullSafe(event.symbol),
                event.price != null ? event.price : 0.0,
                event.quantity != null ? event.quantity : 0,
                nullSafe(event.buyClientId),
                nullSafe(event.sellClientId),
                nullSafe(event.buyOrderId),
                nullSafe(event.sellOrderId),
                nullSafe(event.details));
        tradeAuditWriter.write(line);
        tradeAuditWriter.newLine();
        tradeAuditWriter.flush();
    }
    
    private void writeMessageEvent(AuditEvent event) throws IOException {
        String line = String.format("%s,%d,%s,%s,%s,%s,%d,\"%s\"",
                event.timestamp.format(TIMESTAMP_FORMAT),
                event.eventId,
                nullSafe(event.direction),
                nullSafe(event.messageType),
                nullSafe(event.senderCompId),
                nullSafe(event.targetCompId),
                event.msgSeqNum,
                nullSafe(event.rawMessage).replace("\"", "\"\""));
        messageAuditWriter.write(line);
        messageAuditWriter.newLine();
        messageAuditWriter.flush();
    }
    
    private void writeSystemEvent(AuditEvent event) throws IOException {
        String line = String.format("%s,%d,%s,%s,\"%s\",%s",
                event.timestamp.format(TIMESTAMP_FORMAT),
                event.eventId,
                event.eventType,
                nullSafe(event.component),
                nullSafe(event.details).replace("\"", "\"\""),
                nullSafe(event.userId));
        systemAuditWriter.write(line);
        systemAuditWriter.newLine();
        systemAuditWriter.flush();
    }
    
    private String nullSafe(String value) {
        return value != null ? value : "";
    }
    
    private void rolloverFiles() throws IOException {
        LOG.info("Rolling over audit files for new date");
        closeWriters();
        initializeAuditFiles();
    }
    
    // ==================== Query Methods ====================
    
    /**
     * Get recent audit events
     */
    public List<AuditEvent> getRecentEvents(int limit) {
        List<AuditEvent> events = new ArrayList<>();
        Iterator<AuditEvent> iter = eventCache.descendingIterator();
        while (iter.hasNext() && events.size() < limit) {
            events.add(iter.next());
        }
        return events;
    }
    
    /**
     * Get events by category
     */
    public List<AuditEvent> getEventsByCategory(AuditCategory category, int limit) {
        List<AuditEvent> events = new ArrayList<>();
        for (AuditEvent event : eventCache) {
            if (event.category == category) {
                events.add(event);
                if (events.size() >= limit) break;
            }
        }
        return events;
    }
    
    /**
     * Get order lifecycle by ClOrdId
     */
    public OrderLifecycle getOrderLifecycle(String clOrdId) {
        return orderLifecycles.get(clOrdId);
    }
    
    /**
     * Get all active order lifecycles for a client
     */
    public List<OrderLifecycle> getClientOrderLifecycles(String clientId) {
        List<OrderLifecycle> result = new ArrayList<>();
        for (OrderLifecycle lifecycle : orderLifecycles.values()) {
            if (clientId.equals(lifecycle.clientId)) {
                result.add(lifecycle);
            }
        }
        return result;
    }
    
    /**
     * Get audit statistics
     */
    public AuditStatistics getStatistics() {
        return statistics;
    }
    
    // ==================== Data Classes ====================
    
    public enum AuditEventType {
        // Order events
        ORDER_RECEIVED,
        ORDER_VALIDATED,
        ORDER_ACKNOWLEDGED,
        ORDER_REJECTED,
        ORDER_PARTIAL_FILL,
        ORDER_FILLED,
        ORDER_CANCELLED,
        ORDER_REPLACED,
        ORDER_EXPIRED,
        CANCEL_REQUEST,
        
        // Trade events
        TRADE_EXECUTED,
        TRADE_BUSTED,
        TRADE_CORRECTED,
        
        // Message events
        FIX_MESSAGE_RECEIVED,
        FIX_MESSAGE_SENT,
        FIX_SESSION_CONNECT,
        FIX_SESSION_DISCONNECT,
        
        // System events
        SYSTEM_EVENT,
        CONFIG_CHANGE,
        SECURITY_EVENT
    }
    
    public enum AuditCategory {
        ORDER,
        TRADE,
        MESSAGE,
        SYSTEM
    }
    
    public static class AuditEvent {
        public long eventId;
        public LocalDateTime timestamp;
        public AuditEventType eventType;
        public AuditCategory category;
        
        // Order fields
        public String orderId;
        public String clOrdId;
        public String clientId;
        public String symbol;
        public String side;
        public String orderType;
        public Long quantity;
        public Double price;
        public String status;
        
        // Trade fields
        public String tradeId;
        public String execId;
        public String buyClientId;
        public String sellClientId;
        public String buyOrderId;
        public String sellOrderId;
        
        // Message fields
        public String direction;
        public String messageType;
        public String senderCompId;
        public String targetCompId;
        public long msgSeqNum;
        public String rawMessage;
        
        // System fields
        public String component;
        public String userId;
        
        // Common
        public String details;
    }
    
    public static class OrderLifecycle {
        public String orderId;
        public String orderRefNumber;
        public String clientId;
        public String symbol;
        public String side;
        public String orderType;
        public Long quantity;
        public Double price;
        public String status;
        
        public LocalDateTime receivedTime;
        public LocalDateTime validatedTime;
        public LocalDateTime acknowledgedTime;
        public LocalDateTime completedTime;
        
        public long filledQuantity;
        public String rejectionReason;
        public String cancellationReason;
        
        public List<AuditEvent> events = new ArrayList<>();
    }
    
    public static class AuditStatistics {
        public final AtomicLong ordersReceived = new AtomicLong(0);
        public final AtomicLong ordersAcknowledged = new AtomicLong(0);
        public final AtomicLong ordersRejected = new AtomicLong(0);
        public final AtomicLong ordersFilled = new AtomicLong(0);
        public final AtomicLong ordersCancelled = new AtomicLong(0);
        public final AtomicLong tradesExecuted = new AtomicLong(0);
        public final AtomicLong tradesBusted = new AtomicLong(0);
        public final AtomicLong messagesReceived = new AtomicLong(0);
        public final AtomicLong messagesSent = new AtomicLong(0);
        public final AtomicLong systemEvents = new AtomicLong(0);
        public final AtomicLong droppedEvents = new AtomicLong(0);
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("ordersReceived", ordersReceived.get());
            map.put("ordersAcknowledged", ordersAcknowledged.get());
            map.put("ordersRejected", ordersRejected.get());
            map.put("ordersFilled", ordersFilled.get());
            map.put("ordersCancelled", ordersCancelled.get());
            map.put("tradesExecuted", tradesExecuted.get());
            map.put("tradesBusted", tradesBusted.get());
            map.put("messagesReceived", messagesReceived.get());
            map.put("messagesSent", messagesSent.get());
            map.put("systemEvents", systemEvents.get());
            map.put("droppedEvents", droppedEvents.get());
            return map;
        }
    }
}
