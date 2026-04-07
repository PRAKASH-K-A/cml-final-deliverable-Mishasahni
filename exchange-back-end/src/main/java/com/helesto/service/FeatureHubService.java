package com.helesto.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class FeatureHubService {

    private final ConcurrentMap<String, Set<String>> watchlists = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PriceAlert> alerts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<OrderPreset>> userPresets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<StrategyTemplate>> userStrategies = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<TradeNote>> symbolNotes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> orderTags = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> userRiskProfiles = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UserDashboardConfig> userDashboards = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<PaperTrade>> paperTrades = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<UserNotification>> userNotifications = new ConcurrentHashMap<>();

    private final AtomicLong alertSequence = new AtomicLong(1000);
    private final AtomicLong presetSequence = new AtomicLong(1000);
    private final AtomicLong strategySequence = new AtomicLong(1000);
    private final AtomicLong noteSequence = new AtomicLong(1000);
    private final AtomicLong paperTradeSequence = new AtomicLong(1000);
    private final AtomicLong notificationSequence = new AtomicLong(1000);

    public Set<String> createWatchlist(String watchlistName) {
        String key = normalizeKey(watchlistName);
        watchlists.putIfAbsent(key, ConcurrentHashMap.newKeySet());
        return Collections.unmodifiableSet(watchlists.get(key));
    }

    public Set<String> addWatchlistSymbol(String watchlistName, String symbol) {
        String key = normalizeKey(watchlistName);
        watchlists.putIfAbsent(key, ConcurrentHashMap.newKeySet());
        watchlists.get(key).add(normalizeSymbol(symbol));
        return Collections.unmodifiableSet(watchlists.get(key));
    }

    public Set<String> removeWatchlistSymbol(String watchlistName, String symbol) {
        String key = normalizeKey(watchlistName);
        watchlists.putIfAbsent(key, ConcurrentHashMap.newKeySet());
        watchlists.get(key).remove(normalizeSymbol(symbol));
        return Collections.unmodifiableSet(watchlists.get(key));
    }

    public Set<String> getWatchlist(String watchlistName) {
        String key = normalizeKey(watchlistName);
        Set<String> result = watchlists.getOrDefault(key, Collections.emptySet());
        return Collections.unmodifiableSet(result);
    }

    public PriceAlert createAlert(String userId, String symbol, double thresholdPrice, String condition) {
        String id = "ALRT-" + alertSequence.incrementAndGet();
        PriceAlert alert = new PriceAlert();
        alert.id = id;
        alert.userId = normalizeKey(userId);
        alert.symbol = normalizeSymbol(symbol);
        alert.thresholdPrice = thresholdPrice;
        alert.condition = normalizeCondition(condition);
        alert.acknowledged = false;
        alert.createdAt = Instant.now().toEpochMilli();
        alerts.put(id, alert);
        return alert;
    }

    public List<PriceAlert> listAlerts() {
        return new ArrayList<>(alerts.values());
    }

    public List<PriceAlert> listAlertsByUser(String userId) {
        String userKey = normalizeKey(userId);
        List<PriceAlert> userAlerts = new ArrayList<>();
        for (PriceAlert alert : alerts.values()) {
            if (userKey.equals(alert.userId)) {
                userAlerts.add(alert);
            }
        }
        return userAlerts;
    }

    public PriceAlert acknowledgeAlert(String alertId) {
        PriceAlert alert = alerts.get(alertId);
        if (alert != null) {
            alert.acknowledged = true;
            alert.acknowledgedAt = Instant.now().toEpochMilli();
        }
        return alert;
    }

    public boolean deleteAlert(String alertId) {
        return alerts.remove(alertId) != null;
    }

    public OrderPreset saveOrderPreset(String userId, String name, String symbol, String side,
                                       long quantity, double price, String orderType) {
        String id = "PRST-" + presetSequence.incrementAndGet();
        OrderPreset preset = new OrderPreset();
        preset.id = id;
        preset.userId = normalizeKey(userId);
        preset.name = safe(name);
        preset.symbol = normalizeSymbol(symbol);
        preset.side = safe(side);
        preset.quantity = quantity;
        preset.price = price;
        preset.orderType = safe(orderType);
        preset.createdAt = Instant.now().toEpochMilli();

        userPresets.computeIfAbsent(preset.userId, value -> new CopyOnWriteArrayList<>()).add(preset);
        return preset;
    }

    public List<OrderPreset> listOrderPresets(String userId) {
        return new ArrayList<>(userPresets.getOrDefault(normalizeKey(userId), Collections.emptyList()));
    }

    public StrategyTemplate saveStrategyTemplate(String userId, String name, String description,
                                                 List<String> symbols, String riskProfile) {
        String id = "STRAT-" + strategySequence.incrementAndGet();
        StrategyTemplate strategy = new StrategyTemplate();
        strategy.id = id;
        strategy.userId = normalizeKey(userId);
        strategy.name = safe(name);
        strategy.description = safe(description);
        strategy.symbols = symbols != null ? new ArrayList<>(symbols) : new ArrayList<>();
        strategy.riskProfile = safe(riskProfile);
        strategy.createdAt = Instant.now().toEpochMilli();

        userStrategies.computeIfAbsent(strategy.userId, value -> new CopyOnWriteArrayList<>()).add(strategy);
        return strategy;
    }

    public List<StrategyTemplate> listStrategyTemplates(String userId) {
        return new ArrayList<>(userStrategies.getOrDefault(normalizeKey(userId), Collections.emptyList()));
    }

    public TradeNote addTradeNote(String symbol, String author, String noteText, String sentiment) {
        String id = "NOTE-" + noteSequence.incrementAndGet();
        TradeNote note = new TradeNote();
        note.id = id;
        note.symbol = normalizeSymbol(symbol);
        note.author = safe(author);
        note.noteText = safe(noteText);
        note.sentiment = safe(sentiment);
        note.createdAt = Instant.now().toEpochMilli();

        symbolNotes.computeIfAbsent(note.symbol, value -> new CopyOnWriteArrayList<>()).add(note);
        return note;
    }

    public List<TradeNote> getTradeNotes(String symbol) {
        return new ArrayList<>(symbolNotes.getOrDefault(normalizeSymbol(symbol), Collections.emptyList()));
    }

    public Set<String> addOrderTag(String orderRefNumber, String tag) {
        String orderRef = normalizeKey(orderRefNumber);
        orderTags.putIfAbsent(orderRef, ConcurrentHashMap.newKeySet());
        orderTags.get(orderRef).add(normalizeKey(tag));
        return Collections.unmodifiableSet(orderTags.get(orderRef));
    }

    public Set<String> listOrderTags(String orderRefNumber) {
        return Collections.unmodifiableSet(orderTags.getOrDefault(normalizeKey(orderRefNumber), Collections.emptySet()));
    }

    public List<String> findOrdersByTag(String tag) {
        String normalizedTag = normalizeKey(tag);
        List<String> orderRefs = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : orderTags.entrySet()) {
            if (entry.getValue().contains(normalizedTag)) {
                orderRefs.add(entry.getKey());
            }
        }
        return orderRefs;
    }

    public UserDashboardConfig saveDashboardConfig(String userId, List<String> widgets, String defaultSymbol) {
        String userKey = normalizeKey(userId);
        UserDashboardConfig config = new UserDashboardConfig();
        config.userId = userKey;
        config.widgets = widgets != null ? new ArrayList<>(widgets) : new ArrayList<>();
        config.defaultSymbol = normalizeSymbol(defaultSymbol);
        config.updatedAt = Instant.now().toEpochMilli();
        userDashboards.put(userKey, config);
        return config;
    }

    public UserDashboardConfig getDashboardConfig(String userId) {
        String userKey = normalizeKey(userId);
        UserDashboardConfig config = userDashboards.get(userKey);
        if (config == null) {
            config = new UserDashboardConfig();
            config.userId = userKey;
            config.widgets = new ArrayList<>();
            config.defaultSymbol = "AAPL";
            config.updatedAt = Instant.now().toEpochMilli();
        }
        return config;
    }

    public String setRiskProfile(String userId, String riskProfile) {
        String normalized = safe(riskProfile).trim().toUpperCase();
        if (!"LOW".equals(normalized) && !"MEDIUM".equals(normalized) && !"HIGH".equals(normalized)) {
            normalized = "MEDIUM";
        }
        userRiskProfiles.put(normalizeKey(userId), normalized);
        return normalized;
    }

    public String getRiskProfile(String userId) {
        return userRiskProfiles.getOrDefault(normalizeKey(userId), "MEDIUM");
    }

    public PaperTrade recordPaperTrade(String userId, String symbol, String side, long quantity, double price) {
        String id = "PTRD-" + paperTradeSequence.incrementAndGet();
        PaperTrade trade = new PaperTrade();
        trade.id = id;
        trade.userId = normalizeKey(userId);
        trade.symbol = normalizeSymbol(symbol);
        trade.side = safe(side).toUpperCase();
        trade.quantity = Math.max(0, quantity);
        trade.price = price;
        trade.notional = trade.quantity * trade.price;
        trade.createdAt = Instant.now().toEpochMilli();
        paperTrades.computeIfAbsent(trade.userId, value -> new CopyOnWriteArrayList<>()).add(trade);
        return trade;
    }

    public List<PaperTrade> getPaperTrades(String userId) {
        return new ArrayList<>(paperTrades.getOrDefault(normalizeKey(userId), Collections.emptyList()));
    }

    public PaperPortfolioSummary getPaperPortfolioSummary(String userId) {
        String userKey = normalizeKey(userId);
        List<PaperTrade> trades = paperTrades.getOrDefault(userKey, Collections.emptyList());
        Map<String, PortfolioPosition> positions = new HashMap<>();
        double grossBuyNotional = 0.0;
        double grossSellNotional = 0.0;

        for (PaperTrade trade : trades) {
            PortfolioPosition position = positions.computeIfAbsent(trade.symbol, key -> {
                PortfolioPosition p = new PortfolioPosition();
                p.symbol = key;
                p.netQuantity = 0L;
                p.averagePrice = 0.0;
                p.notional = 0.0;
                return p;
            });

            long signedQty = "SELL".equals(trade.side) ? -trade.quantity : trade.quantity;
            long previousQty = position.netQuantity;
            position.netQuantity += signedQty;

            if ("BUY".equals(trade.side)) {
                grossBuyNotional += trade.notional;
            } else {
                grossSellNotional += trade.notional;
            }

            if (position.netQuantity != 0) {
                double previousNotional = position.averagePrice * Math.abs(previousQty);
                double newNotional = previousNotional + trade.notional;
                position.averagePrice = newNotional / Math.abs(position.netQuantity);
                position.notional = position.averagePrice * Math.abs(position.netQuantity);
            }
        }

        PaperPortfolioSummary summary = new PaperPortfolioSummary();
        summary.userId = userKey;
        summary.positions = new ArrayList<>(positions.values());
        summary.totalTrades = trades.size();
        summary.grossBuyNotional = grossBuyNotional;
        summary.grossSellNotional = grossSellNotional;
        summary.netExposure = grossBuyNotional - grossSellNotional;
        return summary;
    }

    public int resetPaperPortfolio(String userId) {
        List<PaperTrade> removed = paperTrades.remove(normalizeKey(userId));
        return removed != null ? removed.size() : 0;
    }

    public UserNotification createNotification(String userId, String type, String message) {
        String id = "NTF-" + notificationSequence.incrementAndGet();
        UserNotification notification = new UserNotification();
        notification.id = id;
        notification.userId = normalizeKey(userId);
        notification.type = safe(type).toUpperCase();
        notification.message = safe(message);
        notification.read = false;
        notification.createdAt = Instant.now().toEpochMilli();
        userNotifications.computeIfAbsent(notification.userId, value -> new CopyOnWriteArrayList<>()).add(notification);
        return notification;
    }

    public List<UserNotification> listNotifications(String userId, boolean unreadOnly) {
        List<UserNotification> notifications = new ArrayList<>(
                userNotifications.getOrDefault(normalizeKey(userId), Collections.emptyList()));
        if (!unreadOnly) {
            return notifications;
        }
        List<UserNotification> unread = new ArrayList<>();
        for (UserNotification notification : notifications) {
            if (!notification.read) {
                unread.add(notification);
            }
        }
        return unread;
    }

    public UserNotification markNotificationRead(String notificationId) {
        for (List<UserNotification> notifications : userNotifications.values()) {
            for (UserNotification notification : notifications) {
                if (notification.id.equals(notificationId)) {
                    notification.read = true;
                    notification.readAt = Instant.now().toEpochMilli();
                    return notification;
                }
            }
        }
        return null;
    }

    public int clearNotifications(String userId) {
        List<UserNotification> removed = userNotifications.remove(normalizeKey(userId));
        return removed != null ? removed.size() : 0;
    }

    public Map<String, Object> getFeatureStats() {
        return Map.of(
                "watchlists", watchlists.size(),
                "alerts", alerts.size(),
                "presets", userPresets.values().stream().mapToInt(List::size).sum(),
                "strategies", userStrategies.values().stream().mapToInt(List::size).sum(),
                "notes", symbolNotes.values().stream().mapToInt(List::size).sum(),
                "taggedOrders", orderTags.size(),
                "riskProfiles", userRiskProfiles.size(),
                "dashboards", userDashboards.size(),
                "paperTradeUsers", paperTrades.size(),
                "notifications", userNotifications.values().stream().mapToInt(List::size).sum());
    }

    private String normalizeKey(String value) {
        return safe(value).trim().toLowerCase();
    }

    private String normalizeSymbol(String symbol) {
        return safe(symbol).trim().toUpperCase();
    }

    private String normalizeCondition(String condition) {
        String normalized = safe(condition).trim().toUpperCase();
        if (!"ABOVE".equals(normalized) && !"BELOW".equals(normalized)) {
            return "ABOVE";
        }
        return normalized;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public static class PriceAlert {
        public String id;
        public String userId;
        public String symbol;
        public double thresholdPrice;
        public String condition;
        public boolean acknowledged;
        public long createdAt;
        public Long acknowledgedAt;
    }

    public static class OrderPreset {
        public String id;
        public String userId;
        public String name;
        public String symbol;
        public String side;
        public long quantity;
        public double price;
        public String orderType;
        public long createdAt;
    }

    public static class StrategyTemplate {
        public String id;
        public String userId;
        public String name;
        public String description;
        public List<String> symbols;
        public String riskProfile;
        public long createdAt;
    }

    public static class TradeNote {
        public String id;
        public String symbol;
        public String author;
        public String noteText;
        public String sentiment;
        public long createdAt;
    }

    public static class UserDashboardConfig {
        public String userId;
        public List<String> widgets;
        public String defaultSymbol;
        public long updatedAt;
    }

    public static class PaperTrade {
        public String id;
        public String userId;
        public String symbol;
        public String side;
        public long quantity;
        public double price;
        public double notional;
        public long createdAt;
    }

    public static class PortfolioPosition {
        public String symbol;
        public long netQuantity;
        public double averagePrice;
        public double notional;
    }

    public static class PaperPortfolioSummary {
        public String userId;
        public int totalTrades;
        public double grossBuyNotional;
        public double grossSellNotional;
        public double netExposure;
        public List<PortfolioPosition> positions;
    }

    public static class UserNotification {
        public String id;
        public String userId;
        public String type;
        public String message;
        public boolean read;
        public long createdAt;
        public Long readAt;
    }
}
