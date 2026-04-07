package com.helesto.rest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import static io.restassured.RestAssured.given;
import io.restassured.response.Response;

@QuarkusTest
class OrderManagementEndToEndTest {

    private static final String TEST_SYMBOL = "AAPL";
    private static final String TEST_CLIENT = "CLIENT001";

    private void postJson(String path) {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post(path)
        .then()
            .statusCode(200);
    }

    private void postJson(String path, String pathParamName, String pathParamValue) {
        given()
            .contentType("application/json")
            .body("{}")
            .pathParam(pathParamName, pathParamValue)
        .when()
            .post(path)
        .then()
            .statusCode(200);
    }

    private void ensureTradingReady(String symbol) {
        given()
            .contentType("application/json")
            .queryParam("reason", "integration-test")
            .body("{}")
        .when()
            .post("/api/system/market/open")
        .then()
            .statusCode(200);

        postJson("/api/system/risk/resume");
        postJson("/api/system/circuit-breakers/market/resume");
        postJson("/api/system/circuit-breakers/{symbol}/resume", "symbol", symbol);
    }

    private double getReferencePrice(String symbol) {
        Response response = given()
        .when()
            .get("/api/marketdata/{symbol}", symbol)
        .then()
            .statusCode(200)
            .extract().response();

        Double lastPrice = response.jsonPath().getDouble("lastPrice");
        if (lastPrice == null || lastPrice <= 0) {
            return 100.0;
        }
        return lastPrice;
    }

    @Test
    void shouldProcessOrderLifecycleEndToEnd() {
        ensureTradingReady(TEST_SYMBOL);
        double refPrice = getReferencePrice(TEST_SYMBOL);
        double limitPrice = Math.round(refPrice * 1.005 * 100.0) / 100.0;

        String clOrdId = "IT-" + UUID.randomUUID();
        Map<String, Object> request = new HashMap<>();
        request.put("symbol", TEST_SYMBOL);
        request.put("side", "1");
        request.put("quantity", 25);
        request.put("price", limitPrice);
        request.put("orderType", "2");
        request.put("timeInForce", "0");
        request.put("clientId", TEST_CLIENT);
        request.put("clOrdId", clOrdId);

        Response submit = given()
            .contentType("application/json")
            .body(request)
        .when()
            .post("/api/orders/orchestrated")
        .then()
            .statusCode(201)
            .body("success", org.hamcrest.Matchers.is(true))
            .body("orderRefNumber", notNullValue())
            .extract().response();

        String orderRefNumber = submit.jsonPath().getString("orderRefNumber");

        given()
        .when()
            .get("/api/orders/{orderRefNumber}", orderRefNumber)
        .then()
            .statusCode(200)
            .body("orderRefNumber", org.hamcrest.Matchers.equalTo(orderRefNumber));

        Map<String, Object> cancelRequest = new HashMap<>();
        cancelRequest.put("clientId", TEST_CLIENT);
        cancelRequest.put("reason", "integration cancel");

        Response cancelResponse = given()
            .contentType("application/json")
            .body(cancelRequest)
        .when()
            .post("/api/orders/{orderRefNumber}/cancel", orderRefNumber)
        .then()
            .extract().response();

        int cancelStatus = cancelResponse.statusCode();
        Assertions.assertTrue(
                Set.of(200, 400).contains(cancelStatus),
                "Cancel should return 200 (accepted) or 400 (already terminal), got " + cancelStatus);

        if (cancelStatus == 200) {
            Assertions.assertTrue(cancelResponse.jsonPath().getBoolean("success"));
        } else {
            Assertions.assertFalse(cancelResponse.jsonPath().getBoolean("success"));
        }
    }

    @Test
    void shouldHandleBatchSubmissionWithThroughputMetrics() {
        ensureTradingReady(TEST_SYMBOL);
        double refPrice = getReferencePrice(TEST_SYMBOL);

        List<Map<String, Object>> orders = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Map<String, Object> order = new HashMap<>();
            order.put("symbol", TEST_SYMBOL);
            order.put("side", "2");
            order.put("quantity", 10 + i);
            double jitterBps = ThreadLocalRandom.current().nextDouble(-0.0025, 0.0025);
            double price = Math.round(refPrice * (1.001 + jitterBps) * 100.0) / 100.0;
            order.put("price", price);
            order.put("orderType", "2");
            order.put("timeInForce", "0");
            order.put("clientId", TEST_CLIENT);
            order.put("clOrdId", "BATCH-" + UUID.randomUUID());
            orders.add(order);
        }

        Map<String, Object> batchRequest = new HashMap<>();
        batchRequest.put("orders", orders);
        batchRequest.put("continueOnError", true);

        given()
            .contentType("application/json")
            .body(batchRequest)
        .when()
            .post("/api/orders/orchestrated/batch")
        .then()
            .statusCode(200)
            .body("processedOrders", org.hamcrest.Matchers.equalTo(10))
            .body("acceptedOrders", greaterThanOrEqualTo(1))
            .body("throughputOpsPerSecond", notNullValue())
            .body("results.size()", org.hamcrest.Matchers.equalTo(10));
    }
}
