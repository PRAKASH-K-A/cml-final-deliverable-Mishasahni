package com.helesto.rest;

import java.util.List;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.dao.OrderDao;
import com.helesto.model.OrderEntity;

@Path("/api/orders-legacy")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrdersRest {

    private static final Logger LOG = LoggerFactory.getLogger(OrdersRest.class.getName());

    @Inject
    OrderDao orderDao;

    @GET
    @Operation(summary = "Get all orders", description = "Retrieve a list of all orders")
    @APIResponse(responseCode = "200", description = "List of orders", content = @Content(schema = @Schema(implementation = OrderEntity.class)))
    public List<OrderEntity> getOrders() {
        LOG.info("GET /orders");
        return orderDao.findAll();
    }

    @POST
    @Operation(summary = "Create a new order", description = "Create and persist a new order")
    @APIResponse(responseCode = "201", description = "Order created", content = @Content(schema = @Schema(implementation = OrderEntity.class)))
    public OrderEntity createOrder(OrderEntity order) {
        LOG.info("POST /orders - Creating order: {}", order.getClOrdId());
        orderDao.persistOrder(order);
        return order;
    }

    @GET
    @Path("/{clOrdId}")
    @Operation(summary = "Get order by ClOrdId", description = "Retrieve a specific order by its client order ID")
    @APIResponse(responseCode = "200", description = "Order found", content = @Content(schema = @Schema(implementation = OrderEntity.class)))
    @APIResponse(responseCode = "404", description = "Order not found")
    public OrderEntity getOrder(@PathParam("clOrdId") String clOrdId) {
        LOG.info("GET /orders/{} ", clOrdId);
        OrderEntity order = orderDao.findByClOrdId(clOrdId);
        if (order == null) {
            LOG.warn("Order not found: {}", clOrdId);
            return new OrderEntity(); // Return empty order (404 should be handled by framework)
        }
        return order;
    }

    @POST
    @Path("/{clOrdId}/cancel")
    @Operation(summary = "Cancel an order", description = "Cancel an existing order by its client order ID")
    @APIResponse(responseCode = "200", description = "Order cancelled")
    @APIResponse(responseCode = "404", description = "Order not found")
    public void cancelOrder(@PathParam("clOrdId") String clOrdId) {
        LOG.info("POST /orders/{}/cancel", clOrdId);
        OrderEntity order = orderDao.findByClOrdId(clOrdId);
        if (order != null) {
            order.setStatus("CANCELED");
            orderDao.updateOrder(order);
            LOG.info("Order canceled: {}", clOrdId);
        } else {
            LOG.warn("Order not found for cancellation: {}", clOrdId);
        }
    }
}
