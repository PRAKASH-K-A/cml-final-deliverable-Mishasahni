package com.helesto.rest;

import java.util.List;

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

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.dao.OrderDao;
import com.helesto.model.OrderEntity;
import com.helesto.service.FIXOrderService;

@Path("/api/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrdersRest {

    private static final Logger LOG = LoggerFactory.getLogger(OrdersRest.class.getName());

    @Inject
    OrderDao orderDao;

    @Inject
    FIXOrderService fixOrderService;

    @GET
    @Operation(summary = "Get all orders", description = "Retrieve a list of all orders")
    @APIResponse(responseCode = "200", description = "List of orders", content = @Content(schema = @Schema(implementation = OrderEntity.class)))
    public List<OrderEntity> getOrders() {
        LOG.info("GET /orders");
        return orderDao.findAll();
    }

    @POST
    @Operation(summary = "Create a new order", description = "Create and persist a new order, then send via FIX")
    @APIResponse(responseCode = "201", description = "Order created", content = @Content(schema = @Schema(implementation = OrderEntity.class)))
    public Response createOrder(OrderEntity order) {
        // Generate clOrdId if not provided
        if (order.getClOrdId() == null || order.getClOrdId().isEmpty()) {
            order.setClOrdId("ORD-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 10000));
        }
        LOG.info("POST /orders - Creating order: {}", order.getClOrdId());
        
        // Set initial status
        order.setStatus("NEW");
        orderDao.persistOrder(order);
        
        // Send via FIX
        try {
            fixOrderService.sendNewOrder(order);
            return Response.status(Response.Status.CREATED).entity(order).build();
        } catch (Exception e) {
            LOG.error("Failed to send order via FIX", e);
            // Order is saved but FIX send failed - status should be REJECTED
            return Response.status(Response.Status.ACCEPTED).entity(order).build();
        }
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
    @Operation(summary = "Cancel an order", description = "Cancel an existing order via FIX protocol")
    @APIResponse(responseCode = "200", description = "Order cancelled")
    @APIResponse(responseCode = "404", description = "Order not found")
    public Response cancelOrder(@PathParam("clOrdId") String clOrdId) {
        LOG.info("POST /orders/{}/cancel", clOrdId);
        OrderEntity order = orderDao.findByClOrdId(clOrdId);
        if (order != null) {
            try {
                fixOrderService.sendCancelOrder(order);
                LOG.info("Cancel request sent for order: {}", clOrdId);
                return Response.ok(order).build();
            } catch (Exception e) {
                LOG.error("Failed to send cancel via FIX", e);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Failed to send cancel request: " + e.getMessage()).build();
            }
        } else {
            LOG.warn("Order not found for cancellation: {}", clOrdId);
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }
    
    @DELETE
    @Path("/{clOrdId}")
    @Operation(summary = "Delete/Cancel an order", description = "Cancel an order via DELETE method using FIX")
    @APIResponse(responseCode = "204", description = "Order cancelled")
    @APIResponse(responseCode = "404", description = "Order not found")
    public Response deleteOrder(@PathParam("clOrdId") String clOrdId) {
        LOG.info("DELETE /orders/{}", clOrdId);
        OrderEntity order = orderDao.findByClOrdId(clOrdId);
        if (order != null) {
            try {
                fixOrderService.sendCancelOrder(order);
                LOG.info("Cancel request sent via DELETE for: {}", clOrdId);
                return Response.noContent().build();
            } catch (Exception e) {
                LOG.error("Failed to send cancel via FIX", e);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }
        } else {
            LOG.warn("Order not found for deletion: {}", clOrdId);
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }
}
