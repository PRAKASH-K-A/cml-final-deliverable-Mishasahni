package com.helesto.api;

import java.util.Map;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import com.helesto.service.TelemetryService;

/**
 * REST endpoints for telemetry/metrics exposure
 * GET /metrics - all telemetry data
 * GET /metrics/{category} - specific category (fix, orders, matching, marketData, webSocket, optionsPricing, system)
 */
@Path("/metrics")
@Produces(MediaType.APPLICATION_JSON)
public class TelemetryResource {

    @Inject
    TelemetryService telemetryService;

    @GET
    public Map<String, Object> getAllMetrics() {
        return telemetryService.getAllMetrics();
    }

    @GET
    @Path("/fix")
    public Map<String, Object> getFixMetrics() {
        return telemetryService.getFixMetrics();
    }

    @GET
    @Path("/orders")
    public Map<String, Object> getOrderMetrics() {
        return telemetryService.getOrderMetrics();
    }

    @GET
    @Path("/matching")
    public Map<String, Object> getMatchingMetrics() {
        return telemetryService.getMatchingMetrics();
    }

    @GET
    @Path("/market-data")
    public Map<String, Object> getMarketDataMetrics() {
        return telemetryService.getMarketDataMetrics();
    }

    @GET
    @Path("/websocket")
    public Map<String, Object> getWebSocketMetrics() {
        return telemetryService.getWebSocketMetrics();
    }

    @GET
    @Path("/options-pricing")
    public Map<String, Object> getOptionsPricingMetrics() {
        return telemetryService.getOptionsPricingMetrics();
    }

    @GET
    @Path("/system")
    public Map<String, Object> getSystemHealthMetrics() {
        return telemetryService.getSystemHealthMetrics();
    }
}
