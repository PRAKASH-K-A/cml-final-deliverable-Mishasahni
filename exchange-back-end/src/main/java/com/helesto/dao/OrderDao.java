package com.helesto.dao;

import java.util.List;

import javax.enterprise.context.ApplicationScoped;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.transaction.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.model.OrderEntity;

@ApplicationScoped
public class OrderDao {

    private static final Logger LOG = LoggerFactory.getLogger(OrderDao.class.getName());

    @PersistenceContext
    EntityManager em;

    @Transactional
    public void persistOrder(OrderEntity order) {
        LOG.debug("Persisting order: {}", order.getClOrdId());
        em.persist(order);
    }

    @Transactional
    public void updateOrder(OrderEntity order) {
        LOG.debug("Updating order: {}", order.getClOrdId());
        em.merge(order);
    }

    @Transactional
    public OrderEntity findByClOrdId(String clOrdId) {
        LOG.info("Finding order by ClOrdId: {}", clOrdId);
        return em.createQuery("SELECT o FROM OrderEntity o WHERE o.clOrdId = :clOrdId", OrderEntity.class)
                .setParameter("clOrdId", clOrdId)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    @Transactional
    public OrderEntity findByOrderRefNumber(String orderRefNumber) {
        LOG.info("Finding order by OrderRefNumber: {}", orderRefNumber);
        return em.createQuery("SELECT o FROM OrderEntity o WHERE o.orderRefNumber = :orderRefNumber", OrderEntity.class)
                .setParameter("orderRefNumber", orderRefNumber)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    public List<OrderEntity> findAll() {
        LOG.debug("Finding all orders");
        return em.createQuery("SELECT o FROM OrderEntity o ORDER BY o.createdAt DESC", OrderEntity.class)
                .getResultList();
    }

    public List<OrderEntity> findBySymbol(String symbol) {
        LOG.debug("Finding orders by symbol: {}", symbol);
        return em.createQuery("SELECT o FROM OrderEntity o WHERE o.symbol = :symbol ORDER BY o.createdAt DESC", OrderEntity.class)
                .setParameter("symbol", symbol)
                .getResultList();
    }

    public List<OrderEntity> findByStatus(String status) {
        LOG.debug("Finding orders by status: {}", status);
        return em.createQuery("SELECT o FROM OrderEntity o WHERE o.status = :status ORDER BY o.createdAt DESC", OrderEntity.class)
                .setParameter("status", status)
                .getResultList();
    }

    public List<OrderEntity> findByClientId(String clientId) {
        LOG.debug("Finding orders by clientId: {}", clientId);
        return em.createQuery("SELECT o FROM OrderEntity o WHERE o.clientId = :clientId ORDER BY o.createdAt DESC", OrderEntity.class)
                .setParameter("clientId", clientId)
                .getResultList();
    }

    @Transactional
    public void update(OrderEntity order) {
        LOG.info("Updating order: {}", order.getOrderRefNumber());
        em.merge(order);
    }
    
    // ================== G1-M4: Batch Operations ==================
    
    /**
     * G1-M4: Batch insert orders for high-volume scenarios
     * - Flush and clear in configurable batches
     * - Optimal for bulk order submission
     * 
     * @param orders List of orders to persist
     * @param batchSize Number of inserts before flush/clear
     * @return Number of orders successfully persisted
     */
    @Transactional
    public int batchPersistOrders(List<OrderEntity> orders, int batchSize) {
        if (orders == null || orders.isEmpty()) {
            return 0;
        }
        
        LOG.info("Batch persisting {} orders with batch size {}", orders.size(), batchSize);
        int count = 0;
        
        for (int i = 0; i < orders.size(); i++) {
            em.persist(orders.get(i));
            count++;
            
            // Flush and clear every batchSize inserts
            if ((i + 1) % batchSize == 0) {
                em.flush();
                em.clear();
                LOG.debug("Flushed and cleared at index {}", i + 1);
            }
        }
        
        // Final flush for remaining entities
        em.flush();
        em.clear();
        
        LOG.info("Successfully batch persisted {} orders", count);
        return count;
    }
    
    /**
     * Batch insert with default batch size of 50
     */
    @Transactional
    public int batchPersistOrders(List<OrderEntity> orders) {
        return batchPersistOrders(orders, 50);
    }
    
    /**
     * Batch update orders for mass status changes
     */
    @Transactional
    public int batchUpdateOrders(List<OrderEntity> orders, int batchSize) {
        if (orders == null || orders.isEmpty()) {
            return 0;
        }
        
        LOG.info("Batch updating {} orders with batch size {}", orders.size(), batchSize);
        int count = 0;
        
        for (int i = 0; i < orders.size(); i++) {
            em.merge(orders.get(i));
            count++;
            
            if ((i + 1) % batchSize == 0) {
                em.flush();
                em.clear();
            }
        }
        
        em.flush();
        em.clear();
        
        LOG.info("Successfully batch updated {} orders", count);
        return count;
    }
    
    /**
     * Bulk update status by IDs (efficient for cancellation workflows)
     */
    @Transactional
    public int bulkUpdateStatus(List<Long> orderIds, String newStatus) {
        if (orderIds == null || orderIds.isEmpty()) {
            return 0;
        }
        
        LOG.info("Bulk updating status to {} for {} orders", newStatus, orderIds.size());
        
        int updated = em.createQuery(
            "UPDATE OrderEntity o SET o.status = :status WHERE o.id IN :ids")
            .setParameter("status", newStatus)
            .setParameter("ids", orderIds)
            .executeUpdate();
        
        LOG.info("Bulk updated {} orders to status {}", updated, newStatus);
        return updated;
    }
    
    /**
     * Bulk delete old orders (data retention)
     */
    @Transactional
    public int bulkDeleteOlderThan(java.time.LocalDateTime cutoffTime) {
        LOG.info("Bulk deleting orders older than {}", cutoffTime);
        
        int deleted = em.createQuery(
            "DELETE FROM OrderEntity o WHERE o.createdAt < :cutoff")
            .setParameter("cutoff", cutoffTime)
            .executeUpdate();
        
        LOG.info("Bulk deleted {} old orders", deleted);
        return deleted;
    }
}
