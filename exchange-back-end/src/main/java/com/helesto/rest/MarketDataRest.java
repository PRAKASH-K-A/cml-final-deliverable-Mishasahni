package com.helesto.rest;

import com.helesto.service.*;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.*;

/**
 * REST endpoints for Market Data and Options Pricing
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MarketDataRest {

    @Inject
    ReferenceDataService referenceDataService;
    
    @Inject
    MarketDataPoller marketDataPoller;
    
    @Inject
    BlackScholesPricingService pricingService;
    
    @Inject
    OrderBookManager orderBookManager;
    
    @Inject
    TradeService tradeService;
    
    // ================== Reference Data Endpoints ==================
    
    @GET
    @Path("/securities")
    public Response getAllSecurities() {
        return Response.ok(referenceDataService.getAllSecurities()).build();
    }
    
    @GET
    @Path("/securities/{symbol}")
    public Response getSecurity(@PathParam("symbol") String symbol) {
        ReferenceDataService.Security security = referenceDataService.getSecurity(symbol);
        if (security == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Security not found: " + symbol))
                    .build();
        }
        return Response.ok(security).build();
    }
    
    @GET
    @Path("/securities/sector/{sector}")
    public Response getSecuritiesBySector(@PathParam("sector") String sector) {
        return Response.ok(referenceDataService.getSecuritiesBySector(sector)).build();
    }
    
    // ================== Market Data Endpoints ==================
    
    @GET
    @Path("/marketdata")
    public Response getAllMarketData() {
        Collection<MarketDataPoller.MarketDataSnapshot> snapshots = marketDataPoller.getAllSnapshots();
        if (snapshots.isEmpty()) {
            // Return reference data if poller hasn't started
            return Response.ok(referenceDataService.getAllMarketData()).build();
        }
        return Response.ok(snapshots).build();
    }
    
    @GET
    @Path("/marketdata/{symbol}")
    public Response getMarketData(@PathParam("symbol") String symbol) {
        MarketDataPoller.MarketDataSnapshot snapshot = marketDataPoller.getLatestSnapshot(symbol);
        if (snapshot == null) {
            // Fallback to reference data
            ReferenceDataService.MarketData md = referenceDataService.getMarketData(symbol);
            if (md == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Market data not found: " + symbol))
                        .build();
            }
            return Response.ok(md).build();
        }
        return Response.ok(snapshot).build();
    }
    
    // ================== Order Book Endpoints ==================
    
    @GET
    @Path("/orderbook/{symbol}")
    public Response getOrderBook(
            @PathParam("symbol") String symbol,
            @QueryParam("depth") @DefaultValue("10") int depth) {
        OrderBookManager.OrderBookSnapshot snapshot = orderBookManager.getSnapshot(symbol, depth);
        return Response.ok(snapshot).build();
    }
    
    @GET
    @Path("/orderbook/{symbol}/bbo")
    public Response getBestBidOffer(@PathParam("symbol") String symbol) {
        Double bestBid = orderBookManager.getBestBid(symbol);
        Double bestAsk = orderBookManager.getBestAsk(symbol);
        
        return Response.ok(Map.of(
                "symbol", symbol,
                "bestBid", bestBid != null ? bestBid : 0,
                "bestAsk", bestAsk != null ? bestAsk : 0,
                "spread", (bestBid != null && bestAsk != null) ? bestAsk - bestBid : 0,
                "timestamp", System.currentTimeMillis()
        )).build();
    }
    
    // ================== Trade Endpoints ==================
    
    @GET
    @Path("/trades")
    public Response getRecentTrades(@QueryParam("limit") @DefaultValue("100") int limit) {
        return Response.ok(tradeService.getRecentTrades(limit)).build();
    }
    
    @GET
    @Path("/trades/{symbol}")
    public Response getTradesBySymbol(@PathParam("symbol") String symbol) {
        return Response.ok(tradeService.getTradesBySymbol(symbol)).build();
    }
    
    @GET
    @Path("/trades/stats/{symbol}")
    public Response getTradeStats(@PathParam("symbol") String symbol) {
        return Response.ok(tradeService.getTradeStats(symbol)).build();
    }
    
    // ================== Options Pricing Endpoints ==================
    
    @GET
    @Path("/options/price")
    public Response priceOption(
            @QueryParam("spot") double spot,
            @QueryParam("strike") double strike,
            @QueryParam("timeToExpiry") double timeToExpiry,
            @QueryParam("riskFreeRate") @DefaultValue("0.05") double riskFreeRate,
            @QueryParam("volatility") double volatility,
            @QueryParam("isCall") @DefaultValue("true") boolean isCall) {
        
        if (spot <= 0 || strike <= 0 || timeToExpiry <= 0 || volatility <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid parameters: spot, strike, timeToExpiry, volatility must be > 0"))
                    .build();
        }
        
        BlackScholesPricingService.OptionPriceResult result = 
            pricingService.priceOption(spot, strike, timeToExpiry, riskFreeRate, volatility, isCall);
        
        return Response.ok(result).build();
    }
    
    @GET
    @Path("/options/greeks")
    public Response getGreeks(
            @QueryParam("spot") double spot,
            @QueryParam("strike") double strike,
            @QueryParam("timeToExpiry") double timeToExpiry,
            @QueryParam("riskFreeRate") @DefaultValue("0.05") double riskFreeRate,
            @QueryParam("volatility") double volatility,
            @QueryParam("isCall") @DefaultValue("true") boolean isCall) {
        
        if (spot <= 0 || strike <= 0 || timeToExpiry <= 0 || volatility <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid parameters"))
                    .build();
        }
        
        BlackScholesPricingService.Greeks greeks = isCall 
            ? pricingService.callGreeks(spot, strike, timeToExpiry, riskFreeRate, volatility)
            : pricingService.putGreeks(spot, strike, timeToExpiry, riskFreeRate, volatility);
        
        return Response.ok(greeks).build();
    }
    
    @GET
    @Path("/options/impliedvol")
    public Response getImpliedVolatility(
            @QueryParam("marketPrice") double marketPrice,
            @QueryParam("spot") double spot,
            @QueryParam("strike") double strike,
            @QueryParam("timeToExpiry") double timeToExpiry,
            @QueryParam("riskFreeRate") @DefaultValue("0.05") double riskFreeRate,
            @QueryParam("isCall") @DefaultValue("true") boolean isCall) {
        
        if (marketPrice <= 0 || spot <= 0 || strike <= 0 || timeToExpiry <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid parameters"))
                    .build();
        }
        
        double iv = pricingService.impliedVolatility(marketPrice, spot, strike, timeToExpiry, riskFreeRate, isCall);
        
        return Response.ok(Map.of(
                "impliedVolatility", iv,
                "impliedVolatilityPercent", iv * 100,
                "marketPrice", marketPrice,
                "spot", spot,
                "strike", strike,
                "timeToExpiry", timeToExpiry
        )).build();
    }
    
    @POST
    @Path("/options/chain")
    public Response getOptionChain(OptionChainRequest request) {
        if (request.spot <= 0 || request.timeToExpiry <= 0 || request.volatility <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid parameters"))
                    .build();
        }
        
        List<Map<String, Object>> chain = new ArrayList<>();
        double[] strikes = request.strikes != null && request.strikes.length > 0 
            ? request.strikes 
            : generateStrikes(request.spot, request.strikeRange, request.strikeInterval);
        
        for (double strike : strikes) {
            BlackScholesPricingService.OptionPriceResult call = 
                pricingService.priceOption(request.spot, strike, request.timeToExpiry, 
                        request.riskFreeRate, request.volatility, true);
            BlackScholesPricingService.OptionPriceResult put = 
                pricingService.priceOption(request.spot, strike, request.timeToExpiry, 
                        request.riskFreeRate, request.volatility, false);
            
            chain.add(Map.of(
                    "strike", strike,
                    "call", call,
                    "put", put
            ));
        }
        
        return Response.ok(Map.of(
                "underlying", request.underlying,
                "spot", request.spot,
                "timeToExpiry", request.timeToExpiry,
                "volatility", request.volatility,
                "chain", chain
        )).build();
    }
    
    private double[] generateStrikes(double spot, double range, double interval) {
        if (range <= 0) range = 0.2; // 20% range
        if (interval <= 0) interval = spot < 100 ? 2.5 : 5.0;
        
        double minStrike = Math.floor((spot * (1 - range)) / interval) * interval;
        double maxStrike = Math.ceil((spot * (1 + range)) / interval) * interval;
        
        List<Double> strikes = new ArrayList<>();
        for (double s = minStrike; s <= maxStrike; s += interval) {
            strikes.add(s);
        }
        
        return strikes.stream().mapToDouble(Double::doubleValue).toArray();
    }
    
    // ================== Request Classes ==================
    
    public static class OptionChainRequest {
        public String underlying;
        public double spot;
        public double timeToExpiry;
        public double volatility;
        public double riskFreeRate = 0.05;
        public double[] strikes;
        public double strikeRange = 0.2;
        public double strikeInterval = 5.0;
    }
}
