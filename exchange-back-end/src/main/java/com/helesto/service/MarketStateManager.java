package com.helesto.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Market State Manager
 * - Manages trading sessions (pre-market, regular, after-hours)
 * - Handles market calendar and trading holidays
 * - Session transitions and notifications
 * - Trading phase management (opening auction, continuous, closing)
 * - Emergency trading halts
 */
@ApplicationScoped
public class MarketStateManager {

    private static final Logger LOG = LoggerFactory.getLogger(MarketStateManager.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId MARKET_TIMEZONE = ZoneId.of("America/New_York");
    
    @Inject
    TelemetryService telemetryService;
    
    @Inject
    RiskManagementService riskManagementService;
    
    @Inject
    CircuitBreakerService circuitBreakerService;
    
    // Current market state
    private volatile MarketState currentState = MarketState.CLOSED;
    private volatile TradingPhase currentPhase = TradingPhase.CLOSED;
    private volatile LocalDateTime stateChangeTime;
    private volatile String stateChangeReason;
    
    // Session configuration
    private MarketSchedule schedule = new MarketSchedule();

    // Manual override window (used for admin/testing force-open/close)
    private volatile LocalDateTime manualOverrideUntil;
    
    // Holiday calendar
    private final Set<LocalDate> holidays = ConcurrentHashMap.newKeySet();
    private final Map<LocalDate, LocalTime> earlyCloses = new ConcurrentHashMap<>();
    
    // State change listeners
    private final List<Consumer<MarketStateChange>> stateListeners = new CopyOnWriteArrayList<>();
    
    // Scheduler for automatic state transitions
    private ScheduledExecutorService scheduler;
    
    // State history
    private final List<MarketStateChange> stateHistory = Collections.synchronizedList(new ArrayList<>());
    
    @PostConstruct
    public void init() {
        LOG.info("Initializing Market State Manager...");
        
        // Initialize holidays for 2024-2026
        initializeHolidays();
        
        // Start scheduler
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "market-state-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        // Check state every 10 seconds
        scheduler.scheduleAtFixedRate(this::checkAndUpdateState, 0, 10, TimeUnit.SECONDS);
        
        LOG.info("Market State Manager initialized");
    }
    
    @PreDestroy
    public void cleanup() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }
    
    private void initializeHolidays() {
        // 2024 NYSE Holidays
        holidays.add(LocalDate.of(2024, 1, 1));   // New Year's Day
        holidays.add(LocalDate.of(2024, 1, 15));  // MLK Day
        holidays.add(LocalDate.of(2024, 2, 19));  // Presidents Day
        holidays.add(LocalDate.of(2024, 3, 29));  // Good Friday
        holidays.add(LocalDate.of(2024, 5, 27));  // Memorial Day
        holidays.add(LocalDate.of(2024, 6, 19));  // Juneteenth
        holidays.add(LocalDate.of(2024, 7, 4));   // Independence Day
        holidays.add(LocalDate.of(2024, 9, 2));   // Labor Day
        holidays.add(LocalDate.of(2024, 11, 28)); // Thanksgiving
        holidays.add(LocalDate.of(2024, 12, 25)); // Christmas
        
        // 2025 NYSE Holidays
        holidays.add(LocalDate.of(2025, 1, 1));   // New Year's Day
        holidays.add(LocalDate.of(2025, 1, 20));  // MLK Day
        holidays.add(LocalDate.of(2025, 2, 17));  // Presidents Day
        holidays.add(LocalDate.of(2025, 4, 18));  // Good Friday
        holidays.add(LocalDate.of(2025, 5, 26));  // Memorial Day
        holidays.add(LocalDate.of(2025, 6, 19));  // Juneteenth
        holidays.add(LocalDate.of(2025, 7, 4));   // Independence Day
        holidays.add(LocalDate.of(2025, 9, 1));   // Labor Day
        holidays.add(LocalDate.of(2025, 11, 27)); // Thanksgiving
        holidays.add(LocalDate.of(2025, 12, 25)); // Christmas
        
        // 2026 NYSE Holidays
        holidays.add(LocalDate.of(2026, 1, 1));   // New Year's Day
        holidays.add(LocalDate.of(2026, 1, 19));  // MLK Day
        holidays.add(LocalDate.of(2026, 2, 16));  // Presidents Day
        holidays.add(LocalDate.of(2026, 4, 3));   // Good Friday
        holidays.add(LocalDate.of(2026, 5, 25));  // Memorial Day
        holidays.add(LocalDate.of(2026, 6, 19));  // Juneteenth
        holidays.add(LocalDate.of(2026, 7, 3));   // Independence Day (observed)
        holidays.add(LocalDate.of(2026, 9, 7));   // Labor Day
        holidays.add(LocalDate.of(2026, 11, 26)); // Thanksgiving
        holidays.add(LocalDate.of(2026, 12, 25)); // Christmas
        
        // Early closes (1:00 PM ET)
        earlyCloses.put(LocalDate.of(2024, 7, 3), LocalTime.of(13, 0));
        earlyCloses.put(LocalDate.of(2024, 11, 29), LocalTime.of(13, 0));
        earlyCloses.put(LocalDate.of(2024, 12, 24), LocalTime.of(13, 0));
        earlyCloses.put(LocalDate.of(2025, 7, 3), LocalTime.of(13, 0));
        earlyCloses.put(LocalDate.of(2025, 11, 28), LocalTime.of(13, 0));
        earlyCloses.put(LocalDate.of(2025, 12, 24), LocalTime.of(13, 0));
    }
    
    // ==================== State Management ====================
    
    private void checkAndUpdateState() {
        try {
            LocalDateTime now = LocalDateTime.now(MARKET_TIMEZONE);

            // Keep forced state while manual override window is active
            if (manualOverrideUntil != null && now.isBefore(manualOverrideUntil)) {
                return;
            }

            // Clear expired override
            if (manualOverrideUntil != null && !now.isBefore(manualOverrideUntil)) {
                manualOverrideUntil = null;
            }

            LocalDate today = now.toLocalDate();
            LocalTime currentTime = now.toLocalTime();
            
            MarketState newState = determineState(today, currentTime);
            TradingPhase newPhase = determinePhase(today, currentTime);
            
            if (newState != currentState || newPhase != currentPhase) {
                transitionState(newState, newPhase, "Scheduled transition");
            }
        } catch (Exception e) {
            LOG.error("Error checking market state: {}", e.getMessage());
        }
    }
    
    private MarketState determineState(LocalDate date, LocalTime time) {
        // Check if holiday or weekend
        if (holidays.contains(date) || date.getDayOfWeek() == DayOfWeek.SATURDAY || 
            date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return MarketState.CLOSED;
        }
        
        // Check for early close
        LocalTime closeTime = earlyCloses.getOrDefault(date, schedule.regularSessionEnd);
        LocalTime afterHoursEnd = earlyCloses.containsKey(date) ? 
            LocalTime.of(17, 0) : schedule.afterHoursEnd;
        
        // Determine state based on time
        if (time.isBefore(schedule.preMarketStart)) {
            return MarketState.CLOSED;
        } else if (time.isBefore(schedule.regularSessionStart)) {
            return MarketState.PRE_MARKET;
        } else if (time.isBefore(closeTime)) {
            return MarketState.OPEN;
        } else if (time.isBefore(afterHoursEnd)) {
            return MarketState.AFTER_HOURS;
        } else {
            return MarketState.CLOSED;
        }
    }
    
    private TradingPhase determinePhase(LocalDate date, LocalTime time) {
        MarketState state = determineState(date, time);
        
        if (state == MarketState.CLOSED) {
            return TradingPhase.CLOSED;
        }
        
        if (state == MarketState.PRE_MARKET) {
            return TradingPhase.PRE_OPEN;
        }
        
        if (state == MarketState.AFTER_HOURS) {
            return TradingPhase.POST_CLOSE;
        }
        
        // During regular session - determine specific phase
        if (time.isBefore(schedule.regularSessionStart.plusMinutes(5))) {
            return TradingPhase.OPENING_AUCTION;
        } else if (time.isAfter(schedule.regularSessionEnd.minusMinutes(10))) {
            return TradingPhase.CLOSING_AUCTION;
        } else {
            return TradingPhase.CONTINUOUS;
        }
    }
    
    private void transitionState(MarketState newState, TradingPhase newPhase, String reason) {
        MarketState oldState = currentState;
        TradingPhase oldPhase = currentPhase;
        
        currentState = newState;
        currentPhase = newPhase;
        stateChangeTime = LocalDateTime.now(MARKET_TIMEZONE);
        stateChangeReason = reason;
        
        LOG.info("Market state transition: {} -> {} (phase: {} -> {}). Reason: {}",
                oldState, newState, oldPhase, newPhase, reason);
        
        // Record state change
        MarketStateChange change = new MarketStateChange();
        change.previousState = oldState;
        change.newState = newState;
        change.previousPhase = oldPhase;
        change.newPhase = newPhase;
        change.reason = reason;
        change.timestamp = stateChangeTime;
        stateHistory.add(change);
        
        // Notify listeners
        notifyStateListeners(change);
        
        // Handle specific transitions
        handleStateTransition(oldState, newState, oldPhase, newPhase);
    }
    
    private void handleStateTransition(MarketState oldState, MarketState newState,
                                       TradingPhase oldPhase, TradingPhase newPhase) {
        // Market opening - reset daily counters
        if (oldState == MarketState.CLOSED && newState == MarketState.PRE_MARKET) {
            LOG.info("Market day starting - resetting daily counters");
            if (riskManagementService != null) {
                riskManagementService.resetDailyCounters();
            }
        }
        
        // Market open - set circuit breaker reference
        if (newPhase == TradingPhase.OPENING_AUCTION && oldPhase != TradingPhase.OPENING_AUCTION) {
            LOG.info("Opening auction starting");
            // Circuit breaker service can set opening prices here
        }
        
        // Regular trading starting
        if (newPhase == TradingPhase.CONTINUOUS && oldPhase == TradingPhase.OPENING_AUCTION) {
            LOG.info("Continuous trading starting");
        }
        
        // Closing auction
        if (newPhase == TradingPhase.CLOSING_AUCTION && oldPhase == TradingPhase.CONTINUOUS) {
            LOG.info("Closing auction starting");
        }
        
        // Market closed
        if (newState == MarketState.CLOSED && oldState != MarketState.CLOSED) {
            LOG.info("Market closed for the day");
        }
    }
    
    // ==================== Manual Controls ====================
    
    /**
     * Force a market state (for emergency or testing)
     */
    public void forceState(MarketState state, TradingPhase phase, String reason) {
        LOG.warn("Force state change to {} / {} - Reason: {}", state, phase, reason);
        // Hold forced state for 30 minutes to support testing/admin workflows
        manualOverrideUntil = LocalDateTime.now(MARKET_TIMEZONE).plusMinutes(30);
        transitionState(state, phase, "FORCED: " + reason);
    }
    
    /**
     * Halt trading (emergency halt)
     */
    public void haltTrading(String reason) {
        LOG.error("Emergency trading halt: {}", reason);
        transitionState(MarketState.HALTED, TradingPhase.HALTED, "EMERGENCY: " + reason);
        
        if (riskManagementService != null) {
            riskManagementService.haltTrading(reason);
        }
    }
    
    /**
     * Resume trading after halt
     */
    public void resumeTrading() {
        if (currentState == MarketState.HALTED) {
            LOG.info("Resuming trading after halt");
            
            // Determine what state we should be in
            LocalDateTime now = LocalDateTime.now(MARKET_TIMEZONE);
            MarketState newState = determineState(now.toLocalDate(), now.toLocalTime());
            TradingPhase newPhase = determinePhase(now.toLocalDate(), now.toLocalTime());
            
            transitionState(newState, newPhase, "Resume after halt");
            
            if (riskManagementService != null) {
                riskManagementService.resumeTrading();
            }
        }
    }
    
    // ==================== Query Methods ====================
    
    public MarketState getCurrentState() {
        return currentState;
    }
    
    public TradingPhase getCurrentPhase() {
        return currentPhase;
    }
    
    public boolean isTradingAllowed() {
        return currentState == MarketState.OPEN || 
               currentState == MarketState.PRE_MARKET || 
               currentState == MarketState.AFTER_HOURS;
    }
    
    public boolean isRegularTradingHours() {
        return currentState == MarketState.OPEN;
    }
    
    public boolean isExtendedHours() {
        return currentState == MarketState.PRE_MARKET || currentState == MarketState.AFTER_HOURS;
    }
    
    public boolean isTradingAllowed(String orderType) {
        // Some order types only allowed during regular hours
        if ("MARKET".equalsIgnoreCase(orderType)) {
            return currentState == MarketState.OPEN; // Market orders only during regular session
        }
        return isTradingAllowed();
    }
    
    public boolean isHoliday(LocalDate date) {
        return holidays.contains(date);
    }
    
    public boolean isMarketOpen() {
        return currentState != MarketState.CLOSED && currentState != MarketState.HALTED;
    }
    
    // ==================== Session Info ====================
    
    public SessionInfo getSessionInfo() {
        SessionInfo info = new SessionInfo();
        info.currentState = currentState;
        info.currentPhase = currentPhase;
        info.stateChangeTime = stateChangeTime;
        info.stateChangeReason = stateChangeReason;
        
        LocalDateTime now = LocalDateTime.now(MARKET_TIMEZONE);
        LocalDate today = now.toLocalDate();
        
        info.tradingDay = today.format(DATE_FORMAT);
        info.isHoliday = holidays.contains(today);
        info.isEarlyClose = earlyCloses.containsKey(today);
        
        if (info.isEarlyClose) {
            info.closeTime = earlyCloses.get(today).toString();
        } else {
            info.closeTime = schedule.regularSessionEnd.toString();
        }
        
        // Calculate time until next transition
        info.timeUntilNextTransition = calculateTimeUntilNextTransition(now);
        info.nextState = determineNextState();
        
        return info;
    }
    
    private String calculateTimeUntilNextTransition(LocalDateTime now) {
        LocalTime currentTime = now.toLocalTime();
        LocalTime nextTransition;
        
        switch (currentState) {
            case CLOSED:
                if (currentTime.isBefore(schedule.preMarketStart)) {
                    nextTransition = schedule.preMarketStart;
                } else {
                    // Next trading day
                    return "Next trading day";
                }
                break;
            case PRE_MARKET:
                nextTransition = schedule.regularSessionStart;
                break;
            case OPEN:
                LocalDate today = now.toLocalDate();
                nextTransition = earlyCloses.getOrDefault(today, schedule.regularSessionEnd);
                break;
            case AFTER_HOURS:
                nextTransition = schedule.afterHoursEnd;
                break;
            default:
                return "Unknown";
        }
        
        Duration duration = Duration.between(currentTime, nextTransition);
        if (duration.isNegative()) {
            return "Imminent";
        }
        
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        return String.format("%d hours %d minutes", hours, minutes);
    }
    
    private String determineNextState() {
        switch (currentState) {
            case CLOSED:
                if (LocalTime.now(MARKET_TIMEZONE).isBefore(schedule.preMarketStart)) {
                    return "PRE_MARKET";
                }
                return "CLOSED (next day)";
            case PRE_MARKET:
                return "OPEN";
            case OPEN:
                return "AFTER_HOURS";
            case AFTER_HOURS:
                return "CLOSED";
            case HALTED:
                return "Awaiting resume";
            default:
                return "Unknown";
        }
    }
    
    public List<MarketStateChange> getRecentStateChanges(int limit) {
        int startIdx = Math.max(0, stateHistory.size() - limit);
        return new ArrayList<>(stateHistory.subList(startIdx, stateHistory.size()));
    }
    
    // ==================== Calendar Management ====================
    
    public void addHoliday(LocalDate date) {
        holidays.add(date);
        LOG.info("Added holiday: {}", date);
    }
    
    public void removeHoliday(LocalDate date) {
        holidays.remove(date);
        LOG.info("Removed holiday: {}", date);
    }
    
    public void setEarlyClose(LocalDate date, LocalTime closeTime) {
        earlyCloses.put(date, closeTime);
        LOG.info("Set early close for {}: {}", date, closeTime);
    }
    
    public Set<LocalDate> getHolidays() {
        return Collections.unmodifiableSet(holidays);
    }
    
    public Map<LocalDate, LocalTime> getEarlyCloses() {
        return Collections.unmodifiableMap(earlyCloses);
    }
    
    // ==================== Schedule Configuration ====================
    
    public void updateSchedule(MarketSchedule newSchedule) {
        this.schedule = newSchedule;
        LOG.info("Market schedule updated");
        checkAndUpdateState();
    }
    
    public MarketSchedule getSchedule() {
        return schedule;
    }
    
    // ==================== Listeners ====================
    
    public void addStateListener(Consumer<MarketStateChange> listener) {
        stateListeners.add(listener);
    }
    
    public void removeStateListener(Consumer<MarketStateChange> listener) {
        stateListeners.remove(listener);
    }
    
    private void notifyStateListeners(MarketStateChange change) {
        for (Consumer<MarketStateChange> listener : stateListeners) {
            try {
                listener.accept(change);
            } catch (Exception e) {
                LOG.error("Error notifying state listener: {}", e.getMessage());
            }
        }
    }
    
    // ==================== Enums and Data Classes ====================
    
    public enum MarketState {
        CLOSED,      // Market is closed (overnight, weekend, holiday)
        PRE_MARKET,  // Pre-market trading session (4:00 AM - 9:30 AM ET)
        OPEN,        // Regular trading hours (9:30 AM - 4:00 PM ET)
        AFTER_HOURS, // After-hours trading (4:00 PM - 8:00 PM ET)
        HALTED       // Emergency trading halt
    }
    
    public enum TradingPhase {
        CLOSED,           // No trading
        PRE_OPEN,         // Pre-market phase
        OPENING_AUCTION,  // Opening auction (first 5 minutes)
        CONTINUOUS,       // Continuous trading
        CLOSING_AUCTION,  // Closing auction (last 10 minutes)
        POST_CLOSE,       // After-hours phase
        HALTED           // Trading halted
    }
    
    public static class MarketSchedule {
        public LocalTime preMarketStart = LocalTime.of(4, 0);      // 4:00 AM ET
        public LocalTime regularSessionStart = LocalTime.of(9, 30); // 9:30 AM ET
        public LocalTime regularSessionEnd = LocalTime.of(16, 0);   // 4:00 PM ET
        public LocalTime afterHoursEnd = LocalTime.of(20, 0);       // 8:00 PM ET
    }
    
    public static class MarketStateChange {
        public MarketState previousState;
        public MarketState newState;
        public TradingPhase previousPhase;
        public TradingPhase newPhase;
        public String reason;
        public LocalDateTime timestamp;
    }
    
    public static class SessionInfo {
        public MarketState currentState;
        public TradingPhase currentPhase;
        public LocalDateTime stateChangeTime;
        public String stateChangeReason;
        public String tradingDay;
        public boolean isHoliday;
        public boolean isEarlyClose;
        public String closeTime;
        public String timeUntilNextTransition;
        public String nextState;
    }
}
