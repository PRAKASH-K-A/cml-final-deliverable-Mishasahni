package com.helesto.rest;

import java.util.List;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.core.Exchange;
import com.helesto.core.ExchangeApplication;
import com.helesto.dao.OrderDao;
import com.helesto.model.OrderEntity;
import com.helesto.service.QuickFixRequestFrameworkService;

import quickfix.SessionID;

@Path("/api/session")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SessionRest {

    private static final Logger LOG = LoggerFactory.getLogger(SessionRest.class.getName());

    private final Exchange exchange;
    private final ExchangeApplication exchangeApplication;
    private final OrderDao orderDao;
    private final QuickFixRequestFrameworkService quickFixRequestFrameworkService;

    @Inject
    public SessionRest(Exchange exchange,
            ExchangeApplication exchangeApplication,
            OrderDao orderDao,
            QuickFixRequestFrameworkService quickFixRequestFrameworkService) {
        this.exchange = exchange;
        this.exchangeApplication = exchangeApplication;
        this.orderDao = orderDao;
        this.quickFixRequestFrameworkService = quickFixRequestFrameworkService;
    }

    public static class SessionStatus {
        private final String sessionId;
        private final boolean isLoggedOn;
        private final boolean connected;
        private final String senderCompId;
        private final String targetCompId;
        private final Long lastMessageTime;

        public SessionStatus(String sessionId,
                boolean isLoggedOn,
                boolean connected,
                String senderCompId,
                String targetCompId,
                Long lastMessageTime) {
            this.sessionId = sessionId;
            this.isLoggedOn = isLoggedOn;
            this.connected = connected;
            this.senderCompId = senderCompId;
            this.targetCompId = targetCompId;
            this.lastMessageTime = lastMessageTime;
        }

        public String getSessionId() {
            return sessionId;
        }

        public boolean isLoggedOn() {
            return isLoggedOn;
        }

        public boolean isConnected() {
            return connected;
        }

        public String getSenderCompId() {
            return senderCompId;
        }

        public String getTargetCompId() {
            return targetCompId;
        }

        public Long getLastMessageTime() {
            return lastMessageTime;
        }
    }

    public static class QuickFixResponseRequest {
        private String clOrdId;
        private String responseType;
        private String text;

        public String getClOrdId() {
            return clOrdId;
        }

        public void setClOrdId(String clOrdId) {
            this.clOrdId = clOrdId;
        }

        public String getResponseType() {
            return responseType;
        }

        public void setResponseType(String responseType) {
            this.responseType = responseType;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }

    public static class QuickFixResponseResult {
        private final boolean success;
        private final String responseType;
        private final String clOrdId;
        private final String message;

        public QuickFixResponseResult(boolean success, String responseType, String clOrdId, String message) {
            this.success = success;
            this.responseType = responseType;
            this.clOrdId = clOrdId;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getResponseType() {
            return responseType;
        }

        public String getClOrdId() {
            return clOrdId;
        }

        public String getMessage() {
            return message;
        }
    }

    @GET
    @Operation(summary = "Get session status", description = "Get the current FIX session status for the Exchange")
    @APIResponse(responseCode = "200", description = "Session status retrieved")
    public SessionStatus getSessionStatus() {
        LOG.info("GET /session");

        SessionID sessionID = exchangeApplication.getCurrentSessionId();
        String senderCompId = sessionID != null ? sessionID.getSenderCompID() : null;
        String targetCompId = sessionID != null ? sessionID.getTargetCompID() : null;
        String sessionId = sessionID != null ? sessionID.toString() : "EXCHANGE->BROKER";

        Long lastMessageTime = null;
        List<ExchangeApplication.SessionEvent> recentEvents = exchangeApplication.getRecentSessionEvents(1);
        if (!recentEvents.isEmpty()) {
            lastMessageTime = recentEvents.get(0).timestamp;
        }

        return new SessionStatus(
                sessionId,
                exchangeApplication.isSessionLoggedOn(),
                exchange.isAcceptorStarted() && exchangeApplication.isSessionLoggedOn(),
                senderCompId,
                targetCompId,
                lastMessageTime
        );
    }

    @GET
    @Path("/events")
    @Operation(summary = "Get session events", description = "Get recent session events")
    @APIResponse(responseCode = "200", description = "Session events retrieved")
    public List<ExchangeApplication.SessionEvent> getSessionEvents(@QueryParam("limit") Integer limit) {
        int eventLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 1000));
        LOG.info("GET /session/events?limit={}", eventLimit);
        return exchangeApplication.getRecentSessionEvents(eventLimit);
    }

    @POST
    @Path("/respond")
    @Operation(summary = "Send QuickFIX/J response", description = "Send a FIX execution response for an order to the active broker session")
    @APIResponse(responseCode = "200", description = "Response sent")
    public Response sendQuickFixResponse(QuickFixResponseRequest request) {
        if (request == null || request.getClOrdId() == null || request.getClOrdId().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new QuickFixResponseResult(false, null, null, "clOrdId is required"))
                    .build();
        }

        SessionID sessionID = exchangeApplication.getCurrentSessionId();
        if (sessionID == null || !exchangeApplication.isSessionLoggedOn()) {
            return Response.status(Response.Status.CONFLICT)
                .entity(new QuickFixResponseResult(false, request.getResponseType(), request.getClOrdId(),
                            "No active FIX session. Broker must be logged on first."))
                    .build();
        }

        OrderEntity order = orderDao.findByClOrdId(request.getClOrdId());
        if (order == null) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(new QuickFixResponseResult(false, request.getResponseType(), request.getClOrdId(),
                    "Order not found for clOrdId: " + request.getClOrdId()))
                    .build();
        }

        String text = (request.getText() == null || request.getText().isBlank())
                ? "Manual QuickFIX/J response from Exchange API"
            : request.getText();

        QuickFixRequestFrameworkService.DispatchResult dispatchResult =
                quickFixRequestFrameworkService.dispatchResponse(request.getResponseType(), order, sessionID, text);

        if (dispatchResult.isUnsupported()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new QuickFixResponseResult(false, dispatchResult.getResponseType(), request.getClOrdId(),
                            dispatchResult.getMessage()))
                    .build();
        }

        if (!dispatchResult.isSuccess()) {
            LOG.error("Failed to send QuickFIX response for {}: {}", request.getClOrdId(), dispatchResult.getMessage());
            return Response.status(Response.Status.CONFLICT)
                        .entity(new QuickFixResponseResult(false, dispatchResult.getResponseType(), request.getClOrdId(),
                            dispatchResult.getMessage()))
                    .build();
        }

        return Response.ok(new QuickFixResponseResult(true, dispatchResult.getResponseType(), request.getClOrdId(),
                dispatchResult.getMessage())).build();
    }

    @GET
    @Path("/respond/types")
    @Operation(summary = "Get supported QuickFIX response types", description = "Returns all response types currently supported by the QuickFIX request framework")
    @APIResponse(responseCode = "200", description = "Response types retrieved")
    public java.util.Set<String> getSupportedQuickFixResponseTypes() {
        return quickFixRequestFrameworkService.getSupportedResponseTypes();
    }

    @POST
    @Path("/events")
    @Operation(summary = "Get session events (legacy)", description = "Backward-compatible endpoint returning recent session events")
    @APIResponse(responseCode = "200", description = "Session events retrieved")
    public List<ExchangeApplication.SessionEvent> getSessionEventsLegacy() {
        LOG.info("POST /session/events");
        return exchangeApplication.getRecentSessionEvents(100);
    }
}
