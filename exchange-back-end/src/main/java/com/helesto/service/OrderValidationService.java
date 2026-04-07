package com.helesto.service;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.model.OrderEntity;

/**
 * G1-M3: Order Validation + Enrichment Service
 * - Implements validation pipeline (symbol exists, qty range, price ticks, TIF)
 * - Reference-data lookup hooks (security master/customer master)
 * - Generates Order Reference Number strategy (unique + sortable)
 */
@ApplicationScoped
public class OrderValidationService {

    private static final Logger LOG = LoggerFactory.getLogger(OrderValidationService.class);
    
    @Inject
    ReferenceDataService referenceDataService;
    
    // Order reference number generator - unique and sortable
    private static final AtomicLong orderSequence = new AtomicLong(System.currentTimeMillis());
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    
    // Validation constants
    private static final long MIN_QUANTITY = 1L;
    private static final long MAX_QUANTITY = 10_000_000L;
    private static final double MIN_PRICE = 0.01;
    private static final double MAX_PRICE = 100_000.00;
    private static final double PRICE_TICK_SIZE = 0.01;
    
    private static final Set<String> VALID_SIDES = Set.of("1", "2"); // 1=Buy, 2=Sell
    private static final Set<String> VALID_ORDER_TYPES = Set.of("LIMIT", "MARKET", "STOP", "STOP_LIMIT");
    private static final Set<String> VALID_TIF = Set.of("DAY", "GTC", "IOC", "FOK", "GTD");
    
    /**
     * Validate an order through the complete validation pipeline
     * @return ValidationResult containing success/failure and error messages
     */
    public ValidationResult validateOrder(OrderEntity order) {
        LOG.debug("Validating order: clOrdId={}", order.getClOrdId());
        
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        
        // 1. Validate mandatory fields
        if (order.getClOrdId() == null || order.getClOrdId().isEmpty()) {
            errors.add("ClOrdId is required");
        }
        if (order.getSymbol() == null || order.getSymbol().isEmpty()) {
            errors.add("Symbol is required");
        }
        if (order.getSide() == null || order.getSide().isEmpty()) {
            errors.add("Side is required");
        }
        if (order.getQuantity() == null) {
            errors.add("Quantity is required");
        }
        if (order.getOrderType() == null || order.getOrderType().isEmpty()) {
            errors.add("OrderType is required");
        }
        
        // If mandatory fields are missing, return early
        if (!errors.isEmpty()) {
            return new ValidationResult(false, errors, warnings, "MANDATORY_FIELD_MISSING");
        }
        
        // 2. Validate Side (FIX tag 54)
        if (!VALID_SIDES.contains(order.getSide())) {
            errors.add("Invalid side: " + order.getSide() + ". Must be 1 (Buy) or 2 (Sell)");
        }
        
        // 3. Validate Order Type
        if (!VALID_ORDER_TYPES.contains(order.getOrderType().toUpperCase())) {
            errors.add("Invalid order type: " + order.getOrderType());
        }
        
        // 4. Validate Time In Force
        if (order.getTimeInForce() != null && !order.getTimeInForce().isEmpty()) {
            if (!VALID_TIF.contains(order.getTimeInForce().toUpperCase())) {
                errors.add("Invalid time in force: " + order.getTimeInForce());
            }
        }
        
        // 5. Validate Quantity Range
        if (order.getQuantity() < MIN_QUANTITY) {
            errors.add("Quantity must be at least " + MIN_QUANTITY);
        }
        if (order.getQuantity() > MAX_QUANTITY) {
            errors.add("Quantity cannot exceed " + MAX_QUANTITY);
        }
        
        // 6. Validate Price (for limit orders)
        if ("LIMIT".equalsIgnoreCase(order.getOrderType()) || 
            "STOP_LIMIT".equalsIgnoreCase(order.getOrderType())) {
            if (order.getPrice() == null) {
                errors.add("Price is required for LIMIT orders");
            } else {
                if (order.getPrice() < MIN_PRICE) {
                    errors.add("Price must be at least " + MIN_PRICE);
                }
                if (order.getPrice() > MAX_PRICE) {
                    errors.add("Price cannot exceed " + MAX_PRICE);
                }
                // Validate price tick size
                double remainder = order.getPrice() % PRICE_TICK_SIZE;
                if (remainder > 0.001) { // Allow small floating point error
                    warnings.add("Price adjusted to nearest tick: " + PRICE_TICK_SIZE);
                    order.setPrice(Math.round(order.getPrice() / PRICE_TICK_SIZE) * PRICE_TICK_SIZE);
                }
            }
        }
        
        // 7. Validate Symbol exists in Security Master
        if (referenceDataService != null && order.getSymbol() != null) {
            if (!referenceDataService.isValidSymbol(order.getSymbol())) {
                errors.add("Unknown symbol: " + order.getSymbol());
            }
        }
        
        // 8. Validate Client ID if provided
        if (order.getClientId() != null && !order.getClientId().isEmpty()) {
            if (referenceDataService != null && !referenceDataService.isValidClient(order.getClientId())) {
                warnings.add("Unknown client ID: " + order.getClientId());
            }
        }
        
        boolean isValid = errors.isEmpty();
        String rejectReason = isValid ? null : determineRejectReason(errors);
        
        LOG.info("Order validation result: clOrdId={}, valid={}, errors={}", 
                order.getClOrdId(), isValid, errors);
        
        return new ValidationResult(isValid, errors, warnings, rejectReason);
    }
    
    /**
     * Enrich an order with additional data
     * - Generate order reference number
     * - Set timestamps
     * - Initialize status fields
     */
    public void enrichOrder(OrderEntity order) {
        // Generate unique, sortable order reference number
        if (order.getOrderRefNumber() == null || order.getOrderRefNumber().isEmpty()) {
            order.setOrderRefNumber(generateOrderRefNumber());
        }
        
        // Set timestamps
        LocalDateTime now = LocalDateTime.now();
        if (order.getCreatedAt() == null) {
            order.setCreatedAt(now);
        }
        order.setUpdatedAt(now);
        
        // Initialize status fields
        if (order.getStatus() == null || order.getStatus().isEmpty()) {
            order.setStatus("NEW");
        }
        if (order.getFilledQty() == null) {
            order.setFilledQty(0L);
        }
        if (order.getLeavesQty() == null) {
            order.setLeavesQty(order.getQuantity());
        }
        if (order.getAvgPrice() == null) {
            order.setAvgPrice(0.0);
        }
        
        // Default TIF
        if (order.getTimeInForce() == null || order.getTimeInForce().isEmpty()) {
            order.setTimeInForce("DAY");
        }
        
        LOG.debug("Enriched order: clOrdId={}, orderRefNumber={}", 
                order.getClOrdId(), order.getOrderRefNumber());
    }
    
    /**
     * Generate unique, sortable order reference number
     * Format: YYYYMMDD-HHMMSS-SEQUENCE
     */
    public String generateOrderRefNumber() {
        LocalDateTime now = LocalDateTime.now();
        String datePart = now.format(DATE_FORMAT);
        String timePart = String.format("%02d%02d%02d", now.getHour(), now.getMinute(), now.getSecond());
        long sequence = orderSequence.incrementAndGet() % 1000000;
        return String.format("%s-%s-%06d", datePart, timePart, sequence);
    }
    
    private String determineRejectReason(List<String> errors) {
        for (String error : errors) {
            if (error.contains("required")) return "MANDATORY_FIELD_MISSING";
            if (error.contains("Invalid side")) return "INVALID_SIDE";
            if (error.contains("Invalid order type")) return "INVALID_ORDER_TYPE";
            if (error.contains("Quantity")) return "INVALID_QUANTITY";
            if (error.contains("Price")) return "INVALID_PRICE";
            if (error.contains("Unknown symbol")) return "UNKNOWN_SYMBOL";
        }
        return "VALIDATION_ERROR";
    }
    
    /**
     * Result of order validation
     */
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;
        private final List<String> warnings;
        private final String rejectReason;
        
        public ValidationResult(boolean valid, List<String> errors, List<String> warnings, String rejectReason) {
            this.valid = valid;
            this.errors = errors != null ? errors : Collections.emptyList();
            this.warnings = warnings != null ? warnings : Collections.emptyList();
            this.rejectReason = rejectReason;
        }
        
        public boolean isValid() { return valid; }
        public List<String> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }
        public String getRejectReason() { return rejectReason; }
        
        @Override
        public String toString() {
            return String.format("ValidationResult{valid=%s, errors=%s, warnings=%s, rejectReason='%s'}",
                    valid, errors, warnings, rejectReason);
        }
    }
}
