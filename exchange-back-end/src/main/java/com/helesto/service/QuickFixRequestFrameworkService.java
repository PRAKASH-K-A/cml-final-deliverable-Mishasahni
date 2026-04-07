package com.helesto.service;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import com.helesto.model.OrderEntity;

import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.field.OrdRejReason;

@ApplicationScoped
public class QuickFixRequestFrameworkService {

    private final Map<String, QuickFixResponseHandler> responseHandlers = new ConcurrentHashMap<>();

    @Inject
    public QuickFixRequestFrameworkService(ExecutionReportService executionReportService) {
    responseHandlers.put("ACK", (order, sessionId, text) -> executionReportService.sendAck(order, sessionId));
    responseHandlers.put("STATUS", (order, sessionId, text) -> executionReportService.sendOrderStatus(order, sessionId));
    responseHandlers.put("CANCEL", (order, sessionId, text) -> executionReportService.sendCancel(order, sessionId));
    responseHandlers.put("REJECT", (order, sessionId, text) ->
                executionReportService.sendReject(order, OrdRejReason.OTHER,
                        text == null || text.isBlank()
                                ? "Manual QuickFIX/J reject from framework"
                                : text,
                        sessionId));
    }

    public Set<String> getSupportedResponseTypes() {
        return Collections.unmodifiableSet(responseHandlers.keySet());
    }

    public DispatchResult dispatchResponse(String responseType,
                                           OrderEntity order,
                                           SessionID sessionId,
                                           String text) {
        String normalizedType = normalizeType(responseType);
        QuickFixResponseHandler handler = responseHandlers.get(normalizedType);

        if (handler == null) {
            return DispatchResult.unsupported(normalizedType,
                    "Unsupported responseType. Use " + String.join(", ", getSupportedResponseTypes()));
        }

        try {
            handler.handle(order, sessionId, text);
            return DispatchResult.success(normalizedType,
                    "QuickFIX response sent to session " + sessionId);
        } catch (SessionNotFound e) {
            return DispatchResult.failure(normalizedType,
                    "FIX session not found while sending response");
        }
    }

    public void register(String responseType, QuickFixResponseHandler handler) {
        responseHandlers.put(normalizeType(responseType), handler);
    }

    private String normalizeType(String responseType) {
        return responseType == null || responseType.isBlank()
                ? "STATUS"
                : responseType.trim().toUpperCase();
    }

    @FunctionalInterface
    public interface QuickFixResponseHandler {
        void handle(OrderEntity order, SessionID sessionId, String text) throws SessionNotFound;
    }

    public static class DispatchResult {
        private final boolean success;
        private final boolean unsupported;
        private final String responseType;
        private final String message;

        private DispatchResult(boolean success, boolean unsupported, String responseType, String message) {
            this.success = success;
            this.unsupported = unsupported;
            this.responseType = responseType;
            this.message = message;
        }

        public static DispatchResult success(String responseType, String message) {
            return new DispatchResult(true, false, responseType, message);
        }

        public static DispatchResult failure(String responseType, String message) {
            return new DispatchResult(false, false, responseType, message);
        }

        public static DispatchResult unsupported(String responseType, String message) {
            return new DispatchResult(false, true, responseType, message);
        }

        public boolean isSuccess() {
            return success;
        }

        public boolean isUnsupported() {
            return unsupported;
        }

        public String getResponseType() {
            return responseType;
        }

        public String getMessage() {
            return message;
        }
    }
}
