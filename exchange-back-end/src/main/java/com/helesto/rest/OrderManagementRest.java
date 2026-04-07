package com.helesto.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.helesto.dao.OrderDao;
import com.helesto.model.OrderEntity;
import com.helesto.service.MatchingEngine;
import com.helesto.service.OrderBookManager;
import com.helesto.service.OrderCacheService;
import com.helesto.service.OrderCancelService;
import com.helesto.service.OrderFlowOrchestrator;
import com.helesto.service.OrderValidationService;
import com.helesto.service.PerformanceMetricsService;
import com.helesto.service.TradeService;

/**
 * REST endpoints for Order Management and Cancel operations
 */
@Path("/api/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderManagementRest {

    @Inject
    OrderDao orderDao;
    
    @Inject
    OrderCancelService cancelService;
    
    @Inject
    OrderValidationService validationService;
    
    @Inject
    MatchingEngine matchingEngine;
    
    @Inject
    OrderBookManager orderBookManager;
    
    @Inject
    TradeService tradeService;
    
    @Inject
    OrderFlowOrchestrator orchestrator;

    @Inject
    OrderCacheService orderCacheService;

    @Inject
    PerformanceMetricsService performanceMetricsService;
    
    // ================== Order Query Endpoints ==================
    
    @GET
    public Response getAllOrders() {
        return Response.ok(orderDao.findAll()).build();
    }
    
    @POST
    public Response createOrder(OrderSubmitRequest request) {
        // Reuse submit logic
        return submitOrder(request);
    }
    
    @GET
    @Path("/{orderRefNumber}")
    public Response getOrder(@PathParam("orderRefNumber") String orderRefNumber) {
        OrderEntity order = orderCacheService.getByOrderRefNumber(orderRefNumber);
        if (order == null) {
            order = orderDao.findByOrderRefNumber(orderRefNumber);
            if (order != null) {
                orderCacheService.addToCache(order);
            }
        }
        if (order == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Order not found: " + orderRefNumber))
                    .build();
        }
        return Response.ok(order).build();
    }
    
    @GET
    @Path("/symbol/{symbol}")
    public Response getOrdersBySymbol(@PathParam("symbol") String symbol) {
        return Response.ok(orderCacheService.getBySymbol(symbol)).build();
    }
    
    @GET
    @Path("/status/{status}")
    public Response getOrdersByStatus(@PathParam("status") String status) {
        return Response.ok(orderCacheService.getByStatus(status)).build();
    }
    
    @GET
    @Path("/client/{clientId}")
    public Response getOrdersByClient(@PathParam("clientId") String clientId) {
        return Response.ok(orderCacheService.getByClient(clientId)).build();
    }
    
    // ================== Order Cancel Endpoints ==================
    
    @POST
    @Path("/{orderRefNumber}/cancel")
    public Response cancelOrder(
            @PathParam("orderRefNumber") String orderRefNumber,
            CancelOrderRequest request) {
        
        String clientId = request != null ? request.clientId : null;
        String reason = request != null ? request.reason : "User requested cancel";
        
        OrderCancelService.CancelResult result = cancelService.requestCancel(orderRefNumber, clientId, reason);
        
        if (result.success) {
            return Response.ok(Map.of(
                    "success", true,
                    "message", result.message,
                    "canceledQuantity", result.canceledQuantity,
                    "filledQuantity", result.filledQuantity
            )).build();
        } else {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "error", result.message))
                    .build();
        }
    }

    /**
     * Backward-compatible cancel endpoint used by legacy/front-end flows that call
     * DELETE /api/orders/{clOrdId}. Resolves the order by clOrdId and then routes
     * through the existing cancel service.
     */
    @DELETE
    @Path("/{clOrdId}")
    public Response cancelOrderByClOrdId(@PathParam("clOrdId") String clOrdId) {
        if (clOrdId == null || clOrdId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "error", "clOrdId is required"))
                    .build();
        }

        OrderEntity order = orderDao.findByClOrdId(clOrdId);
        if (order == null || order.getOrderRefNumber() == null || order.getOrderRefNumber().isBlank()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("success", false, "error", "Order not found for clOrdId: " + clOrdId))
                    .build();
        }

        String clientId = order.getClientId();
        String reason = "User requested cancel";
        OrderCancelService.CancelResult result = cancelService.requestCancel(order.getOrderRefNumber(), clientId, reason);

        if (result.success) {
            return Response.ok(Map.of(
                    "success", true,
                    "message", result.message,
                    "canceledQuantity", result.canceledQuantity,
                    "filledQuantity", result.filledQuantity,
                    "clOrdId", clOrdId,
                    "orderRefNumber", order.getOrderRefNumber()
            )).build();
        }

        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of(
                        "success", false,
                        "error", result.message,
                        "clOrdId", clOrdId,
                        "orderRefNumber", order.getOrderRefNumber()
                ))
                .build();
    }
    
    @GET
    @Path("/pending-cancels")
    public Response getPendingCancels() {
        return Response.ok(cancelService.getPendingCancels()).build();
    }
    
    // ================== Audit Trail Endpoints ==================
    
    @GET
    @Path("/audit")
    public Response getFullAuditTrail() {
        return Response.ok(cancelService.getFullAuditTrail()).build();
    }
    
    @GET
    @Path("/audit/order/{orderRefNumber}")
    public Response getOrderAuditTrail(@PathParam("orderRefNumber") String orderRefNumber) {
        return Response.ok(cancelService.getOrderAuditTrail(orderRefNumber)).build();
    }
    
    @GET
    @Path("/audit/client/{clientId}")
    public Response getClientAuditTrail(@PathParam("clientId") String clientId) {
        return Response.ok(cancelService.getClientAuditTrail(clientId)).build();
    }
    
    // ================== Order Submission (for testing) ==================
    
    @POST
    @Path("/submit")
    public Response submitOrder(OrderSubmitRequest request) {
        return processSingleOrder(request);
    }
    
    // ================== Orchestrated Order Submission (with full checks) ==================
    
    /**
     * Submit an order through the full orchestrator pipeline including:
     * - Market state checks
     * - Rate limiting
     * - Validation
     * - Circuit breaker checks  
     * - Risk management
     * - Audit trail
     * - Performance tracking
     */
    @POST
    @Path("/orchestrated")
    public Response submitOrchestratedOrder(OrderSubmitRequest request) {
        return processSingleOrder(request);
    }

    /**
     * Batch submit orders through orchestrator to improve throughput for high-volume clients.
     */
    @POST
    @Path("/orchestrated/batch")
    public Response submitOrchestratedBatch(BatchOrderSubmitRequest batchRequest) {
        if (batchRequest == null || batchRequest.orders == null || batchRequest.orders.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "orders array is required"))
                    .build();
        }

        boolean continueOnError = batchRequest.continueOnError == null || batchRequest.continueOnError;
        List<Map<String, Object>> results = new ArrayList<>();
        int accepted = 0;
        int rejected = 0;
        long startNanos = System.nanoTime();

        for (OrderSubmitRequest request : batchRequest.orders) {
            Response singleResponse = processSingleOrder(request);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) singleResponse.getEntity();

            Map<String, Object> item = new java.util.HashMap<>(payload);
            item.put("httpStatus", singleResponse.getStatus());
            results.add(item);

            if (singleResponse.getStatus() == Response.Status.CREATED.getStatusCode()) {
                accepted++;
            } else {
                rejected++;
                if (!continueOnError) {
                    break;
                }
            }
        }

        long totalNanos = System.nanoTime() - startNanos;
        int processed = accepted + rejected;
        double throughputOpsPerSecond = totalNanos > 0
                ? (processed * 1_000_000_000.0) / totalNanos
                : 0.0;

        performanceMetricsService.recordLatency("order.batch.total", totalNanos);

        return Response.ok(Map.of(
                "processedOrders", processed,
                "acceptedOrders", accepted,
                "rejectedOrders", rejected,
                "throughputOpsPerSecond", String.format("%.2f", throughputOpsPerSecond),
                "totalLatencyMs", String.format("%.3f", totalNanos / 1_000_000.0),
                "results", results
        )).build();
    }

    private Response processSingleOrder(OrderSubmitRequest request) {
        if (request.symbol == null || request.side == null || request.quantity <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid order: symbol, side, quantity required"))
                    .build();
        }
        
        OrderFlowOrchestrator.OrderRequest orchRequest = OrderFlowOrchestrator.OrderRequest
                .create(request.symbol, request.side, request.quantity, request.price)
                .withClientId(request.clientId != null ? request.clientId : "CLIENT001")
            .withOrderType(convertOrderType(request.orderType != null ? request.orderType : "LIMIT"))
            .withTimeInForce(convertTimeInForce(request.timeInForce != null ? request.timeInForce : "DAY"));
        
        if (request.clOrdId != null) {
            orchRequest.withClOrdId(request.clOrdId);
        } else {
            orchRequest.withClOrdId(UUID.randomUUID().toString());
        }
        
        OrderFlowOrchestrator.OrderResult result = orchestrator.processOrder(orchRequest);
        
        if (result.success) {
            return Response.status(Response.Status.CREATED)
                    .entity(result.toMap())
                    .build();
        } else {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(result.toMap())
                    .build();
        }
    }
    
    /**
     * Cancel an order through the orchestrated pipeline
     */
    @POST
    @Path("/orchestrated/{orderRefNumber}/cancel")
    public Response cancelOrchestratedOrder(
            @PathParam("orderRefNumber") String orderRefNumber,
            CancelOrderRequest request) {
        
        String clientId = request != null ? request.clientId : null;
        String reason = request != null ? request.reason : "User requested cancel";
        
        OrderFlowOrchestrator.CancelResult result = orchestrator.cancelOrder(orderRefNumber, clientId, reason);
        
        if (result.success) {
            return Response.ok(Map.of(
                    "success", true,
                    "message", result.message,
                    "canceledQuantity", result.canceledQuantity,
                    "filledQuantity", result.filledQuantity
            )).build();
        } else {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "error", result.message))
                    .build();
        }
    }
    
    // ================== Order Amend (Replace) Endpoint ==================

    /**
     * Amend (modify) an existing order - cancel original and submit replacement
     * Supports changing price, quantity, or both
     */
    @POST
    @Path("/{orderRefNumber}/amend")
    public Response amendOrder(
            @PathParam("orderRefNumber") String orderRefNumber,
            AmendOrderRequest request) {

        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Amend request body is required")).build();
        }

        if (request.newPrice < 0 || request.newQuantity < 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "newPrice and newQuantity must be non-negative")).build();
        }

        if (request.newPrice == 0 && request.newQuantity == 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Provide at least one field to amend: newPrice or newQuantity")).build();
        }

        // Find the original order
        OrderEntity original = orderDao.findByOrderRefNumber(orderRefNumber);
        if (original == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Order not found: " + orderRefNumber)).build();
        }

        // Can only amend NEW or PARTIALLY_FILLED orders
        String status = original.getStatus();
        if (!"NEW".equals(status) && !"PARTIALLY_FILLED".equals(status)
            && !"PARTIAL_FILL".equals(status) && !"PENDING_NEW".equals(status)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Cannot amend order in status: " + status)).build();
        }

        // Cancel original
        String reason = "Replaced by amendment";
        String clientId = request.clientId != null ? request.clientId : original.getClientId();
        OrderCancelService.CancelResult cancelResult = cancelService.requestCancel(orderRefNumber, clientId, reason);
        if (!cancelResult.success && !"ALREADY_CANCELED".equals(cancelResult.message)) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Failed to cancel original order: " + cancelResult.message)).build();
        }

        // Build replacement request
        OrderSubmitRequest replacement = new OrderSubmitRequest();
        replacement.symbol = original.getSymbol();
        replacement.side = original.getSide();
        replacement.quantity = request.newQuantity > 0 ? request.newQuantity : original.getQuantity().intValue();
        replacement.price = request.newPrice > 0 ? request.newPrice : original.getPrice();
        replacement.orderType = original.getOrderType();
        replacement.timeInForce = original.getTimeInForce();
        replacement.clientId = clientId;
        replacement.clOrdId = "AMD-" + orderRefNumber.substring(Math.max(0, orderRefNumber.length() - 8));

        // Submit replacement via orchestrator
        Response submitResponse = submitOrchestratedOrder(replacement);

        if (submitResponse.getStatus() == 201) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) submitResponse.getEntity();
            body.put("amendedFrom", orderRefNumber);
            body.put("priceChanged", request.newPrice > 0);
            body.put("quantityChanged", request.newQuantity > 0);
            return Response.ok(body).build();
        }
        return submitResponse;
    }

    /**
     * Bulk cancel - cancel all orders for a client or symbol
     */
    @POST
    @Path("/bulk-cancel")
    public Response bulkCancel(BulkCancelRequest request) {
        if (request == null) {
            request = new BulkCancelRequest();
        }

        String requestClientId = request.clientId != null && !request.clientId.isBlank()
            ? request.clientId
            : "CLIENT001";
        String reason = request.reason != null && !request.reason.isBlank()
            ? request.reason
            : "Bulk cancel";

        int canceledCount = 0;
        int failedCount = 0;
        java.util.List<String> errors = new java.util.ArrayList<>();

        java.util.List<com.helesto.model.OrderEntity> orders;
        if (request.symbol != null && !request.symbol.isEmpty()) {
            orders = orderDao.findBySymbol(request.symbol);
        } else if (request.clientId != null && !request.clientId.isEmpty()) {
            orders = orderDao.findByClientId(request.clientId);
        } else {
            // Default bulk cleanup should target all active states, not only NEW.
            java.util.LinkedHashMap<String, com.helesto.model.OrderEntity> byRef = new java.util.LinkedHashMap<>();
            for (com.helesto.model.OrderEntity o : orderDao.findByStatus("NEW")) {
                byRef.put(o.getOrderRefNumber(), o);
            }
            for (com.helesto.model.OrderEntity o : orderDao.findByStatus("PARTIALLY_FILLED")) {
                byRef.put(o.getOrderRefNumber(), o);
            }
            for (com.helesto.model.OrderEntity o : orderDao.findByStatus("PARTIAL_FILL")) {
                byRef.put(o.getOrderRefNumber(), o);
            }
            orders = new java.util.ArrayList<>(byRef.values());
        }

        for (com.helesto.model.OrderEntity o : orders) {
            if ("NEW".equals(o.getStatus()) || "PARTIALLY_FILLED".equals(o.getStatus())
                || "PARTIAL_FILL".equals(o.getStatus())) {
                String effectiveClientId = (o.getClientId() != null && !o.getClientId().isBlank())
                    ? o.getClientId()
                    : requestClientId;
                OrderCancelService.CancelResult r = cancelService.requestCancel(
                    o.getOrderRefNumber(), effectiveClientId, reason);
                if (r.success) canceledCount++;
                else { failedCount++; errors.add(o.getOrderRefNumber() + ": " + r.message); }
            }
        }

        // Keep in-memory books aligned with DB active state to remove stale ghost levels.
        orderBookManager.rebuildFromActiveOrders();

        return Response.ok(Map.of(
                "canceledCount", canceledCount,
                "failedCount", failedCount,
                "errors", errors
        )).build();
    }

    // ================== Helper Methods ==================
    
    private String convertOrderType(String type) {
        if (type == null) return "LIMIT";
        switch (type.toUpperCase()) {
            case "1": case "MARKET": return "MARKET";
            case "2": case "LIMIT": return "LIMIT";
            case "3": case "STOP": return "STOP";
            case "4": case "STOP_LIMIT": return "STOP_LIMIT";
            default: return type.toUpperCase();
        }
    }
    
    private String convertTimeInForce(String tif) {
        if (tif == null) return "DAY";
        switch (tif.toUpperCase()) {
            case "0": case "DAY": return "DAY";
            case "1": case "GTC": return "GTC";
            case "3": case "IOC": return "IOC";
            case "4": case "FOK": return "FOK";
            case "6": case "GTD": return "GTD";
            default: return tif.toUpperCase();
        }
    }
    
    // ================== Request Classes ==================
    
    public static class CancelOrderRequest {
        public String clientId;
        public String reason;
    }

    public static class AmendOrderRequest {
        public String clientId;
        public double newPrice;
        public int newQuantity;
        public String reason;
    }

    public static class BulkCancelRequest {
        public String clientId;
        public String symbol;
        public String reason;
    }
    
    public static class OrderSubmitRequest {
        public String symbol;
        public String side; // BUY, SELL, 1, 2
        public int quantity;
        public double price;
        public double stopPrice; // For STOP and STOP_LIMIT orders
        public String orderType; // 1=MARKET, 2=LIMIT, 3=STOP, 4=STOP_LIMIT
        public String timeInForce; // 0=DAY, 1=GTC, 3=IOC, 4=FOK
        public String clientId;
        public String clOrdId;
    }

    public static class BatchOrderSubmitRequest {
        public List<OrderSubmitRequest> orders;
        public Boolean continueOnError;
    }
}
