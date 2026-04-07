package com.helesto.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.transaction.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.dao.OrderDao;
import com.helesto.model.OrderEntity;
import com.helesto.socket.WebSocketAggregator;

/**
 * G4-M3: Order Cancel Service with Audit Trail
 * - Order state management (pending, accepted, rejected, canceled)
 * - Cancel request validation and processing
 * - Complete audit trail for compliance
 */
@ApplicationScoped
public class OrderCancelService {

    private static final Logger LOG = LoggerFactory.getLogger(OrderCancelService.class);
    
    @Inject
    OrderDao orderDao;
    
    @Inject
    EntityManager entityManager;
    
    @Inject
    OrderBookManager orderBookManager;
    
    @Inject
    WebSocketAggregator webSocketAggregator;

    @Inject
    OrderCacheService orderCacheService;

    @Inject
    TelemetryService telemetryService;
    
    // Audit trail storage (in production, persist to database)
    private final List<AuditEntry> auditTrail = new CopyOnWriteArrayList<>();
    
    // Pending cancel requests
    private final Map<String, CancelRequest> pendingCancels = new ConcurrentHashMap<>();
    
    // Order state transitions
    private static final Map<String, Set<String>> VALID_TRANSITIONS = Map.of(
        "NEW", Set.of("PARTIALLY_FILLED", "FILLED", "CANCELED", "REJECTED", "PENDING_CANCEL"),
        "PENDING_NEW", Set.of("NEW", "REJECTED"),
        "PARTIALLY_FILLED", Set.of("FILLED", "CANCELED", "PENDING_CANCEL"),
        "PENDING_CANCEL", Set.of("CANCELED", "FILLED", "PARTIALLY_FILLED"),
        "FILLED", Set.of(), // Terminal state
        "CANCELED", Set.of(), // Terminal state
        "REJECTED", Set.of() // Terminal state
    );
    
    /**
     * Request to cancel an order
     */
    @Transactional
    public CancelResult requestCancel(String orderRefNumber, String clientId, String reason) {
        LOG.info("Cancel request received: order={}, client={}, reason={}", orderRefNumber, clientId, reason);
        
        // Create audit entry
        AuditEntry audit = createAuditEntry("CANCEL_REQUEST", orderRefNumber, clientId, reason);
        
        // Find the order
        OrderEntity order = orderDao.findByOrderRefNumber(orderRefNumber);
        if (order == null) {
            audit.status = "REJECTED";
            audit.details = "Order not found";
            auditTrail.add(audit);
            return new CancelResult(false, "Order not found: " + orderRefNumber);
        }

        // Validate client ownership. If legacy/migrated orders are missing client attribution,
        // bind the first explicit caller clientId to avoid false mismatches.
        String normalizedRequestClientId = normalizeClientId(clientId);
        String normalizedOrderClientId = normalizeClientId(order.getClientId());

        if (normalizedRequestClientId != null && normalizedOrderClientId == null) {
            order.setClientId(normalizedRequestClientId);
            orderDao.update(order);
            orderCacheService.addToCache(order);
            normalizedOrderClientId = normalizedRequestClientId;
            LOG.info("Backfilled missing clientId on order {} with {} during cancel request", orderRefNumber, normalizedRequestClientId);
        }

        if (normalizedRequestClientId != null
                && normalizedOrderClientId != null
                && !normalizedRequestClientId.equalsIgnoreCase(normalizedOrderClientId)) {
            audit.status = "REJECTED";
            audit.details = "Client ID mismatch";
            auditTrail.add(audit);
            return new CancelResult(false, "Unauthorized: client ID mismatch");
        }
        
        // Check if order can be canceled
        String currentStatus = order.getStatus();
        if (!canCancel(currentStatus)) {
            audit.status = "REJECTED";
            audit.details = "Order in non-cancelable state: " + currentStatus;
            auditTrail.add(audit);
            return new CancelResult(false, "Order cannot be canceled in state: " + currentStatus);
        }
        
        // Create cancel request
        CancelRequest cancelRequest = new CancelRequest();
        cancelRequest.orderRefNumber = orderRefNumber;
        cancelRequest.origClOrdId = order.getClOrdId();
        cancelRequest.clientId = normalizedOrderClientId != null ? normalizedOrderClientId : normalizedRequestClientId;
        cancelRequest.reason = reason;
        cancelRequest.requestTime = System.currentTimeMillis();
        cancelRequest.orderId = order.getId();
        
        // Set order to pending cancel
        String previousStatus = order.getStatus();
        order.setStatus("PENDING_CANCEL");
        orderDao.update(order);
        orderCacheService.addToCache(order);
        
        pendingCancels.put(orderRefNumber, cancelRequest);
        
        audit.status = "PENDING";
        audit.details = String.format("Status changed: %s -> PENDING_CANCEL", previousStatus);
        auditTrail.add(audit);
        
        // Process the cancel
        return processCancel(orderRefNumber, cancelRequest);
    }
    
    /**
     * Process and execute the cancel
     */
    @Transactional
    public CancelResult processCancel(String orderRefNumber, CancelRequest cancelRequest) {
        LOG.info("Processing cancel for order: {}", orderRefNumber);
        
        AuditEntry audit = createAuditEntry("CANCEL_PROCESS", orderRefNumber, cancelRequest.clientId, null);
        
        OrderEntity order = orderDao.findByOrderRefNumber(orderRefNumber);
        if (order == null) {
            audit.status = "FAILED";
            audit.details = "Order not found during processing";
            auditTrail.add(audit);
            return new CancelResult(false, "Order not found");
        }
        
        // Remove from order book if present
        boolean removedFromBook = orderBookManager.removeOrder(order.getSymbol(), orderRefNumber);
        
        // Calculate leaves quantity that was canceled
        long canceledQty = order.getLeavesQty() != null ? order.getLeavesQty() : 
                          order.getQuantity() - (order.getFilledQty() != null ? order.getFilledQty() : 0);
        
        // Update order status
        String previousStatus = order.getStatus();
        order.setStatus("CANCELED");
        order.setLeavesQty(0L);
        orderDao.update(order);
        orderCacheService.addToCache(order);
        telemetryService.recordOrderCancelled();
        
        // Remove from pending
        pendingCancels.remove(orderRefNumber);
        
        // Complete audit
        audit.status = "COMPLETED";
        audit.details = String.format("Canceled %d shares. Removed from book: %s. Previous: %s", 
                canceledQty, removedFromBook, previousStatus);
        auditTrail.add(audit);
        
        // Notify via WebSocket
        try {
            webSocketAggregator.broadcastOrderUpdate(order);
        } catch (Exception e) {
            LOG.warn("Could not broadcast cancel update: {}", e.getMessage());
        }
        
        LOG.info("Order canceled successfully: {} (canceled qty: {})", orderRefNumber, canceledQty);
        
        CancelResult result = new CancelResult(true, "Order canceled successfully");
        result.canceledQuantity = canceledQty;
        result.filledQuantity = order.getFilledQty() != null ? order.getFilledQty() : 0;
        return result;
    }
    
    /**
     * Handle cancel reject from exchange
     */
    @Transactional
    public void handleCancelReject(String orderRefNumber, String rejectReason) {
        LOG.info("Cancel rejected for order {}: {}", orderRefNumber, rejectReason);
        
        AuditEntry audit = createAuditEntry("CANCEL_REJECT", orderRefNumber, null, rejectReason);
        
        OrderEntity order = orderDao.findByOrderRefNumber(orderRefNumber);
        if (order != null && "PENDING_CANCEL".equals(order.getStatus())) {
            // Revert to previous state (assumes it was NEW or PARTIALLY_FILLED)
            long filledQty = order.getFilledQty() != null ? order.getFilledQty() : 0L;
            String newStatus = filledQty > 0 ? "PARTIALLY_FILLED" : "NEW";
            order.setStatus(newStatus);
            orderDao.update(order);
            orderCacheService.addToCache(order);
            
            audit.details = String.format("Reverted status to %s. Reason: %s", newStatus, rejectReason);
        }
        
        audit.status = "REJECTED";
        auditTrail.add(audit);
        
        pendingCancels.remove(orderRefNumber);
    }
    
    /**
     * Get audit trail for an order
     */
    public List<AuditEntry> getOrderAuditTrail(String orderRefNumber) {
        List<AuditEntry> orderAudit = new ArrayList<>();
        for (AuditEntry entry : auditTrail) {
            if (orderRefNumber.equals(entry.orderRefNumber)) {
                orderAudit.add(entry);
            }
        }
        return orderAudit;
    }
    
    /**
     * Get full audit trail (for compliance)
     */
    public List<AuditEntry> getFullAuditTrail() {
        return new ArrayList<>(auditTrail);
    }
    
    /**
     * Get audit trail for a client
     */
    public List<AuditEntry> getClientAuditTrail(String clientId) {
        List<AuditEntry> clientAudit = new ArrayList<>();
        for (AuditEntry entry : auditTrail) {
            if (clientId.equals(entry.clientId)) {
                clientAudit.add(entry);
            }
        }
        return clientAudit;
    }
    
    /**
     * Get pending cancel requests
     */
    public Collection<CancelRequest> getPendingCancels() {
        return Collections.unmodifiableCollection(pendingCancels.values());
    }
    
    /**
     * Check if order can be canceled
     */
    public boolean canCancel(String currentStatus) {
        if (currentStatus == null) return false;
        Set<String> validTargets = VALID_TRANSITIONS.get(currentStatus);
        return validTargets != null && (validTargets.contains("CANCELED") || validTargets.contains("PENDING_CANCEL"));
    }
    
    /**
     * Check if state transition is valid
     */
    public boolean isValidTransition(String fromState, String toState) {
        Set<String> validTargets = VALID_TRANSITIONS.get(fromState);
        return validTargets != null && validTargets.contains(toState);
    }
    
    private AuditEntry createAuditEntry(String action, String orderRefNumber, String clientId, String details) {
        AuditEntry entry = new AuditEntry();
        entry.timestamp = LocalDateTime.now();
        entry.action = action;
        entry.orderRefNumber = orderRefNumber;
        entry.clientId = clientId;
        entry.details = details;
        entry.status = "PENDING";
        return entry;
    }

    private String normalizeClientId(String clientId) {
        if (clientId == null) {
            return null;
        }
        String normalized = clientId.trim();
        return normalized.isEmpty() ? null : normalized;
    }
    
    // ================== Inner Classes ==================
    
    public static class CancelRequest {
        public String orderRefNumber;
        public String origClOrdId;
        public String clientId;
        public String reason;
        public long requestTime;
        public Long orderId;
    }
    
    public static class CancelResult {
        public boolean success;
        public String message;
        public long canceledQuantity;
        public long filledQuantity;
        
        public CancelResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
    
    public static class AuditEntry {
        public LocalDateTime timestamp;
        public String action;
        public String orderRefNumber;
        public String clientId;
        public String status;
        public String details;
        
        @Override
        public String toString() {
            return String.format("[%s] %s %s: %s - %s", 
                    timestamp, action, orderRefNumber, status, details);
        }
    }
}
