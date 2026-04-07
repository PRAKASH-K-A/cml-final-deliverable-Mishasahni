package com.helesto.service;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.helesto.dao.OrderDao;
import com.helesto.model.OrderEntity;

class FixOrderManagementEngineTest {

    @Test
    void cancelShouldMarkOrderCanceledAndRemoveFromBook() {
        InMemoryOrderDao dao = new InMemoryOrderDao();
        OrderBookManager bookManager = new OrderBookManager();

        OrderEntity order = new OrderEntity();
        order.setClOrdId("C1");
        order.setOrderRefNumber("R1");
        order.setSymbol("AAPL");
        order.setSide("1");
        order.setQuantity(100L);
        order.setFilledQty(0L);
        order.setLeavesQty(100L);
        order.setStatus("NEW");
        dao.put(order);

        OrderBookManager.BookOrder bookOrder = new OrderBookManager.BookOrder();
        bookOrder.orderId = "R1";
        bookOrder.clOrdId = "C1";
        bookOrder.symbol = "AAPL";
        bookOrder.side = "1";
        bookOrder.price = 150.0;
        bookOrder.originalQty = 100;
        bookOrder.leavesQty = 100;
        bookOrder.orderType = "LIMIT";
        bookOrder.timeInForce = "DAY";
        bookManager.addOrder(bookOrder);

        FixOrderManagementEngine engine = new FixOrderManagementEngine();
        setField(engine, "orderDao", dao);
        setField(engine, "orderBookManager", bookManager);

        FixOrderManagementEngine.CancelResult result = engine.cancel("C1");

        Assertions.assertTrue(result.success);
        Assertions.assertEquals("CANCELED", dao.findByClOrdId("C1").getStatus());
        Assertions.assertTrue(bookManager.getBidsAtPrice("AAPL", 150.0).isEmpty());
    }

    @Test
    void replaceShouldApplyNewValuesAndKeepLeavesConsistent() {
        InMemoryOrderDao dao = new InMemoryOrderDao();

        OrderEntity order = new OrderEntity();
        order.setClOrdId("C1");
        order.setOrderRefNumber("R1");
        order.setSymbol("AAPL");
        order.setSide("1");
        order.setQuantity(100L);
        order.setFilledQty(20L);
        order.setLeavesQty(80L);
        order.setPrice(150.0);
        order.setStatus("PARTIALLY_FILLED");
        dao.put(order);

        FixOrderManagementEngine engine = new FixOrderManagementEngine();
        setField(engine, "orderDao", dao);
        setField(engine, "orderBookManager", new OrderBookManager());

        FixOrderManagementEngine.ReplaceResult result = engine.replace("C1", "C2", 120.0, 151.0);

        Assertions.assertTrue(result.success);
        Assertions.assertEquals("C2", result.order.getClOrdId());
        Assertions.assertEquals(120L, result.order.getQuantity());
        Assertions.assertEquals(100L, result.order.getLeavesQty());
        Assertions.assertEquals(151.0, result.order.getPrice());
        Assertions.assertEquals("NEW", result.order.getStatus());
    }

    @Test
    void cancelShouldRejectAlreadyFilledOrder() {
        InMemoryOrderDao dao = new InMemoryOrderDao();

        OrderEntity order = new OrderEntity();
        order.setClOrdId("C1");
        order.setOrderRefNumber("R1");
        order.setSymbol("AAPL");
        order.setStatus("FILLED");
        dao.put(order);

        FixOrderManagementEngine engine = new FixOrderManagementEngine();
        setField(engine, "orderDao", dao);
        setField(engine, "orderBookManager", new OrderBookManager());

        FixOrderManagementEngine.CancelResult result = engine.cancel("C1");

        Assertions.assertFalse(result.success);
        Assertions.assertTrue(result.tooLate);
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static class InMemoryOrderDao extends OrderDao {
        private final Map<String, OrderEntity> byClOrdId = new HashMap<>();
        private final Map<String, OrderEntity> byOrderRef = new HashMap<>();

        void put(OrderEntity order) {
            byClOrdId.put(order.getClOrdId(), order);
            byOrderRef.put(order.getOrderRefNumber(), order);
        }

        @Override
        public OrderEntity findByClOrdId(String clOrdId) {
            return byClOrdId.get(clOrdId);
        }

        @Override
        public OrderEntity findByOrderRefNumber(String orderRefNumber) {
            return byOrderRef.get(orderRefNumber);
        }

        @Override
        public void updateOrder(OrderEntity order) {
            OrderEntity stale = byOrderRef.get(order.getOrderRefNumber());
            if (stale != null && stale.getClOrdId() != null && !stale.getClOrdId().equals(order.getClOrdId())) {
                byClOrdId.remove(stale.getClOrdId());
            }
            byClOrdId.put(order.getClOrdId(), order);
            byOrderRef.put(order.getOrderRefNumber(), order);
        }
    }
}
