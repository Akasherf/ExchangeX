package com.exchangex.api;

import com.exchangex.api.accounts.TradingClearingService;
import com.exchangex.api.repository.CheckpointRepository;
import com.exchangex.api.repository.CommandRepository;
import com.exchangex.api.repository.OrderRepository;
import com.exchangex.api.repository.TradeRepository;
import com.exchangex.engine.MatchingEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import jakarta.annotation.PreDestroy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One in-process sequenced matching worker per active instrument; no clustering/leader fencing. */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "exchangex.multisymbol.enabled", havingValue = "true", matchIfMissing = true)
public class SymbolCoordinatorRegistry {
    private final DurableCommandCoordinator primary;
    private final CommandRepository commands;
    private final OrderRepository orders;
    private final TradeRepository trades;
    private final CheckpointRepository checkpoints;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TradingClearingService clearing;
    private final Map<String, DurableCommandCoordinator> other = new HashMap<>();
    private boolean started;

    @Value("${exchangex.trading.enforced:true}")
    private boolean tradingEnforced;

    public SymbolCoordinatorRegistry(DurableCommandCoordinator primary, CommandRepository commands,
            OrderRepository orders, TradeRepository trades, CheckpointRepository checkpoints,
            JdbcTemplate jdbc, TransactionTemplate tx,
            org.springframework.beans.factory.ObjectProvider<TradingClearingService> clearingProvider) {
        this.primary = primary;
        this.commands = commands;
        this.orders = orders;
        this.trades = trades;
        this.checkpoints = checkpoints;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clearing = clearingProvider.getIfAvailable();
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (started) return;
        List<String> symbols = activeSymbols();
        for (String symbol : symbols) {
            if ("AAPL".equals(symbol)) continue;
            var worker = new DurableCommandCoordinator(symbol, new MatchingEngine(symbol),
                    commands, orders, trades, checkpoints, jdbc, tx, null);
            worker.configureTrading(clearing, tradingEnforced);
            worker.initialize();
            other.put(symbol, worker);
        }
        started = true;
    }

    public List<String> activeSymbols() {
        return jdbc.query("SELECT symbol FROM instruments WHERE active = TRUE ORDER BY symbol",
                (rs, index) -> rs.getString(1));
    }

    public synchronized DurableCommandCoordinator coordinator(String symbol) {
        if (symbol == null || !symbol.matches("[A-Z][A-Z0-9]{0,9}")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown instrument");
        }
        if (!activeSymbols().contains(symbol)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown instrument");
        }
        if ("AAPL".equals(symbol)) return primary;
        var worker = other.get(symbol);
        if (worker == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Instrument coordinator not initialized");
        return worker;
    }

    @PreDestroy
    public synchronized void close() {
        other.values().forEach(DurableCommandCoordinator::close);
        other.clear();
        started = false;
    }
}
