package com.helesto.service;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.core.Bootstrap;
import com.helesto.dao.OrderDao;
import com.helesto.model.OrderEntity;

import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.field.ClOrdID;
import quickfix.field.HandlInst;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TimeInForce;
import quickfix.field.TransactTime;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelRequest;

@ApplicationScoped
public class FIXOrderService {

    private static final Logger LOG = LoggerFactory.getLogger(FIXOrderService.class);

    @Inject
    Bootstrap bootstrap;

    @Inject
    OrderDao orderDao;

    public FIXOrderService() {
        LOG.info("FIXOrderService Constructor");
    }

    /**
     * Send a new order via FIX protocol
     */
    public void sendNewOrder(OrderEntity order) throws SessionNotFound {
        LOG.info("Sending NewOrderSingle for order: {}", order.getClOrdId());

        if (!bootstrap.getTrader().isInitiatorStarted()) {
            LOG.error("FIX session not connected. Cannot send order.");
            order.setStatus("REJECTED");
            order.setRejectReason("FIX session not connected");
            orderDao.updateOrder(order);
            throw new RuntimeException("FIX session not connected");
        }

        try {
            SessionID sessionID = bootstrap.getTrader().getSessionIDFromInitiator();
            
            NewOrderSingle newOrderSingle = new NewOrderSingle();
            newOrderSingle.set(new ClOrdID(order.getClOrdId()));
            newOrderSingle.set(new Symbol(order.getSymbol()));
            newOrderSingle.set(new Side(parseSide(order.getSide())));
            newOrderSingle.set(new TransactTime());
            newOrderSingle.set(new OrderQty(order.getQuantity()));
            newOrderSingle.set(new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION));
            
            // Set order type
            if ("MARKET".equalsIgnoreCase(order.getOrderType())) {
                newOrderSingle.set(new OrdType(OrdType.MARKET));
            } else {
                newOrderSingle.set(new OrdType(OrdType.LIMIT));
                newOrderSingle.set(new Price(order.getPrice()));
            }
            
            // Set time in force
            newOrderSingle.set(new TimeInForce(parseTimeInForce(order.getTimeInForce())));

            // Send to exchange
            Session.sendToTarget(newOrderSingle, sessionID);
            
            LOG.info("NewOrderSingle sent successfully for: {}", order.getClOrdId());
            order.setStatus("PENDING_NEW");
            orderDao.updateOrder(order);

        } catch (Exception e) {
            LOG.error("Error sending NewOrderSingle", e);
            order.setStatus("REJECTED");
            order.setRejectReason(e.getMessage());
            orderDao.updateOrder(order);
            throw new RuntimeException("Failed to send order via FIX: " + e.getMessage());
        }
    }

    /**
     * Send an order cancel request via FIX protocol
     */
    public void sendCancelOrder(OrderEntity order) throws SessionNotFound {
        LOG.info("Sending OrderCancelRequest for order: {}", order.getClOrdId());

        if (!bootstrap.getTrader().isInitiatorStarted()) {
            LOG.error("FIX session not connected. Cannot cancel order.");
            throw new RuntimeException("FIX session not connected");
        }

        try {
            SessionID sessionID = bootstrap.getTrader().getSessionIDFromInitiator();
            
            OrderCancelRequest cancelRequest = new OrderCancelRequest();
            cancelRequest.set(new OrigClOrdID(order.getClOrdId()));
            cancelRequest.set(new ClOrdID("CXLREQ-" + order.getClOrdId()));
            cancelRequest.set(new Symbol(order.getSymbol()));
            cancelRequest.set(new Side(parseSide(order.getSide())));
            cancelRequest.set(new TransactTime());
            cancelRequest.set(new OrderQty(order.getQuantity()));

            // Send to exchange
            Session.sendToTarget(cancelRequest, sessionID);
            
            LOG.info("OrderCancelRequest sent successfully for: {}", order.getClOrdId());
            order.setStatus("PENDING_CANCEL");
            orderDao.updateOrder(order);

        } catch (Exception e) {
            LOG.error("Error sending OrderCancelRequest", e);
            throw new RuntimeException("Failed to send cancel request via FIX: " + e.getMessage());
        }
    }

    private char parseSide(String side) {
        if ("1".equals(side) || "BUY".equalsIgnoreCase(side)) {
            return Side.BUY;
        } else if ("2".equals(side) || "SELL".equalsIgnoreCase(side)) {
            return Side.SELL;
        }
        return Side.BUY; // default
    }

    private char parseTimeInForce(String tif) {
        if (tif == null) return TimeInForce.DAY;
        switch (tif.toUpperCase()) {
            case "GTC": return TimeInForce.GOOD_TILL_CANCEL;
            case "IOC": return TimeInForce.IMMEDIATE_OR_CANCEL;
            case "FOK": return TimeInForce.FILL_OR_KILL;
            default: return TimeInForce.DAY;
        }
    }
}
