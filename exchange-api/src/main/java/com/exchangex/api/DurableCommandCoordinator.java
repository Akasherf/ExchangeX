package com.exchangex.api;

import com.exchangex.api.repository.CheckpointRepository;
import com.exchangex.api.repository.CommandRepository;
import com.exchangex.api.repository.OrderRepository;
import com.exchangex.api.repository.TradeRepository;
import com.exchangex.engine.MatchingEngine;
import com.exchangex.engine.Order;
import com.exchangex.engine.OrderView;
import com.exchangex.engine.Side;
import com.exchangex.engine.Trade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-writer durable command coordinator per instrument.
 *
 * Architecture:
 * 1. ADMIT: Client commands (SUBMIT/CANCEL) are inserted into exchange_commands (status=PENDING)
 *    - Returns 202 ACCEPTED with sequence_id immediately after commit
 *    - Idempotency: duplicate client order_id returns existing sequence_id
 *
 * 2. EXECUTE: Background worker reads committed commands in strict sequence_id order
 *    - Mutates MatchingEngine (in-memory)
 *    - Builds result projection (trades, order state changes)
 *
 * 3. COMMIT: Single transaction commits:
 *    - INSERT trades
 *    - UPSERT orders (state, remaining_qty, execution_status)
 *    - UPDATE exchange_commands SET status='COMPLETED'
 *    - UPDATE command_checkpoint
 *    - If this TX fails: STOP processing, rebuild engine from DB, resume from checkpoint
 *
 * 4. RECOVERY: On startup, rebuild MatchingEngine from committed orders/trades,
 *    then replay commands with sequence_id > checkpoint.
 *
 * Thread model: One active coordinator/background worker per symbol (currently AAPL).
 * All MatchingEngine mutations happen on this thread only. Multiple application
 * instances processing the same symbol are NOT supported without leader fencing.
 */
public class DurableCommandCoordinator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DurableCommandCoordinator.class);

    private final String symbol = "AAPL";
    private MatchingEngine matchingEngine;
    private final CommandRepository commandRepository;
    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;
    private final CheckpointRepository checkpointRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    // Test-only failure injection seam (package-private, null in production)
    @Nullable
    private final FailureInjection failureInjection;

    // Allow disabling auto-initialization for tests (default true for production)
    @Value("${coordinator.auto-initialize:true}")
    private boolean autoInitialize;

    private ExecutorService executor;
    // PostgreSQL is authoritative. Signals only reduce polling latency; they carry no commands.
    private final Semaphore admissionSignal = new Semaphore(0);
    private static final int MAX_OUTSTANDING = 1024;
    private static final int ADMISSION_LOCK_NAMESPACE = 27182;
    private final AtomicReference<BookSnapshot> publishedBook = new AtomicReference<>(
            new BookSnapshot(Optional.empty(), Optional.empty()));
    private final AtomicReference<List<Trade>> publishedTrades = new AtomicReference<>(List.of());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong lastCommittedSequenceId = new AtomicLong(0);
    private CommandLifecycleObserver lifecycleObserver = CommandLifecycleObserver.NOOP;

    /** Optional, best-effort telemetry. Never participates in transaction decisions. */
    @Autowired(required = false)
    public void setLifecycleObserver(CommandLifecycleObserver listener) {
        this.lifecycleObserver = listener == null ? CommandLifecycleObserver.NOOP : listener;
    }

    private void observe(java.util.function.Consumer<CommandLifecycleObserver> event) {
        try {
            event.accept(lifecycleObserver);
        } catch (RuntimeException observerFailure) {
            // Telemetry must never modify execution, recovery, or admission semantics.
            log.warn("Command telemetry callback failed", observerFailure);
        }
    }

    // Dedicated scheduler for execution future polling
    private ScheduledExecutorService pollingScheduler;

    public DurableCommandCoordinator(
            MatchingEngine matchingEngine,
            CommandRepository commandRepository,
            OrderRepository orderRepository,
            TradeRepository tradeRepository,
            CheckpointRepository checkpointRepository,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        this(matchingEngine, commandRepository, orderRepository, tradeRepository, checkpointRepository,
             jdbcTemplate, transactionTemplate, null);
    }

    /**
     * Package-private constructor for test configuration with failure injection.
     * Only used by test configurations in postgres-integration profile.
     */
    DurableCommandCoordinator(
            MatchingEngine matchingEngine,
            CommandRepository commandRepository,
            OrderRepository orderRepository,
            TradeRepository tradeRepository,
            CheckpointRepository checkpointRepository,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            @Nullable FailureInjection failureInjection
    ) {
        this.matchingEngine = matchingEngine;
        this.commandRepository = commandRepository;
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.checkpointRepository = checkpointRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.failureInjection = failureInjection;

        // Only create executor/scheduler for production mode (when repositories are provided)
        // TestDurableCommandCoordinator passes null repositories and overrides initialize/close
        boolean isProductionMode = commandRepository != null;
        if (isProductionMode) {
            this.executor = Executors.newSingleThreadExecutor(
                    r -> new Thread(r, "durable-coordinator-" + symbol));
            this.pollingScheduler = Executors.newSingleThreadScheduledExecutor(
                    r -> new Thread(r, "execution-future-poller-" + symbol)
            );
        } else {
            this.executor = null;
            this.pollingScheduler = null;
        }
    }

    /**
     * Spring-managed startup hook. Integration tests can suppress automatic replay
     * until the dedicated test database has been cleaned.
     */
    @PostConstruct
    void autoInitializeOnStartup() {
        if (autoInitialize) {
            initialize();
        } else {
            log.info("Auto-initialize disabled for {}", symbol);
        }
    }

    /**
     * Explicitly start or recover this coordinator. Unlike the Spring startup
     * hook, this method must also work on manually constructed coordinators
     * used to simulate a fresh process during recovery tests.
     */
    public void initialize() {
        initializeInternal();
    }

    /**
     * Reinitialize the coordinator from the current database state.
     * Used by tests to sync in-memory state after database cleanup.
     */
    public synchronized void reinitialize() {
        log.info("Reinitializing coordinator for {}", symbol);
        running.set(false);
        observe(o -> o.coordinatorRunning(false));
        admissionSignal.release();
        executor.shutdownNow();
        pollingScheduler.shutdownNow();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Previous coordinator worker is still running");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping previous coordinator worker", e);
        }
        executor = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "durable-coordinator-" + symbol));
        pollingScheduler = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "execution-future-poller-" + symbol));
        initializeInternal();
    }

    private void initializeInternal() {
        // 1. Read checkpoint
        long checkpoint = checkpointRepository.getLastCompletedSequenceId(symbol);
        lastCommittedSequenceId.set(checkpoint);
        log.info("Starting coordinator for {} from checkpoint sequence_id={}", symbol, checkpoint);

        // 2. Rebuild MatchingEngine from committed durable state
        rebuildEngineFromDatabase();

        // 3. Replay any commands between checkpoint and current journal head
        replayPendingCommands(checkpoint);
        publishCommittedState();

        // 4. Start background worker
        running.set(true);
        observe(o -> o.coordinatorRunning(true));
        executor.execute(this::processLoop);
        log.info("Durable coordinator started for {}", symbol);
    }

    /**
     * Rebuild MatchingEngine from committed orders and trades in database.
     * Only orders with execution_status in (PENDING, PARTIAL) are restored to the book.
     * Trades are not re-executed; they are already reflected in remaining_quantity.
     */
    private void rebuildEngineFromDatabase() {
        log.info("Rebuilding MatchingEngine for {} from database", symbol);
        matchingEngine = new MatchingEngine(symbol);

        // Load persisted trades first (so they're available in trades() endpoint)
        List<TradeRepository.TradeRecord> persistedTrades = tradeRepository.findBySymbol(symbol);
        if (!persistedTrades.isEmpty()) {
            List<Trade> trades = persistedTrades.stream()
                    .map(tr -> new Trade(tr.buyOrderId(), tr.sellOrderId(), tr.executionPrice(), tr.executionQuantity()))
                    .toList();
            matchingEngine.restoreTradeHistory(trades);
            log.info("Restored {} trades from database", trades.size());
        }

        // Get all active orders (PENDING or PARTIAL execution status)
        List<OrderRepository.OrderRecord> activeOrders = orderRepository.findActiveOrders(symbol);

        // Sort by exchange_sequence_id to maintain price-time priority
        activeOrders.sort((a, b) -> Long.compare(a.exchangeSequenceId(), b.exchangeSequenceId()));

        for (OrderRepository.OrderRecord record : activeOrders) {
            Order order = new Order(
                    record.clientOrderId(),
                    record.symbol(),
                    record.side(),
                    record.limitPrice(),
                    record.remainingQuantity()  // Use remaining, not original
            );
            // Submit to engine - this will place it in the book at the right price-time position
            // Since we're rebuilding in sequence order, price-time priority is preserved
            matchingEngine.submit(order);
        }

        log.info("Rebuilt engine with {} active orders", activeOrders.size());
    }

    /**
     * Replay commands that were committed to journal but not yet processed.
     * These are commands with sequence_id > checkpoint and status PENDING or FAILED.
     */
    private void replayPendingCommands(long checkpoint) {
        List<CommandRepository.CommandRecord> pendingCommands =
                commandRepository.findPendingCommandsSince(symbol, checkpoint);

        if (pendingCommands.isEmpty()) {
            log.info("No pending commands to replay for {}", symbol);
            return;
        }

        log.info("Replaying {} pending commands for {}", pendingCommands.size(), symbol);

        for (CommandRepository.CommandRecord cmd : pendingCommands) {
            long executionStart = System.nanoTime();
            try {
                ExecutionResult result = executeCommand(cmd);
                long commitStart = System.nanoTime();
                commitResults(cmd.sequenceId(), result.trades(), result.orderUpdates(), result.isCancel());
                long committed = System.nanoTime();
                observe(o -> o.commandCommitted(cmd.commandType(), true,
                        commitStart - executionStart, committed - commitStart, result.trades().size()));
            } catch (Exception e) {
                observe(o -> o.commandFailed(true));
                log.error("Failed to replay command {}: {}", cmd.sequenceId(), e.getMessage(), e);
                // Leave journal entry PENDING for the next recovery attempt.
                // Stop replay on first failure - intervention may be needed
                throw new IllegalStateException("Replay failed at sequence_id=" + cmd.sequenceId(), e);
            }
        }

        lastCommittedSequenceId.set(pendingCommands.get(pendingCommands.size() - 1).sequenceId());
        log.info("Replay complete, advanced checkpoint to {}", lastCommittedSequenceId.get());
    }

    /**
     * Drain the authoritative, committed PostgreSQL journal in sequence order.
     * Never execute an in-memory enqueue order: concurrent callers may commit and
     * notify in a different order. The admission transaction's per-symbol
     * advisory lock makes committed sequence ordering unambiguous.
     */
    private void processLoop() {
        while (running.get()) {
            try {
                List<CommandRepository.CommandRecord> pending =
                        commandRepository.findPendingCommandsSince(symbol, lastCommittedSequenceId.get());
                if (pending.isEmpty()) {
                    // Coalesce notifications; the DB scan, not the permit count,
                    // determines which commands are executable.
                    admissionSignal.drainPermits();
                    admissionSignal.tryAcquire(100, TimeUnit.MILLISECONDS);
                    continue;
                }
                // Read one at a time so each checkpoint is persisted before taking the next.
                processCommand(pending.get(0));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                running.set(false);
                observe(o -> o.coordinatorRunning(false));
                log.error("Durable journal polling halted for {}", symbol, e);
            }
        }
    }

    private void processCommand(CommandRepository.CommandRecord cmd) {
        long executionStart = System.nanoTime();
        try {
            ExecutionResult result = executeCommand(cmd);
            long commitStart = System.nanoTime();
            commitResults(cmd.sequenceId(), result.trades(), result.orderUpdates(), result.isCancel());
            long committed = System.nanoTime();
            lastCommittedSequenceId.set(cmd.sequenceId());
            publishCommittedState();
            observe(o -> o.commandCommitted(cmd.commandType(), false,
                    commitStart - executionStart, committed - commitStart, result.trades().size()));
        } catch (Exception e) {
            observe(o -> o.commandFailed(false));
            log.error("Execution/commit failed for sequence {}. Halting coordinator; marking command FAILED for restart replay.", cmd.sequenceId(), e);
            try {
                commandRepository.markFailed(cmd.sequenceId());
            } catch (Exception markFailedEx) {
                log.error("Failed to mark command {} as FAILED", cmd.sequenceId(), markFailedEx);
            }
            running.set(false);
            observe(o -> o.coordinatorRunning(false));
        }
    }

    /** Immutable committed-state snapshots, never live reads of mutable TreeMaps. */
    private void publishCommittedState() {
        publishedBook.set(new BookSnapshot(matchingEngine.bestBid(), matchingEngine.bestAsk()));
        publishedTrades.set(List.copyOf(matchingEngine.trades()));
    }

    /**
     * Execute a single command against the MatchingEngine.
     * Returns the trades and order updates produced.
     */
    private ExecutionResult executeCommand(CommandRepository.CommandRecord cmd) {
        if ("SUBMIT".equals(cmd.commandType())) {
            Order order = new Order(cmd.clientOrderId(), cmd.symbol(), cmd.side(), cmd.price(), cmd.quantity());
            List<Trade> trades = matchingEngine.submit(order);

            // Build order updates for the submitted order and any maker orders that were filled
            List<OrderUpdate> orderUpdates = buildOrderUpdatesForSubmit(cmd, trades);

            return new ExecutionResult(trades, orderUpdates, false, cmd.sequenceId());
        } else if ("CANCEL".equals(cmd.commandType())) {
            boolean cancelled = matchingEngine.cancel(cmd.clientOrderId());
            List<OrderUpdate> orderUpdates = new ArrayList<>();

            if (cancelled) {
                orderUpdates.add(new OrderUpdate(cmd.clientOrderId(), 0, "CANCELLED"));
            }

            return new ExecutionResult(List.of(), orderUpdates, true, cmd.sequenceId());
        } else {
            throw new IllegalArgumentException("Unknown command type: " + cmd.commandType());
        }
    }

    /**
     * Build order updates for a SUBMIT command.
     * Includes the incoming order and any resting orders that were filled/partially filled.
     */
    private List<OrderUpdate> buildOrderUpdatesForSubmit(CommandRepository.CommandRecord cmd, List<Trade> trades) {
        List<OrderUpdate> updates = new ArrayList<>();

        // The incoming order
        long remainingQty = cmd.quantity();
        for (Trade trade : trades) {
            remainingQty -= trade.quantity();
        }
        String execStatus = remainingQty == 0 ? "FILLED" :
                           trades.isEmpty() ? "PENDING" : "PARTIAL";
        updates.add(new OrderUpdate(cmd.clientOrderId(), remainingQty, execStatus, cmd.sequenceId()));

        // Each maker may appear in several trades. Aggregate fills and read its
        // previously persisted state; a fully-filled maker no longer exists in the live book.
        java.util.Map<Long, Long> makerFills = new java.util.LinkedHashMap<>();
        for (Trade trade : trades) {
            long makerId = cmd.side() == Side.BUY ? trade.sellOrderId() : trade.buyOrderId();
            makerFills.merge(makerId, trade.quantity(), Long::sum);
        }
        for (var makerFill : makerFills.entrySet()) {
            OrderRepository.OrderRecord maker = orderRepository.findByClientOrderId(makerFill.getKey())
                    .orElseThrow(() -> new IllegalStateException("Missing maker order: " + makerFill.getKey()));
            long makerRemaining = maker.remainingQuantity() - makerFill.getValue();
            if (makerRemaining < 0) throw new IllegalStateException("Maker quantity underflow");
            updates.add(new OrderUpdate(makerFill.getKey(), makerRemaining,
                    makerRemaining == 0 ? "FILLED" : "PARTIAL"));
        }

        return updates;
    }

    /**
     * Atomically commit execution results to database.
     * Single transaction: orders + trades + command status + checkpoint.
     * Orders must be inserted first to satisfy FK constraints on trades.
     * If this fails, the MatchingEngine heap is now divergent from DB; processing
     * must halt and restart from committed state rather than continue.
     */
    protected void commitResults(long sequenceId, List<Trade> trades, List<OrderUpdate> orderUpdates, boolean isCancel) {
        transactionTemplate.executeWithoutResult(txStatus -> {
            // 1. Handle orders FIRST - INSERT new orders for SUBMIT, UPDATE for both SUBMIT and CANCEL
            // This must happen before trade insert to satisfy FK constraints (trades reference orders)
            for (OrderUpdate update : orderUpdates) {
                if (update.executionStatus().equals("CANCELLED")) {
                    orderRepository.markCancelled(update.clientOrderId());
                } else {
                    // Check if order exists, if not insert it
                    var existing = orderRepository.findByClientOrderId(update.clientOrderId());
                    if (existing.isEmpty()) {
                        // This is a new SUBMIT command - insert the order
                        // We need to get the original order details from the command
                        var cmd = commandRepository.findBySequenceId(sequenceId);
                        if (cmd.isPresent()) {
                            var cmdRecord = cmd.get();
                            if (update.clientOrderId() != cmdRecord.clientOrderId()) {
                                throw new IllegalStateException("Cannot insert missing maker order " + update.clientOrderId());
                            }
                            String status = switch (update.executionStatus()) {
                                case "FILLED" -> "FILLED";
                                case "PARTIAL" -> "PARTIALLY_FILLED";
                                case "CANCELLED" -> "CANCELLED";
                                default -> "OPEN";
                            };
                            OrderRepository.OrderRecord newOrder = new OrderRepository.OrderRecord(
                                    cmdRecord.clientOrderId(), // order_id = client_order_id
                                    cmdRecord.clientOrderId(),
                                    sequenceId,
                                    cmdRecord.symbol(),
                                    cmdRecord.side(),
                                    cmdRecord.price(),
                                    cmdRecord.quantity(), // original_quantity
                                    update.remainingQuantity(),
                                    update.executionStatus(),
                                    status
                            );
                            orderRepository.insertOrder(newOrder);
                        }
                    } else {
                        // Update existing order
                        orderRepository.updateOrderExecution(update.clientOrderId(), update.remainingQuantity(), update.executionStatus());
                    }
                }
            }

            // Failure injection point 1: After orders, before trades
            if (failureInjection != null) {
                failureInjection.afterOrdersBeforeTrades(sequenceId);
            }

            // 2. Insert trades with sequence references (after orders exist)
            if (!trades.isEmpty()) {
                List<TradeRepository.TradeRecord> tradeRecords = new ArrayList<>();
                for (Trade trade : trades) {
                    long buySeqId, sellSeqId;
                    if (isCancel) {
                        continue;
                    } else {
                        buySeqId = sequenceId;
                        sellSeqId = sequenceId;
                    }
                    tradeRecords.add(TradeRepository.fromTrade(trade, buySeqId, sellSeqId));
                }
                fixTradeSequenceIds(tradeRecords, trades, sequenceId);
                tradeRepository.insertTrades(tradeRecords);
            }

            // Failure injection point 2: After trades, before command status
            if (failureInjection != null) {
                failureInjection.afterTradesBeforeCommandStatus(sequenceId);
            }

            // 3. Mark command as completed
            commandRepository.markCompleted(sequenceId);

            // Failure injection point 3: After command status, before checkpoint
            if (failureInjection != null) {
                failureInjection.afterCommandStatusBeforeCheckpoint(sequenceId);
            }

            // 4. Advance checkpoint
            checkpointRepository.advanceIfGreater(symbol, sequenceId);
        });
    }

    /**
     * Fix trade sequence IDs by looking up the maker order's exchange_sequence_id.
     * The taker is the current command (sequenceId), the maker is the resting order.
     * We need the current command's side to know which side is taker vs maker.
     */
    private void fixTradeSequenceIds(List<TradeRepository.TradeRecord> tradeRecords, List<Trade> trades, long currentSequenceId) {
        // Get the current command to know its side (BUY or SELL)
        var cmdOpt = commandRepository.findBySequenceId(currentSequenceId);
        if (cmdOpt.isEmpty()) {
            log.warn("Could not find command for sequenceId={}, using fallback sequence IDs", currentSequenceId);
            return;
        }
        Side currentCommandSide = cmdOpt.get().side();

        for (int idx = 0; idx < trades.size(); idx++) {
            final int i = idx;
            Trade trade = trades.get(i);
            TradeRepository.TradeRecord record = tradeRecords.get(i);

            // Determine maker vs taker based on current command's side
            // If current command is BUY: taker=buy, maker=sell
            // If current command is SELL: taker=sell, maker=buy
            long makerOrderId;
            long takerSequenceId = currentSequenceId;
            long makerSequenceId;

            if (currentCommandSide == Side.BUY) {
                // Current command is BUY (taker), so maker is the SELL order
                makerOrderId = trade.sellOrderId();
                makerSequenceId = lookupMakerSequenceId(makerOrderId);
                tradeRecords.set(i, new TradeRepository.TradeRecord(
                        record.tradeId(), record.buyOrderId(), record.sellOrderId(),
                        record.executionPrice(), record.executionQuantity(),
                        takerSequenceId,      // buy_sequence_id = current command (taker)
                        makerSequenceId       // sell_sequence_id = maker's sequence_id
                ));
            } else {
                // Current command is SELL (taker), so maker is the BUY order
                makerOrderId = trade.buyOrderId();
                makerSequenceId = lookupMakerSequenceId(makerOrderId);
                tradeRecords.set(i, new TradeRepository.TradeRecord(
                        record.tradeId(), record.buyOrderId(), record.sellOrderId(),
                        record.executionPrice(), record.executionQuantity(),
                        makerSequenceId,      // buy_sequence_id = maker's sequence_id
                        takerSequenceId       // sell_sequence_id = current command (taker)
                ));
            }
        }
    }

    private long lookupMakerSequenceId(long makerOrderId) {
        // First try to find the maker's sequence_id from the orders table (committed state)
        var orderOpt = orderRepository.findByClientOrderId(makerOrderId);
        if (orderOpt.isPresent()) {
            return orderOpt.get().exchangeSequenceId();
        }

        // Fallback: look up the SUBMIT command for this maker order in the journal
        // The command journal is the authoritative source for sequence_ids
        var cmdOpt = commandRepository.findPendingSubmitByClientOrderId(makerOrderId, symbol);
        if (cmdOpt.isPresent()) {
            return cmdOpt.get().sequenceId();
        }

        // Also check completed commands
        // We need a new repository method, but for now throw descriptive error
        throw new IllegalStateException(
                "Maker order " + makerOrderId + " not found in orders table or command journal. " +
                "This indicates a data integrity issue: maker's SUBMIT command should exist.");
    }

    /**
     * Serialize the journal INSERT and its commit in PostgreSQL (not just in Java).
     * An identity/sequence number is assigned before commit; without serialization,
     * sequence 101 could commit and execute before an uncommitted sequence 100.
     */
    private void lockAdmissionTransaction() {
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(?, hashtext(?))",
                rs -> { /* wait for lock, no result to map */ }, ADMISSION_LOCK_NAMESPACE, symbol);
    }

    private void ensureAcceptingCommands() {
        if (!running.get()) {
            throw new RejectedExecutionException("Coordinator not accepting commands");
        }
    }

    private void enforceBackpressure() {
        if (commandRepository.countOutstanding(symbol) >= MAX_OUTSTANDING) {
            throw new RejectedExecutionException("Outstanding command limit reached");
        }
    }

    /** The payload of an idempotent retry must match, including after COMPLETED. */
    private AdmissionResult existingSubmitOrNull(long clientOrderId, Side side, long price, long quantity) {
        var existing = commandRepository.findPendingSubmitByClientOrderId(clientOrderId, symbol);
        if (existing.isEmpty()) return null;
        var cmd = existing.get();
        if (cmd.side() != side || cmd.price() != price || cmd.quantity() != quantity) {
            throw new IllegalArgumentException("Duplicate order ID with different payload: " + clientOrderId);
        }
        return new AdmissionResult(cmd.sequenceId(), true);
    }

    public AdmissionResult admitSubmit(long clientOrderId, Side side, long price, long quantity) {
        long admissionStart = System.nanoTime();
        ensureAcceptingCommands();
        AdmissionResult admitted = transactionTemplate.execute(tx -> {
            lockAdmissionTransaction();
            AdmissionResult prior = existingSubmitOrNull(clientOrderId, side, price, quantity);
            if (prior != null) return prior;
            enforceBackpressure();
            long sequenceId = commandRepository.insertSubmitCommand(clientOrderId, symbol, side, price, quantity);
            return new AdmissionResult(sequenceId, false);
        });
        if (admitted == null) throw new IllegalStateException("Admission transaction returned no result");
        if (!admitted.isDuplicate()) admissionSignal.release();
        long admissionNanos = System.nanoTime() - admissionStart;
        observe(o -> o.commandAdmitted("SUBMIT", admitted.isDuplicate(), admissionNanos));
        return admitted;
    }

    /**
     * A SUBMIT exists durably before it exists in the orders projection. Accept
     * a CANCEL for such a pending submit; the single writer will execute the
     * SUBMIT first and then cancel any remaining resting quantity.
     */
    public AdmissionResult admitCancel(long clientOrderId) {
        long admissionStart = System.nanoTime();
        ensureAcceptingCommands();
        AdmissionResult admitted = transactionTemplate.execute(tx -> {
            lockAdmissionTransaction();
            var existing = commandRepository.findPendingCancelByClientOrderId(clientOrderId, symbol);
            if (existing.isPresent()) return new AdmissionResult(existing.get().sequenceId(), true);

            var submit = commandRepository.findPendingSubmitByClientOrderId(clientOrderId, symbol);
            if (submit.isEmpty()) throw new IllegalArgumentException("Order not found: " + clientOrderId);

            var persisted = orderRepository.findByClientOrderId(clientOrderId);
            if (persisted.isPresent()) {
                String status = persisted.get().executionStatus();
                if ("CANCELLED".equals(status) || "FILLED".equals(status)) {
                    throw new IllegalArgumentException("Order already " + status.toLowerCase() + ": " + clientOrderId);
                }
            }
            enforceBackpressure();
            long sequenceId = commandRepository.insertCancelCommand(clientOrderId, symbol);
            return new AdmissionResult(sequenceId, false);
        });
        if (admitted == null) throw new IllegalStateException("Admission transaction returned no result");
        if (!admitted.isDuplicate()) admissionSignal.release();
        long admissionNanos = System.nanoTime() - admissionStart;
        observe(o -> o.commandAdmitted("CANCEL", admitted.isDuplicate(), admissionNanos));
        return admitted;
    }

    // Public API for controller

    public CompletableFuture<AdmissionResult> submitOrder(long clientOrderId, Side side, long price, long quantity) {
        try {
            AdmissionResult admission = admitSubmit(clientOrderId, side, price, quantity);
            return CompletableFuture.completedFuture(admission);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<AdmissionResult> cancelOrder(long clientOrderId) {
        try {
            AdmissionResult admission = admitCancel(clientOrderId);
            return CompletableFuture.completedFuture(admission);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<Optional<OrderView>> findOrder(long clientOrderId) {
        // Read from order repository (includes cancelled/filled orders not in engine)
        var record = orderRepository.findByClientOrderId(clientOrderId);
        if (record.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        OrderRepository.OrderRecord r = record.get();
        OrderView view = new OrderView(
                r.clientOrderId(),
                r.side(),
                r.limitPrice(),
                r.remainingQuantity()
        );
        return CompletableFuture.completedFuture(Optional.of(view));
    }

    public CompletableFuture<BookSnapshot> snapshot() {
        return CompletableFuture.completedFuture(publishedBook.get());
    }

    public CompletableFuture<List<Trade>> trades() {
        return CompletableFuture.completedFuture(publishedTrades.get());
    }

    private CompletableFuture<ExecutionResult> getExecutionFuture(long sequenceId) {
        CompletableFuture<ExecutionResult> future = new CompletableFuture<>();

        // Schedule polling task on dedicated scheduler
        ScheduledFuture<?> pollTask = pollingScheduler.scheduleAtFixedRate(() -> {
            if (future.isDone()) return;

            try {
                // Verify specific command's terminal state in DB
                var cmdOpt = commandRepository.findBySequenceId(sequenceId);
                if (cmdOpt.isPresent()) {
                    String status = cmdOpt.get().status(); // PENDING, COMPLETED, FAILED
                    if ("COMPLETED".equals(status)) {
                        // Command completed - do not fabricate ExecutionResult
                        // Return minimal result; client should poll GET /orders/{id} for details
                        future.complete(new ExecutionResult(List.of(), List.of(), false, sequenceId));
                        return;
                    }
                    if ("FAILED".equals(status)) {
                        future.completeExceptionally(new IllegalStateException(
                            "Command " + sequenceId + " marked FAILED in journal"));
                        return;
                    }
                    // PENDING - continue polling
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }, 0, 50, TimeUnit.MILLISECONDS);

        // Timeout handling - does NOT cancel or alter the durable command
        CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS).execute(() -> {
            if (!future.isDone()) {
                pollTask.cancel(false);
                future.completeExceptionally(new IllegalStateException(
                    "Timeout waiting for command " + sequenceId + " to complete after 30s. " +
                    "Command remains in journal for replay."));
            }
        });

        // Also cancel poll task when future completes (success or failure)
        future.whenComplete((r, e) -> pollTask.cancel(false));

        return future;
    }

    @Override
    public void close() {
        running.set(false);
        observe(o -> o.coordinatorRunning(false));
        admissionSignal.release();
        executor.shutdown();
        pollingScheduler.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            if (!pollingScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                pollingScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            pollingScheduler.shutdownNow();
        }
        log.info("Durable coordinator closed for {}", symbol);
    }

    // Inner classes

    record ExecutionResult(List<Trade> trades, List<OrderUpdate> orderUpdates, boolean isCancel, long sequenceId) {}

    record OrderUpdate(long clientOrderId, long remainingQuantity, String executionStatus) {
        OrderUpdate(long clientOrderId, long remainingQuantity, String executionStatus, long sequenceId) {
            this(clientOrderId, remainingQuantity, executionStatus);
        }
    }

    record AdmissionResult(long sequenceId, boolean isDuplicate) {}

    public record BookSnapshot(
            Optional<OrderView> bestBid,
            Optional<OrderView> bestAsk
    ) {}
}