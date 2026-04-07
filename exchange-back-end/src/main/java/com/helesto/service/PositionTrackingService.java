package com.helesto.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.helesto.model.TradeEntity;

/**
 * Position Tracking Service
 * - Real-time position management per client/symbol
 * - P&L calculation (realized and unrealized)
 * - Cost basis tracking (FIFO, LIFO, average cost)
 * - Position history and audit trail
 * - Mark-to-market valuations
 */
@ApplicationScoped
public class PositionTrackingService {

    private static final Logger LOG = LoggerFactory.getLogger(PositionTrackingService.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    @Inject
    ReferenceDataService referenceDataService;
    
    @Inject
    TradeService tradeService;
    
    @Inject
    TelemetryService telemetryService;
    
    // Positions by client ID and symbol
    private final Map<String, Map<String, Position>> clientPositions = new ConcurrentHashMap<>();
    
    // Aggregate positions by symbol (firm-wide)
    private final Map<String, Position> firmPositions = new ConcurrentHashMap<>();
    
    // P&L tracking
    private final Map<String, ClientPnL> clientPnL = new ConcurrentHashMap<>();
    private final FirmPnL firmPnL = new FirmPnL();
    
    // Position change listeners
    private final List<Consumer<PositionChange>> positionListeners = new ArrayList<>();
    
    // Cost basis method
    private CostBasisMethod costBasisMethod = CostBasisMethod.AVERAGE;
    
    @PostConstruct
    public void init() {
        LOG.info("Initializing Position Tracking Service...");
        
        // Register for trade updates
        tradeService.addTradeListener(this::onTrade);
        
        LOG.info("Position Tracking Service initialized");
    }
    
    // ==================== Trade Processing ====================
    
    /**
     * Process a trade and update positions
     */
    public void onTrade(TradeEntity trade) {
        try {
            String symbol = trade.getSymbol();
            long quantity = trade.getQuantity();
            double price = trade.getPrice();
            
            // Update buyer position
            if (trade.getBuyClientId() != null) {
                updatePosition(trade.getBuyClientId(), symbol, quantity, price, true);
            }
            
            // Update seller position
            if (trade.getSellClientId() != null) {
                updatePosition(trade.getSellClientId(), symbol, -quantity, price, true);
            }
            
            // Update firm aggregate
            updateFirmPosition(symbol, trade);
            
            LOG.debug("Position updated for trade {}: {} {} @ {}", 
                    trade.getTradeId(), symbol, quantity, price);
            
        } catch (Exception e) {
            LOG.error("Error processing trade for positions: {}", e.getMessage());
            telemetryService.recordError();
        }
    }
    
    /**
     * Record a trade directly with individual parameters (for orchestrator use)
     */
    public void recordTrade(String clientId, String symbol, String side, int quantity, double price, String tradeId) {
        try {
            long signedQuantity = "1".equals(side) ? quantity : -quantity; // 1=Buy, 2=Sell
            updatePosition(clientId, symbol, signedQuantity, price, true);
            LOG.debug("Position recorded for client {}: {} {} @ {} (trade {})", 
                    clientId, symbol, signedQuantity, price, tradeId);
        } catch (Exception e) {
            LOG.error("Error recording trade for positions: clientId={}, symbol={}", clientId, symbol, e);
        }
    }
    
    private void updatePosition(String clientId, String symbol, long quantityChange, 
                                double price, boolean notifyListeners) {
        // Get or create client position map
        Map<String, Position> positions = clientPositions.computeIfAbsent(
                clientId, k -> new ConcurrentHashMap<>());
        
        // Get or create position
        Position position = positions.computeIfAbsent(symbol, k -> {
            Position p = new Position();
            p.clientId = clientId;
            p.symbol = symbol;
            p.openDate = LocalDate.now().format(DATE_FORMAT);
            return p;
        });
        
        // Track position change for P&L calculation
        long previousQty = position.quantity;
        double previousCost = position.totalCost;
        
        synchronized (position) {
            if (quantityChange > 0) {
                // Buying - add to position
                position.totalCost += quantityChange * price;
                position.quantity += quantityChange;
                position.todayBuyQty += quantityChange;
                position.todayBuyValue += quantityChange * price;
                
                // Add to lot queue for FIFO/LIFO
                position.lots.add(new TradeLot(quantityChange, price, LocalDateTime.now()));
                
            } else {
                // Selling - reduce position
                long sellQty = Math.abs(quantityChange);
                position.quantity -= sellQty;
                position.todaySellQty += sellQty;
                position.todaySellValue += sellQty * price;
                
                // Calculate realized P&L based on cost basis method
                RealizedPnLResult pnlResult = calculateRealizedPnL(position, sellQty, price);
                position.realizedPnL += pnlResult.realizedPnL;
                position.totalCost -= pnlResult.costBasis;
                
                // Update client P&L
                updateClientPnL(clientId, symbol, pnlResult.realizedPnL);
            }
            
            // Update average cost if position remains
            if (position.quantity > 0) {
                position.averageCost = position.totalCost / position.quantity;
            } else if (position.quantity == 0) {
                position.averageCost = 0;
                position.totalCost = 0;
            }
            
            // Update market value and unrealized P&L
            ReferenceDataService.MarketData md = referenceDataService.getMarketData(symbol);
            if (md != null) {
                position.marketPrice = md.lastPrice;
                position.marketValue = position.quantity * md.lastPrice;
                position.unrealizedPnL = position.marketValue - position.totalCost;
            }
            
            position.lastUpdateTime = LocalDateTime.now();
        }
        
        // Notify listeners
        if (notifyListeners) {
            PositionChange change = new PositionChange();
            change.clientId = clientId;
            change.symbol = symbol;
            change.previousQty = previousQty;
            change.newQty = position.quantity;
            change.tradePrice = price;
            change.averageCost = position.averageCost;
            change.realizedPnL = position.realizedPnL;
            change.unrealizedPnL = position.unrealizedPnL;
            change.timestamp = LocalDateTime.now();
            notifyPositionListeners(change);
        }
    }
    
    private RealizedPnLResult calculateRealizedPnL(Position position, long sellQty, double sellPrice) {
        RealizedPnLResult result = new RealizedPnLResult();
        
        switch (costBasisMethod) {
            case FIFO:
                result = calculateFIFOPnL(position, sellQty, sellPrice);
                break;
            case LIFO:
                result = calculateLIFOPnL(position, sellQty, sellPrice);
                break;
            case AVERAGE:
            default:
                result = calculateAveragePnL(position, sellQty, sellPrice);
                break;
        }
        
        return result;
    }
    
    private RealizedPnLResult calculateFIFOPnL(Position position, long sellQty, double sellPrice) {
        RealizedPnLResult result = new RealizedPnLResult();
        long remainingQty = sellQty;
        
        while (remainingQty > 0 && !position.lots.isEmpty()) {
            TradeLot lot = position.lots.peek();
            long lotQtyToUse = Math.min(remainingQty, lot.quantity);
            
            result.costBasis += lotQtyToUse * lot.price;
            result.realizedPnL += lotQtyToUse * (sellPrice - lot.price);
            
            lot.quantity -= lotQtyToUse;
            remainingQty -= lotQtyToUse;
            
            if (lot.quantity <= 0) {
                position.lots.poll();
            }
        }
        
        return result;
    }
    
    private RealizedPnLResult calculateLIFOPnL(Position position, long sellQty, double sellPrice) {
        RealizedPnLResult result = new RealizedPnLResult();
        long remainingQty = sellQty;
        
        // Convert to stack for LIFO
        Deque<TradeLot> stack = new ArrayDeque<>(position.lots);
        position.lots.clear();
        
        while (remainingQty > 0 && !stack.isEmpty()) {
            TradeLot lot = stack.pollLast();
            long lotQtyToUse = Math.min(remainingQty, lot.quantity);
            
            result.costBasis += lotQtyToUse * lot.price;
            result.realizedPnL += lotQtyToUse * (sellPrice - lot.price);
            
            lot.quantity -= lotQtyToUse;
            remainingQty -= lotQtyToUse;
            
            if (lot.quantity > 0) {
                stack.addLast(lot);
            }
        }
        
        // Restore remaining lots
        position.lots.addAll(stack);
        
        return result;
    }
    
    private RealizedPnLResult calculateAveragePnL(Position position, long sellQty, double sellPrice) {
        RealizedPnLResult result = new RealizedPnLResult();
        
        if (position.quantity > 0 && position.averageCost > 0) {
            result.costBasis = sellQty * position.averageCost;
            result.realizedPnL = sellQty * (sellPrice - position.averageCost);
        }
        
        return result;
    }
    
    private void updateFirmPosition(String symbol, TradeEntity trade) {
        Position position = firmPositions.computeIfAbsent(symbol, k -> {
            Position p = new Position();
            p.clientId = "FIRM";
            p.symbol = symbol;
            p.openDate = LocalDate.now().format(DATE_FORMAT);
            return p;
        });
        
        synchronized (position) {
            position.todayVolume += trade.getQuantity();
            position.todayTrades++;
            position.todayNotional += trade.getQuantity() * trade.getPrice();
            position.lastTradePrice = trade.getPrice();
            position.lastUpdateTime = LocalDateTime.now();
        }
    }
    
    private void updateClientPnL(String clientId, String symbol, double realizedPnL) {
        ClientPnL pnl = clientPnL.computeIfAbsent(clientId, k -> new ClientPnL(clientId));
        
        synchronized (pnl) {
            pnl.dailyRealizedPnL += realizedPnL;
            pnl.totalRealizedPnL += realizedPnL;
            pnl.symbolPnL.merge(symbol, realizedPnL, Double::sum);
            pnl.lastUpdateTime = LocalDateTime.now();
        }
        
        // Update firm P&L
        synchronized (firmPnL) {
            firmPnL.dailyRealizedPnL += realizedPnL;
            firmPnL.totalRealizedPnL += realizedPnL;
        }
    }
    
    // ==================== Position Queries ====================
    
    /**
     * Get position for a client/symbol
     */
    public Position getPosition(String clientId, String symbol) {
        Map<String, Position> positions = clientPositions.get(clientId);
        if (positions != null) {
            return positions.get(symbol);
        }
        return null;
    }
    
    /**
     * Get all positions for a client
     */
    public Map<String, Position> getClientPositions(String clientId) {
        return clientPositions.getOrDefault(clientId, Collections.emptyMap());
    }
    
    /**
     * Get all positions across all clients for a symbol
     */
    public List<Position> getPositionsBySymbol(String symbol) {
        List<Position> positions = new ArrayList<>();
        for (Map<String, Position> clientPos : clientPositions.values()) {
            Position pos = clientPos.get(symbol);
            if (pos != null && pos.quantity != 0) {
                positions.add(pos);
            }
        }
        return positions;
    }
    
    /**
     * Get firm aggregate position for a symbol
     */
    public Position getFirmPosition(String symbol) {
        return firmPositions.get(symbol);
    }
    
    /**
     * Get client P&L summary
     */
    public ClientPnL getClientPnL(String clientId) {
        return clientPnL.get(clientId);
    }
    
    /**
     * Get firm-wide P&L summary
     */
    public FirmPnL getFirmPnL() {
        return firmPnL;
    }
    
    // ==================== Mark-to-Market ====================
    
    /**
     * Mark all positions to market (update unrealized P&L)
     */
    public void markToMarket() {
        LOG.debug("Running mark-to-market...");
        
        for (Map<String, Position> positions : clientPositions.values()) {
            for (Position position : positions.values()) {
                updateMarketValue(position);
            }
        }
        
        // Update client unrealized P&L totals
        for (ClientPnL pnl : clientPnL.values()) {
            double totalUnrealized = 0;
            Map<String, Position> positions = clientPositions.get(pnl.clientId);
            if (positions != null) {
                for (Position pos : positions.values()) {
                    totalUnrealized += pos.unrealizedPnL;
                }
            }
            pnl.totalUnrealizedPnL = totalUnrealized;
        }
        
        LOG.debug("Mark-to-market complete");
    }
    
    private void updateMarketValue(Position position) {
        ReferenceDataService.MarketData md = referenceDataService.getMarketData(position.symbol);
        if (md != null && position.quantity != 0) {
            position.marketPrice = md.lastPrice;
            position.marketValue = position.quantity * md.lastPrice;
            position.unrealizedPnL = position.marketValue - position.totalCost;
        }
    }
    
    // ==================== Portfolio Summary ====================
    
    /**
     * Get portfolio summary for a client
     */
    public PortfolioSummary getPortfolioSummary(String clientId) {
        PortfolioSummary summary = new PortfolioSummary();
        summary.clientId = clientId;
        
        Map<String, Position> positions = clientPositions.get(clientId);
        if (positions != null) {
            for (Position pos : positions.values()) {
                updateMarketValue(pos); // Ensure current values
                
                summary.positionCount++;
                summary.totalMarketValue += pos.marketValue;
                summary.totalCost += pos.totalCost;
                summary.totalUnrealizedPnL += pos.unrealizedPnL;
                summary.totalRealizedPnL += pos.realizedPnL;
                
                // Track by sector if available
                ReferenceDataService.Security security = referenceDataService.getSecurity(pos.symbol);
                if (security != null) {
                    summary.valueBySegment.merge(security.sector, pos.marketValue, Double::sum);
                }
            }
        }
        
        ClientPnL pnl = clientPnL.get(clientId);
        if (pnl != null) {
            summary.dailyRealizedPnL = pnl.dailyRealizedPnL;
        }
        
        summary.totalPnL = summary.totalRealizedPnL + summary.totalUnrealizedPnL;
        summary.timestamp = LocalDateTime.now();
        
        return summary;
    }
    
    /**
     * Get firm-wide portfolio summary
     */
    public PortfolioSummary getFirmPortfolioSummary() {
        PortfolioSummary summary = new PortfolioSummary();
        summary.clientId = "FIRM";
        
        for (Map.Entry<String, Map<String, Position>> entry : clientPositions.entrySet()) {
            for (Position pos : entry.getValue().values()) {
                updateMarketValue(pos);
                
                summary.positionCount++;
                summary.totalMarketValue += pos.marketValue;
                summary.totalCost += pos.totalCost;
                summary.totalUnrealizedPnL += pos.unrealizedPnL;
                summary.totalRealizedPnL += pos.realizedPnL;
            }
        }
        
        summary.dailyRealizedPnL = firmPnL.dailyRealizedPnL;
        summary.totalPnL = summary.totalRealizedPnL + summary.totalUnrealizedPnL;
        summary.timestamp = LocalDateTime.now();
        
        return summary;
    }
    
    // ==================== Listeners ====================
    
    public void addPositionListener(Consumer<PositionChange> listener) {
        positionListeners.add(listener);
    }
    
    public void removePositionListener(Consumer<PositionChange> listener) {
        positionListeners.remove(listener);
    }
    
    private void notifyPositionListeners(PositionChange change) {
        for (Consumer<PositionChange> listener : positionListeners) {
            try {
                listener.accept(change);
            } catch (Exception e) {
                LOG.error("Error notifying position listener: {}", e.getMessage());
            }
        }
    }
    
    // ==================== Daily Reset ====================
    
    /**
     * Reset daily counters (call at start of trading day)
     */
    public void resetDailyCounters() {
        LOG.info("Resetting daily position counters");
        
        for (Map<String, Position> positions : clientPositions.values()) {
            for (Position pos : positions.values()) {
                pos.todayBuyQty = 0;
                pos.todayBuyValue = 0;
                pos.todaySellQty = 0;
                pos.todaySellValue = 0;
            }
        }
        
        for (Position pos : firmPositions.values()) {
            pos.todayVolume = 0;
            pos.todayTrades = 0;
            pos.todayNotional = 0;
        }
        
        for (ClientPnL pnl : clientPnL.values()) {
            pnl.dailyRealizedPnL = 0;
        }
        
        firmPnL.dailyRealizedPnL = 0;
    }
    
    // ==================== Configuration ====================
    
    public void setCostBasisMethod(CostBasisMethod method) {
        this.costBasisMethod = method;
        LOG.info("Cost basis method set to: {}", method);
    }
    
    public CostBasisMethod getCostBasisMethod() {
        return costBasisMethod;
    }
    
    // ==================== Data Classes ====================
    
    public enum CostBasisMethod {
        FIFO,    // First In, First Out
        LIFO,    // Last In, First Out
        AVERAGE  // Weighted Average Cost
    }
    
    public static class Position {
        public String clientId;
        public String symbol;
        public long quantity;
        public double averageCost;
        public double totalCost;
        public double marketPrice;
        public double marketValue;
        public double unrealizedPnL;
        public double realizedPnL;
        public String openDate;
        public LocalDateTime lastUpdateTime;
        
        // Daily tracking
        public long todayBuyQty;
        public double todayBuyValue;
        public long todaySellQty;
        public double todaySellValue;
        public long todayVolume;
        public long todayTrades;
        public double todayNotional;
        public double lastTradePrice;
        
        // Lot tracking for specific identification
        public final Deque<TradeLot> lots = new ArrayDeque<>();
    }
    
    public static class TradeLot {
        public long quantity;
        public final double price;
        public final LocalDateTime acquisitionTime;
        
        public TradeLot(long quantity, double price, LocalDateTime acquisitionTime) {
            this.quantity = quantity;
            this.price = price;
            this.acquisitionTime = acquisitionTime;
        }
    }
    
    public static class PositionChange {
        public String clientId;
        public String symbol;
        public long previousQty;
        public long newQty;
        public double tradePrice;
        public double averageCost;
        public double realizedPnL;
        public double unrealizedPnL;
        public LocalDateTime timestamp;
    }
    
    public static class ClientPnL {
        public final String clientId;
        public double dailyRealizedPnL;
        public double totalRealizedPnL;
        public double totalUnrealizedPnL;
        public final Map<String, Double> symbolPnL = new ConcurrentHashMap<>();
        public LocalDateTime lastUpdateTime;
        
        public ClientPnL(String clientId) {
            this.clientId = clientId;
        }
    }
    
    public static class FirmPnL {
        public double dailyRealizedPnL;
        public double totalRealizedPnL;
        public double totalUnrealizedPnL;
    }
    
    public static class PortfolioSummary {
        public String clientId;
        public int positionCount;
        public double totalMarketValue;
        public double totalCost;
        public double totalUnrealizedPnL;
        public double totalRealizedPnL;
        public double dailyRealizedPnL;
        public double totalPnL;
        public final Map<String, Double> valueBySegment = new HashMap<>();
        public LocalDateTime timestamp;
    }
    
    private static class RealizedPnLResult {
        public double costBasis;
        public double realizedPnL;
    }
}
