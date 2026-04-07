package com.helesto.model;

import java.time.LocalDateTime;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "orders")
public class OrderEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String orderRefNumber;
    private String clOrdId;
    private String symbol;
    private String side;
    private Long quantity;
    private Double price;
    private String orderType;
    private String timeInForce;
    private String status;
    private String clientId;
    private Long filledQty;
    private Long leavesQty;
    private Double avgPrice;
    private String rejectReason;
    private String previousStatus;
    private Long userId;
    private String accountId;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getOrderRefNumber() {
        return orderRefNumber;
    }
    
    public void setOrderRefNumber(String orderRefNumber) {
        this.orderRefNumber = orderRefNumber;
    }
    
    public String getClOrdId() {
        return clOrdId;
    }
    
    public void setClOrdId(String clOrdId) {
        this.clOrdId = clOrdId;
    }
    
    public String getSymbol() {
        return symbol;
    }
    
    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }
    
    public String getSide() {
        return side;
    }
    
    public void setSide(String side) {
        this.side = side;
    }
    
    public Long getQuantity() {
        return quantity;
    }
    
    public void setQuantity(Long quantity) {
        this.quantity = quantity;
    }
    
    public Double getPrice() {
        return price;
    }
    
    public void setPrice(Double price) {
        this.price = price;
    }
    
    public String getOrderType() {
        return orderType;
    }
    
    public void setOrderType(String orderType) {
        this.orderType = orderType;
    }
    
    public String getTimeInForce() {
        return timeInForce;
    }
    
    public void setTimeInForce(String timeInForce) {
        this.timeInForce = timeInForce;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public String getClientId() {
        return clientId;
    }
    
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }
    
    public Long getFilledQty() {
        return filledQty;
    }
    
    public void setFilledQty(Long filledQty) {
        this.filledQty = filledQty;
    }
    
    public Long getLeavesQty() {
        return leavesQty;
    }
    
    public void setLeavesQty(Long leavesQty) {
        this.leavesQty = leavesQty;
    }
    
    public Double getAvgPrice() {
        return avgPrice;
    }
    
    public void setAvgPrice(Double avgPrice) {
        this.avgPrice = avgPrice;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    public String getRejectReason() {
        return rejectReason;
    }
    
    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }
    
    public String getPreviousStatus() {
        return previousStatus;
    }
    
    public void setPreviousStatus(String previousStatus) {
        this.previousStatus = previousStatus;
    }
    
    public Long getUserId() {
        return userId;
    }
    
    public void setUserId(Long userId) {
        this.userId = userId;
    }
    
    public String getAccountId() {
        return accountId;
    }
    
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }
}
