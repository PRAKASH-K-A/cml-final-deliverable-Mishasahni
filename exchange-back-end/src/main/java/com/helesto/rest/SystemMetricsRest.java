package com.helesto.rest;

import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.service.AuditTrailService;
import com.helesto.service.CircuitBreakerService;
import com.helesto.service.MarketStateManager;
import com.helesto.service.OrderRateLimiter;
import com.helesto.service.PerformanceMetricsService;
import com.helesto.service.PositionTrackingService;
import com.helesto.service.RiskManagementService;
import com.helesto.service.TelemetryService;

/**
 * System Metrics and Monitoring REST API
 * Provides comprehensive access to:
 * - Risk management metrics
 * - Circuit breaker status
 * - Position tracking data
 * - Rate limiting status
 * - Market state information
 * - Audit trail data
 * - Performance metrics
 */
@Path("/api/system")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SystemMetricsRest {

    private static final Logger LOG = LoggerFactory.getLogger(SystemMetricsRest.class);
    
    @Inject
    RiskManagementService riskManagementService;
    
    @Inject
    CircuitBreakerService circuitBreakerService;
    
    @Inject
    PositionTrackingService positionTrackingService;
    
    @Inject
    OrderRateLimiter orderRateLimiter;
    
    @Inject
    MarketStateManager marketStateManager;
    
    @Inject
    AuditTrailService auditTrailService;
    
    @Inject
    PerformanceMetricsService performanceMetricsService;
    
    @Inject
    TelemetryService telemetryService;
    
    // ==================== Dashboard ====================

    @GET
    @Path("/dashboard")
    @Operation(summary = "Aggregated dashboard metrics", description = "Returns health, risk, performance and position data in a single call")
    public Response getDashboard() {
        Map<String, Object> dashboard = new HashMap<>();

        // Health
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("tradingAllowed", marketStateManager.isTradingAllowed());
        health.put("marketState", marketStateManager.getCurrentState().toString());
        health.put("tradingHalted", riskManagementService.isTradingHalted());
        health.put("timestamp", System.currentTimeMillis());
        dashboard.put("health", health);

        // Risk summary
        try { dashboard.put("risk", riskManagementService.getRiskMetrics()); }
        catch (Exception e) { dashboard.put("risk", Map.of("error", e.getMessage())); }

        // Performance metrics
        try { dashboard.put("performance", performanceMetricsService.getSummary()); }
        catch (Exception e) { dashboard.put("performance", Map.of("error", e.getMessage())); }

        // Circuit breaker status
        try { dashboard.put("circuitBreaker", circuitBreakerService.getStatus()); }
        catch (Exception e) { dashboard.put("circuitBreaker", Map.of("error", e.getMessage())); }

        // Position summary
        try { dashboard.put("positions", positionTrackingService.getFirmPortfolioSummary()); }
        catch (Exception e) { dashboard.put("positions", Map.of("error", e.getMessage())); }

        // Telemetry
        try { dashboard.put("telemetry", telemetryService.getAllMetrics()); }
        catch (Exception e) { dashboard.put("telemetry", Map.of("error", e.getMessage())); }

        dashboard.put("generatedAt", System.currentTimeMillis());
        return Response.ok(dashboard).build();
    }

    // ==================== Health & Status ====================
    
    @GET
    @Path("/health")
    @Operation(summary = "System health check", description = "Get overall system health status")
    @APIResponse(responseCode = "200", description = "Health status")
    public Response getHealth() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("tradingAllowed", marketStateManager.isTradingAllowed());
        health.put("marketState", marketStateManager.getCurrentState().toString());
        health.put("tradingHalted", riskManagementService.isTradingHalted());
        health.put("timestamp", System.currentTimeMillis());
        return Response.ok(health).build();
    }
    
    @GET
    @Path("/status")
    @Operation(summary = "Comprehensive system status", description = "Get detailed system status including all components")
    @APIResponse(responseCode = "200", description = "System status")
    public Response getSystemStatus() {
        Map<String, Object> status = new HashMap<>();
        
        // Market state
        status.put("marketState", marketStateManager.getSessionInfo());
        
        // Risk status
        Map<String, Object> riskStatus = new HashMap<>();
        riskStatus.put("tradingHalted", riskManagementService.isTradingHalted());
        riskStatus.put("haltReason", riskManagementService.getHaltReason());
        status.put("risk", riskStatus);
        
        // Circuit breaker status
        status.put("circuitBreakers", circuitBreakerService.getStatus());
        
        // Performance summary
        status.put("performance", performanceMetricsService.getSummary().toMap());
        
        // Audit stats
        status.put("auditStats", auditTrailService.getStatistics().toMap());
        
        return Response.ok(status).build();
    }
    
    // ==================== Risk Management ====================
    
    @GET
    @Path("/risk")
    @Operation(summary = "Get risk metrics", description = "Get comprehensive risk management metrics")
    @APIResponse(responseCode = "200", description = "Risk metrics")
    public Response getRiskMetrics() {
        return Response.ok(riskManagementService.getRiskMetrics()).build();
    }
    
    @GET
    @Path("/risk/client/{clientId}")
    @Operation(summary = "Get client risk limits", description = "Get risk limits for a specific client")
    public Response getClientRiskLimits(@PathParam("clientId") String clientId) {
        RiskManagementService.ClientRiskLimits limits = riskManagementService.getClientLimits(clientId);
        if (limits == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Client not found"))
                    .build();
        }
        return Response.ok(limits).build();
    }
    
    @POST
    @Path("/risk/halt")
    @Operation(summary = "Halt trading", description = "Emergency halt all trading (kill switch)")
    public Response haltTrading(@QueryParam("reason") String reason) {
        LOG.warn("Trading halt requested: {}", reason);
        riskManagementService.haltTrading(reason != null ? reason : "Manual halt via API");
        return Response.ok(Map.of("success", true, "message", "Trading halted")).build();
    }
    
    @POST
    @Path("/risk/resume")
    @Operation(summary = "Resume trading", description = "Resume trading after halt")
    public Response resumeTrading() {
        LOG.info("Trading resume requested");
        riskManagementService.resumeTrading();
        return Response.ok(Map.of("success", true, "message", "Trading resumed")).build();
    }

    // ==================== Market State Control (testing / admin) ====================

    @POST
    @Path("/market/open")
    @Operation(summary = "Force market open", description = "Force market state to OPEN / CONTINUOUS for testing")
    public Response forceMarketOpen(@QueryParam("reason") String reason) {
        String r = (reason != null && !reason.isEmpty()) ? reason : "Manual open via API";
        LOG.warn("Force market OPEN requested: {}", r);
        marketStateManager.forceState(
                MarketStateManager.MarketState.OPEN,
                MarketStateManager.TradingPhase.CONTINUOUS,
                r);
        return Response.ok(Map.of("success", true, "marketState", "OPEN", "tradingPhase", "CONTINUOUS")).build();
    }

    @POST
    @Path("/market/close")
    @Operation(summary = "Force market close", description = "Force market state to CLOSED for testing")
    public Response forceMarketClose(@QueryParam("reason") String reason) {
        String r = (reason != null && !reason.isEmpty()) ? reason : "Manual close via API";
        LOG.warn("Force market CLOSE requested: {}", r);
        marketStateManager.forceState(
                MarketStateManager.MarketState.CLOSED,
                MarketStateManager.TradingPhase.CLOSED,
                r);
        return Response.ok(Map.of("success", true, "marketState", "CLOSED", "tradingPhase", "CLOSED")).build();
    }

    @POST
    @Path("/risk/client/{clientId}/disable")
    @Operation(summary = "Disable client trading", description = "Disable trading for a specific client")
    public Response disableClient(@PathParam("clientId") String clientId) {
        riskManagementService.disableClient(clientId);
        return Response.ok(Map.of("success", true, "clientId", clientId, "enabled", false)).build();
    }
    
    @POST
    @Path("/risk/client/{clientId}/enable")
    @Operation(summary = "Enable client trading", description = "Enable trading for a specific client")
    public Response enableClient(@PathParam("clientId") String clientId) {
        riskManagementService.enableClient(clientId);
        return Response.ok(Map.of("success", true, "clientId", clientId, "enabled", true)).build();
    }
    
    // ==================== Circuit Breakers ====================
    
    @GET
    @Path("/circuit-breakers")
    @Operation(summary = "Get circuit breaker status", description = "Get status of all circuit breakers")
    public Response getCircuitBreakerStatus() {
        return Response.ok(circuitBreakerService.getStatus()).build();
    }
    
    @GET
    @Path("/circuit-breakers/{symbol}/luld")
    @Operation(summary = "Get LULD bands", description = "Get LULD price bands for a symbol")
    public Response getLULDBands(@PathParam("symbol") String symbol) {
        CircuitBreakerService.LULDBands bands = circuitBreakerService.getLULDBands(symbol);
        if (bands == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No LULD bands for symbol"))
                    .build();
        }
        return Response.ok(bands).build();
    }
    
    @POST
    @Path("/circuit-breakers/{symbol}/halt")
    @Operation(summary = "Trigger news halt", description = "Manually trigger a news-based trading halt")
    public Response triggerNewsHalt(@PathParam("symbol") String symbol, @QueryParam("reason") String reason) {
        LOG.warn("Manual news halt for {}: {}", symbol, reason);
        circuitBreakerService.triggerNewsHalt(symbol, reason != null ? reason : "Manual halt");
        return Response.ok(Map.of("success", true, "symbol", symbol, "halted", true)).build();
    }
    
    @POST
    @Path("/circuit-breakers/{symbol}/resume")
    @Operation(summary = "Resume symbol trading", description = "Resume trading for a halted symbol")
    public Response resumeSymbolTrading(@PathParam("symbol") String symbol) {
        circuitBreakerService.resumeTrading(symbol);
        return Response.ok(Map.of("success", true, "symbol", symbol, "halted", false)).build();
    }
    
        @POST
        @Path("/circuit-breakers/market/resume")
        @Operation(summary = "Resume market-wide trading", description = "Reset market-wide circuit breaker state")
        public Response resumeMarketWideTrading() {
            circuitBreakerService.resumeMarketWideTrading();
            return Response.ok(Map.of("success", true, "marketHalted", false)).build();
        }
    
    // ==================== Positions ====================
    
    @GET
    @Path("/positions")
    @Operation(summary = "Get firm portfolio summary", description = "Get firm-wide portfolio summary")
    public Response getFirmPortfolio() {
        return Response.ok(positionTrackingService.getFirmPortfolioSummary()).build();
    }
    
    @GET
    @Path("/positions/client/{clientId}")
    @Operation(summary = "Get client positions", description = "Get all positions for a client")
    public Response getClientPositions(@PathParam("clientId") String clientId) {
        return Response.ok(positionTrackingService.getClientPositions(clientId)).build();
    }
    
    @GET
    @Path("/positions/client/{clientId}/summary")
    @Operation(summary = "Get client portfolio summary", description = "Get portfolio summary for a client")
    public Response getClientPortfolioSummary(@PathParam("clientId") String clientId) {
        return Response.ok(positionTrackingService.getPortfolioSummary(clientId)).build();
    }
    
    @GET
    @Path("/positions/symbol/{symbol}")
    @Operation(summary = "Get positions by symbol", description = "Get all positions for a symbol across clients")
    public Response getPositionsBySymbol(@PathParam("symbol") String symbol) {
        return Response.ok(positionTrackingService.getPositionsBySymbol(symbol)).build();
    }
    
    @GET
    @Path("/pnl/firm")
    @Operation(summary = "Get firm P&L", description = "Get firm-wide P&L summary")
    public Response getFirmPnL() {
        return Response.ok(positionTrackingService.getFirmPnL()).build();
    }
    
    @GET
    @Path("/pnl/client/{clientId}")
    @Operation(summary = "Get client P&L", description = "Get P&L for a specific client")
    public Response getClientPnL(@PathParam("clientId") String clientId) {
        PositionTrackingService.ClientPnL pnl = positionTrackingService.getClientPnL(clientId);
        if (pnl == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Client not found"))
                    .build();
        }
        return Response.ok(pnl).build();
    }
    
    // ==================== Rate Limiting ====================
    
    @GET
    @Path("/rate-limits")
    @Operation(summary = "Get rate limiter metrics", description = "Get rate limiting metrics and status")
    public Response getRateLimitMetrics() {
        return Response.ok(orderRateLimiter.getMetrics()).build();
    }
    
    @GET
    @Path("/rate-limits/client/{clientId}")
    @Operation(summary = "Get client rate limit status", description = "Get rate limit status for a client")
    public Response getClientRateLimitStatus(@PathParam("clientId") String clientId) {
        return Response.ok(orderRateLimiter.getClientStatus(clientId)).build();
    }
    
    @POST
    @Path("/rate-limits/client/{clientId}/clear-violations")
    @Operation(summary = "Clear client violations", description = "Clear rate limit violations for a client")
    public Response clearClientViolations(@PathParam("clientId") String clientId) {
        orderRateLimiter.clearViolations(clientId);
        return Response.ok(Map.of("success", true, "clientId", clientId)).build();
    }
    
    // ==================== Market State ====================
    
    @GET
    @Path("/market-state")
    @Operation(summary = "Get market state", description = "Get current market state and session info")
    public Response getMarketState() {
        return Response.ok(marketStateManager.getSessionInfo()).build();
    }
    
    @GET
    @Path("/market-state/holidays")
    @Operation(summary = "Get holidays", description = "Get market holiday calendar")
    public Response getHolidays() {
        return Response.ok(Map.of(
                "holidays", marketStateManager.getHolidays(),
                "earlyCloses", marketStateManager.getEarlyCloses()
        )).build();
    }
    
    @GET
    @Path("/market-state/history")
    @Operation(summary = "Get state history", description = "Get recent market state changes")
    public Response getStateHistory(@QueryParam("limit") @DefaultValue("50") int limit) {
        return Response.ok(marketStateManager.getRecentStateChanges(limit)).build();
    }
    
    @POST
    @Path("/market-state/halt")
    @Operation(summary = "Emergency market halt", description = "Trigger emergency trading halt")
    public Response emergencyHalt(@QueryParam("reason") String reason) {
        LOG.error("Emergency halt triggered: {}", reason);
        marketStateManager.haltTrading(reason != null ? reason : "Emergency halt via API");
        return Response.ok(Map.of("success", true, "state", "HALTED")).build();
    }
    
    @POST
    @Path("/market-state/resume")
    @Operation(summary = "Resume after halt", description = "Resume trading after emergency halt")
    public Response resumeAfterHalt() {
        marketStateManager.resumeTrading();
        return Response.ok(Map.of("success", true, "state", marketStateManager.getCurrentState().toString())).build();
    }
    
    // ==================== Audit Trail ====================
    
    @GET
    @Path("/audit")
    @Operation(summary = "Get recent audit events", description = "Get recent audit events")
    public Response getAuditEvents(@QueryParam("limit") @DefaultValue("100") int limit) {
        return Response.ok(auditTrailService.getRecentEvents(limit)).build();
    }
    
    @GET
    @Path("/audit/orders")
    @Operation(summary = "Get order audit events", description = "Get recent order audit events")
    public Response getOrderAuditEvents(@QueryParam("limit") @DefaultValue("100") int limit) {
        return Response.ok(auditTrailService.getEventsByCategory(
                AuditTrailService.AuditCategory.ORDER, limit)).build();
    }
    
    @GET
    @Path("/audit/trades")
    @Operation(summary = "Get trade audit events", description = "Get recent trade audit events")
    public Response getTradeAuditEvents(@QueryParam("limit") @DefaultValue("100") int limit) {
        return Response.ok(auditTrailService.getEventsByCategory(
                AuditTrailService.AuditCategory.TRADE, limit)).build();
    }
    
    @GET
    @Path("/audit/order/{clOrdId}")
    @Operation(summary = "Get order lifecycle", description = "Get complete lifecycle for an order")
    public Response getOrderLifecycle(@PathParam("clOrdId") String clOrdId) {
        AuditTrailService.OrderLifecycle lifecycle = auditTrailService.getOrderLifecycle(clOrdId);
        if (lifecycle == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Order not found"))
                    .build();
        }
        return Response.ok(lifecycle).build();
    }
    
    @GET
    @Path("/audit/stats")
    @Operation(summary = "Get audit statistics", description = "Get audit trail statistics")
    public Response getAuditStats() {
        return Response.ok(auditTrailService.getStatistics().toMap()).build();
    }
    
    // ==================== Performance Metrics ====================
    
    @GET
    @Path("/performance")
    @Operation(summary = "Get performance summary", description = "Get comprehensive performance summary")
    public Response getPerformanceSummary() {
        return Response.ok(performanceMetricsService.getSummary().toMap()).build();
    }
    
    @GET
    @Path("/performance/latency")
    @Operation(summary = "Get latency stats", description = "Get latency statistics for all operations")
    public Response getLatencyStats() {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, PerformanceMetricsService.LatencyStats> entry : 
                performanceMetricsService.getAllLatencyStats().entrySet()) {
            result.put(entry.getKey(), entry.getValue().toMap());
        }
        return Response.ok(result).build();
    }
    
    @GET
    @Path("/performance/latency/{operation}")
    @Operation(summary = "Get operation latency", description = "Get latency statistics for a specific operation")
    public Response getOperationLatency(@PathParam("operation") String operation) {
        return Response.ok(performanceMetricsService.getLatencyStats(operation).toMap()).build();
    }
    
    @GET
    @Path("/performance/sla")
    @Operation(summary = "Get SLA status", description = "Get SLA compliance status for all operations")
    public Response getSLAStatus() {
        return Response.ok(performanceMetricsService.getAllSLAStatus()).build();
    }
    
    @GET
    @Path("/performance/timeseries/{operation}")
    @Operation(summary = "Get time series data", description = "Get time series performance data for an operation")
    public Response getTimeSeries(@PathParam("operation") String operation,
                                  @QueryParam("seconds") @DefaultValue("60") int seconds) {
        return Response.ok(performanceMetricsService.getTimeSeries(operation, seconds)).build();
    }
    
    @POST
    @Path("/performance/reset")
    @Operation(summary = "Reset metrics", description = "Reset all performance metrics")
    public Response resetPerformanceMetrics() {
        performanceMetricsService.reset();
        return Response.ok(Map.of("success", true, "message", "Performance metrics reset")).build();
    }
    
    // ==================== Telemetry ====================
    
    @GET
    @Path("/telemetry")
    @Operation(summary = "Get all telemetry", description = "Get comprehensive telemetry data")
    public Response getAllTelemetry() {
        Map<String, Object> telemetry = new HashMap<>();
        telemetry.put("fix", telemetryService.getFixMetrics());
        telemetry.put("orders", telemetryService.getOrderMetrics());
        telemetry.put("matching", telemetryService.getMatchingMetrics());
        telemetry.put("marketData", telemetryService.getMarketDataMetrics());
        telemetry.put("websocket", telemetryService.getWebSocketMetrics());
        telemetry.put("system", telemetryService.getSystemHealthMetrics());
        return Response.ok(telemetry).build();
    }
    
    @GET
    @Path("/telemetry/fix")
    @Operation(summary = "Get FIX telemetry", description = "Get FIX protocol telemetry")
    public Response getFixTelemetry() {
        return Response.ok(telemetryService.getFixMetrics()).build();
    }
    
    @GET
    @Path("/telemetry/orders")
    @Operation(summary = "Get order telemetry", description = "Get order processing telemetry")
    public Response getOrderTelemetry() {
        return Response.ok(telemetryService.getOrderMetrics()).build();
    }
    
    @GET
    @Path("/telemetry/matching")
    @Operation(summary = "Get matching telemetry", description = "Get matching engine telemetry")
    public Response getMatchingTelemetry() {
        return Response.ok(telemetryService.getMatchingMetrics()).build();
    }
}
