package com.helesto.rest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.helesto.service.FeatureHubService;

@Path("/api/features")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FeatureHubRest {

    @Inject
    FeatureHubService featureHubService;

    @POST
    @Path("/watchlists/{name}")
    public Response createWatchlist(@PathParam("name") String name) {
        Set<String> symbols = featureHubService.createWatchlist(name);
        return Response.status(Response.Status.CREATED)
                .entity(Map.of("watchlist", name, "symbols", symbols))
                .build();
    }

    @POST
    @Path("/watchlists/{name}/symbols/{symbol}")
    public Response addWatchlistSymbol(@PathParam("name") String name, @PathParam("symbol") String symbol) {
        Set<String> symbols = featureHubService.addWatchlistSymbol(name, symbol);
        return Response.ok(Map.of("watchlist", name, "symbols", symbols)).build();
    }

    @DELETE
    @Path("/watchlists/{name}/symbols/{symbol}")
    public Response removeWatchlistSymbol(@PathParam("name") String name, @PathParam("symbol") String symbol) {
        Set<String> symbols = featureHubService.removeWatchlistSymbol(name, symbol);
        return Response.ok(Map.of("watchlist", name, "symbols", symbols)).build();
    }

    @GET
    @Path("/watchlists/{name}")
    public Response getWatchlist(@PathParam("name") String name) {
        Set<String> symbols = featureHubService.getWatchlist(name);
        return Response.ok(Map.of("watchlist", name, "symbols", symbols)).build();
    }

    @POST
    @Path("/alerts")
    public Response createAlert(CreateAlertRequest request) {
        if (request == null || request.symbol == null || request.symbol.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "symbol is required"))
                    .build();
        }
        FeatureHubService.PriceAlert alert = featureHubService.createAlert(
                request.userId,
                request.symbol,
                request.thresholdPrice,
                request.condition);
        return Response.status(Response.Status.CREATED).entity(alert).build();
    }

    @GET
    @Path("/alerts")
    public Response listAlerts(@QueryParam("userId") String userId) {
        List<FeatureHubService.PriceAlert> alerts;
        if (userId == null || userId.trim().isEmpty()) {
            alerts = featureHubService.listAlerts();
        } else {
            alerts = featureHubService.listAlertsByUser(userId);
        }
        return Response.ok(alerts).build();
    }

    @POST
    @Path("/alerts/{id}/ack")
    public Response acknowledgeAlert(@PathParam("id") String id) {
        FeatureHubService.PriceAlert alert = featureHubService.acknowledgeAlert(id);
        if (alert == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "alert not found"))
                    .build();
        }
        return Response.ok(alert).build();
    }

    @DELETE
    @Path("/alerts/{id}")
    public Response deleteAlert(@PathParam("id") String id) {
        boolean deleted = featureHubService.deleteAlert(id);
        if (!deleted) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "alert not found"))
                    .build();
        }
        return Response.ok(Map.of("deleted", true, "id", id)).build();
    }

    @POST
    @Path("/presets")
    public Response saveOrderPreset(SaveOrderPresetRequest request) {
        if (request == null || request.userId == null || request.userId.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "userId is required"))
                    .build();
        }
        FeatureHubService.OrderPreset preset = featureHubService.saveOrderPreset(
                request.userId,
                request.name,
                request.symbol,
                request.side,
                request.quantity,
                request.price,
                request.orderType);
        return Response.status(Response.Status.CREATED).entity(preset).build();
    }

    @GET
    @Path("/presets")
    public Response listOrderPresets(@QueryParam("userId") String userId) {
        List<FeatureHubService.OrderPreset> presets = featureHubService.listOrderPresets(userId);
        return Response.ok(presets).build();
    }

    @POST
    @Path("/strategies")
    public Response saveStrategyTemplate(SaveStrategyTemplateRequest request) {
        if (request == null || request.userId == null || request.userId.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "userId is required"))
                    .build();
        }
        FeatureHubService.StrategyTemplate strategy = featureHubService.saveStrategyTemplate(
                request.userId,
                request.name,
                request.description,
                request.symbols,
                request.riskProfile);
        return Response.status(Response.Status.CREATED).entity(strategy).build();
    }

    @GET
    @Path("/strategies")
    public Response listStrategyTemplates(@QueryParam("userId") String userId) {
        List<FeatureHubService.StrategyTemplate> strategies = featureHubService.listStrategyTemplates(userId);
        return Response.ok(strategies).build();
    }

    @POST
    @Path("/notes")
    public Response addTradeNote(AddTradeNoteRequest request) {
        if (request == null || request.symbol == null || request.symbol.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "symbol is required"))
                    .build();
        }
        FeatureHubService.TradeNote note = featureHubService.addTradeNote(
                request.symbol,
                request.author,
                request.noteText,
                request.sentiment);
        return Response.status(Response.Status.CREATED).entity(note).build();
    }

    @GET
    @Path("/notes/{symbol}")
    public Response getTradeNotes(@PathParam("symbol") String symbol) {
        List<FeatureHubService.TradeNote> notes = featureHubService.getTradeNotes(symbol);
        return Response.ok(notes).build();
    }

    @POST
    @Path("/orders/{orderRef}/tags/{tag}")
    public Response addOrderTag(@PathParam("orderRef") String orderRef, @PathParam("tag") String tag) {
        Set<String> tags = featureHubService.addOrderTag(orderRef, tag);
        return Response.ok(Map.of("orderRef", orderRef, "tags", tags)).build();
    }

    @GET
    @Path("/orders/{orderRef}/tags")
    public Response listOrderTags(@PathParam("orderRef") String orderRef) {
        Set<String> tags = featureHubService.listOrderTags(orderRef);
        return Response.ok(Map.of("orderRef", orderRef, "tags", tags)).build();
    }

    @GET
    @Path("/tags/{tag}/orders")
    public Response findOrdersByTag(@PathParam("tag") String tag) {
        List<String> orderRefs = featureHubService.findOrdersByTag(tag);
        return Response.ok(Map.of("tag", tag, "orders", orderRefs)).build();
    }

    @POST
    @Path("/dashboard-config")
    public Response saveDashboardConfig(SaveDashboardConfigRequest request) {
        if (request == null || request.userId == null || request.userId.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "userId is required"))
                    .build();
        }
        FeatureHubService.UserDashboardConfig config = featureHubService.saveDashboardConfig(
                request.userId,
                request.widgets,
                request.defaultSymbol);
        return Response.ok(config).build();
    }

    @GET
    @Path("/dashboard-config")
    public Response getDashboardConfig(@QueryParam("userId") String userId) {
        FeatureHubService.UserDashboardConfig config = featureHubService.getDashboardConfig(userId);
        return Response.ok(config).build();
    }

    @POST
    @Path("/risk-profile")
    public Response setRiskProfile(SetRiskProfileRequest request) {
        if (request == null || request.userId == null || request.userId.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "userId is required"))
                    .build();
        }
        String profile = featureHubService.setRiskProfile(request.userId, request.riskProfile);
        return Response.ok(Map.of("userId", request.userId, "riskProfile", profile)).build();
    }

    @GET
    @Path("/risk-profile")
    public Response getRiskProfile(@QueryParam("userId") String userId) {
        return Response.ok(Map.of("userId", userId, "riskProfile", featureHubService.getRiskProfile(userId))).build();
    }

    @POST
    @Path("/paper-trades")
    public Response recordPaperTrade(RecordPaperTradeRequest request) {
        if (request == null || request.userId == null || request.userId.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "userId is required"))
                    .build();
        }
        FeatureHubService.PaperTrade trade = featureHubService.recordPaperTrade(
                request.userId,
                request.symbol,
                request.side,
                request.quantity,
                request.price);
        return Response.status(Response.Status.CREATED).entity(trade).build();
    }

    @GET
    @Path("/paper-trades")
    public Response listPaperTrades(@QueryParam("userId") String userId) {
        return Response.ok(featureHubService.getPaperTrades(userId)).build();
    }

    @GET
    @Path("/paper-portfolio")
    public Response getPaperPortfolio(@QueryParam("userId") String userId) {
        return Response.ok(featureHubService.getPaperPortfolioSummary(userId)).build();
    }

    @DELETE
    @Path("/paper-portfolio")
    public Response resetPaperPortfolio(@QueryParam("userId") String userId) {
        int removed = featureHubService.resetPaperPortfolio(userId);
        return Response.ok(Map.of("userId", userId, "removedTrades", removed)).build();
    }

    @POST
    @Path("/notifications")
    public Response createNotification(CreateNotificationRequest request) {
        if (request == null || request.userId == null || request.userId.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "userId is required"))
                    .build();
        }
        FeatureHubService.UserNotification notification = featureHubService.createNotification(
                request.userId,
                request.type,
                request.message);
        return Response.status(Response.Status.CREATED).entity(notification).build();
    }

    @GET
    @Path("/notifications")
    public Response listNotifications(@QueryParam("userId") String userId,
                                      @QueryParam("unreadOnly") @javax.ws.rs.DefaultValue("false") boolean unreadOnly) {
        return Response.ok(featureHubService.listNotifications(userId, unreadOnly)).build();
    }

    @POST
    @Path("/notifications/{id}/read")
    public Response markNotificationRead(@PathParam("id") String id) {
        FeatureHubService.UserNotification notification = featureHubService.markNotificationRead(id);
        if (notification == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "notification not found"))
                    .build();
        }
        return Response.ok(notification).build();
    }

    @DELETE
    @Path("/notifications")
    public Response clearNotifications(@QueryParam("userId") String userId) {
        int removed = featureHubService.clearNotifications(userId);
        return Response.ok(Map.of("userId", userId, "removedNotifications", removed)).build();
    }

    @GET
    @Path("/stats")
    public Response getFeatureStats() {
        return Response.ok(featureHubService.getFeatureStats()).build();
    }

    public static class CreateAlertRequest {
        public String userId;
        public String symbol;
        public double thresholdPrice;
        public String condition;
    }

    public static class SaveOrderPresetRequest {
        public String userId;
        public String name;
        public String symbol;
        public String side;
        public long quantity;
        public double price;
        public String orderType;
    }

    public static class SaveStrategyTemplateRequest {
        public String userId;
        public String name;
        public String description;
        public List<String> symbols;
        public String riskProfile;
    }

    public static class AddTradeNoteRequest {
        public String symbol;
        public String author;
        public String noteText;
        public String sentiment;
    }

    public static class SaveDashboardConfigRequest {
        public String userId;
        public List<String> widgets;
        public String defaultSymbol;
    }

    public static class SetRiskProfileRequest {
        public String userId;
        public String riskProfile;
    }

    public static class RecordPaperTradeRequest {
        public String userId;
        public String symbol;
        public String side;
        public long quantity;
        public double price;
    }

    public static class CreateNotificationRequest {
        public String userId;
        public String type;
        public String message;
    }
}
