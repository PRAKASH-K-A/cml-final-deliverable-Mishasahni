package com.helesto.rest;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.core.Trader;

import quickfix.ConfigError;

@Path("/api/session")
@Produces(MediaType.APPLICATION_JSON)
public class SessionRest {

    private static final Logger LOG = LoggerFactory.getLogger(SessionRest.class.getName());

    @Inject
    Trader trader;

    public static class SessionStatus {
        public boolean isConnected;
        public String targetHost;
        public String targetPort;

        public SessionStatus(boolean isConnected, String targetHost, String targetPort) {
            this.isConnected = isConnected;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
        }
    }

    @GET
    @Operation(summary = "Get session status", description = "Get the current FIX session status for the Broker")
    @APIResponse(responseCode = "200", description = "Session status retrieved")
    public SessionStatus getSessionStatus() {
        LOG.info("GET /session");
        return new SessionStatus(
                trader.isInitiatorStarted(),
                "localhost",
                "9876"
        );
    }

    @POST
    @Path("/logon")
    @Operation(summary = "Logon to Exchange", description = "Establish FIX connection to the Exchange")
    @APIResponse(responseCode = "200", description = "Logon successful")
    public String logon() {
        LOG.info("POST /session/logon");
        try {
            trader.logon();
            return "{\"status\":\"success\"}";
        } catch (ConfigError e) {
            LOG.error("Logon failed", e);
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    @POST
    @Path("/logout")
    @Operation(summary = "Logout from Exchange", description = "Close FIX connection to the Exchange")
    @APIResponse(responseCode = "200", description = "Logout successful")
    public String logout() {
        LOG.info("POST /session/logout");
        try {
            trader.logout();
            return "{\"status\":\"success\"}";
        } catch (Exception e) {
            LOG.error("Logout failed", e);
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }
}
