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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * 2. EXECUTE: Background worker processes pending commands in strict sequence_id order
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
 * Thread model: Single background thread per symbol (currently only AAPL).
 * All MatchingEngine mutations happen on this thread only.
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

    private final ExecutorService executor;
    private final BlockingQueue<CommandTask> commandQueue;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong lastCommittedSequenceId = new AtomicLong(0);

    // For coordinating shutdown
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    // Dedicated scheduler for execution future polling
    private final ScheduledExecutorService pollingScheduler =
        Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "execution-future-poller-" + symbol));

    public DurableCommandCoordinator(
            MatchingEngine matchingEngine,
            CommandRepository commandRepository,
            OrderRepository orderRepository,
            TradeRepository tradeRepository,
            CheckpointRepository checkpointRepository,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        this.matchingEngine = matchingEngine;
        this.commandRepository = commandRepository;
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
        this.checkpointRepository = checkpointRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;

        this.commandQueue = new ArrayBlockingQueue<>(1024);
        this.executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1024), // executor's internal queue
                r -> new Thread(r, "durable-coordinator-" + symbol),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @PostConstruct
    public void initialize() {
        // 1. Read checkpoint
        long checkpoint = checkpointRepository.getLastCompletedSequenceId(symbol);
        lastCommittedSequenceId.set(checkpoint);
        log.info("Starting coordinator for {} from checkpoint sequence_id={}", symbol, checkpoint);

        // 2. Rebuild MatchingEngine from committed durable state
        rebuildEngineFromDatabase();

        // 3. Replay any commands between checkpoint and current journal head
        replayPendingCommands(checkpoint);

        // 4. Start background worker
        running.set(true);
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
     * These are commands with sequence_id > checkpoint and status = PENDING.
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
            try {
                ExecutionResult result = executeCommand(cmd);
                commitResults(cmd.sequenceId(), result.trades(), result.orderUpdates(), result.isCancel());
            } catch (Exception e) {
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
     * Main processing loop - runs on single background thread.
     */
    private void processLoop() {
        try {
            while (running.get()) {
                CommandTask task;
                try {
                    task = commandQueue.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (task == null) continue;

                try {
                    processCommand(task);
                } catch (Exception e) {
                    log.error("Error processing command {}: {}", task.command().sequenceId(), e.getMessage(), e);
                    task.completeExceptionally(e);
                }
            }
        } finally {
            shutdownLatch.countDown();
        }
    }

    private void processCommand(CommandTask task) {
        CommandRepository.CommandRecord cmd = task.command();

        try {
            // Execute against MatchingEngine
            ExecutionResult result = executeCommand(cmd);

            // Commit results atomically
            commitResults(cmd.sequenceId(), result.trades(), result.orderUpdates(), result.isCancel());

            // Advance checkpoint
            lastCommittedSequenceId.set(cmd.sequenceId());

            // Complete the future successfully
            task.complete(result);

        } catch (Exception e) {
            // A committed admission must never be silently discarded after a transient failure.
            // PostgreSQL rolled back the results; the in-memory engine did not.
            // Stop processing and rely on restart recovery to replay the still-PENDING command.
            log.error("Execution/commit failed for sequence {}. Halting coordinator; command remains PENDING for restart replay.", cmd.sequenceId(), e);
            // Mark command as FAILED so restart can distinguish from never-processed
            try {
                commandRepository.markFailed(cmd.sequenceId());
            } catch (Exception markFailedEx) {
                log.error("Failed to mark command {} as FAILED", cmd.sequenceId(), markFailedEx);
            }
            running.set(false);
            task.completeExceptionally(e);
        }
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
     * If this fails, the MatchingEngine heap is now divergent from DB.
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

            // 3. Mark command as completed
            commandRepository.markCompleted(sequenceId);

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
     * Admit a SUBMIT command to the durable journal.
     * Returns the sequence_id if admitted, or existing sequence_id if idempotent retry.
     * Throws if duplicate order_id with different payload.
     * Throws RejectedExecutionException if command queue is full (backpressure).
     */
    public AdmissionResult admitSubmit(long clientOrderId, Side side, long price, long quantity) {
        // Check queue capacity BEFORE journal admission (backpressure)
        if (commandQueue.remainingCapacity() == 0) {
            throw new RejectedExecutionException("Command queue full");
        }

        // Check for existing pending SUBMIT with same order_id
        var existing = commandRepository.findPendingSubmitByClientOrderId(clientOrderId, symbol);
        if (existing.isPresent()) {
            CommandRepository.CommandRecord cmd = existing.get();
            // Verify payload matches (idempotency with payload check)
            if (cmd.side() != side || cmd.price() != price || cmd.quantity() != quantity) {
                throw new IllegalArgumentException("Duplicate order ID with different payload: " + clientOrderId);
            }
            return new AdmissionResult(cmd.sequenceId(), true); // duplicate, idempotent
        }

        // Insert new command
        long sequenceId;
        try {
            sequenceId = commandRepository.insertSubmitCommand(clientOrderId, symbol, side, price, quantity);
        } catch (DuplicateKeyException e) {
            // Race condition: another thread inserted same order_id
            var retry = commandRepository.findPendingSubmitByClientOrderId(clientOrderId, symbol);
            if (retry.isPresent()) {
                CommandRepository.CommandRecord cmd = retry.get();
                if (cmd.side() != side || cmd.price() != price || cmd.quantity() != quantity) {
                    throw new IllegalArgumentException("Duplicate order ID with different payload: " + clientOrderId);
                }
                return new AdmissionResult(cmd.sequenceId(), true);
            }
            throw e;
        }

        // Queue for execution
        CommandRepository.CommandRecord cmd = new CommandRepository.CommandRecord(
                sequenceId, "SUBMIT", clientOrderId, symbol, side, price, quantity, "PENDING"
        );
        queueCommand(cmd);

        return new AdmissionResult(sequenceId, false);
    }

    /**
     * Admit a CANCEL command to the durable journal.
     * Throws RejectedExecutionException if command queue is full (backpressure).
     */
    public AdmissionResult admitCancel(long clientOrderId) {
        // Check queue capacity BEFORE journal admission (backpressure)
        if (commandQueue.remainingCapacity() == 0) {
            throw new RejectedExecutionException("Command queue full");
        }

        // Check for existing pending CANCEL
        var existing = commandRepository.findPendingCancelByClientOrderId(clientOrderId, symbol);
        if (existing.isPresent()) {
            return new AdmissionResult(existing.get().sequenceId(), true);
        }

        // Check if order exists and is still active
        var order = orderRepository.findByClientOrderId(clientOrderId);
        if (order.isEmpty()) {
            throw new IllegalArgumentException("Order not found: " + clientOrderId);
        }
        if (order.get().executionStatus().equals("CANCELLED") || order.get().executionStatus().equals("FILLED")) {
            throw new IllegalArgumentException("Order already " + order.get().executionStatus().toLowerCase() + ": " + clientOrderId);
        }

        long sequenceId = commandRepository.insertCancelCommand(clientOrderId, symbol);

        CommandRepository.CommandRecord cmd = new CommandRepository.CommandRecord(
                sequenceId, "CANCEL", clientOrderId, symbol, null, null, null, "PENDING"
        );
        queueCommand(cmd);

        return new AdmissionResult(sequenceId, false);
    }

    private void queueCommand(CommandRepository.CommandRecord cmd) {
        CompletableFuture<ExecutionResult> future = new CompletableFuture<>();
        CommandTask task = new CommandTask(cmd, future);
        try {
            commandQueue.put(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.completeExceptionally(e);
        }
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
        return CompletableFuture.completedFuture(
                new BookSnapshot(matchingEngine.bestBid(), matchingEngine.bestAsk())
        );
    }

    public CompletableFuture<List<Trade>> trades() {
        return CompletableFuture.completedFuture(matchingEngine.trades());
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
        executor.shutdown();
        pollingScheduler.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            if (!pollingScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                pollingScheduler.shutdownNow();
            }
            shutdownLatch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            pollingScheduler.shutdownNow();
        }
        log.info("Durable coordinator closed for {}", symbol);
    }

    // Inner classes

    private static class CommandTask implements Runnable {
        private final CommandRepository.CommandRecord command;
        private final CompletableFuture<ExecutionResult> future;

        CommandTask(CommandRepository.CommandRecord command, CompletableFuture<ExecutionResult> future) {
            this.command = command;
            this.future = future;
        }

        CommandRepository.CommandRecord command() { return command; }
        void complete(ExecutionResult result) { future.complete(result); }
        void completeExceptionally(Throwable t) { future.completeExceptionally(t); }

        @Override
        public void run() {
            // This will be called by the executor thread
            // The actual processing is done in processCommand
        }
    }

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