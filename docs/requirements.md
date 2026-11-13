# ExchangeX Sprint 1 requirements

## Functional scope
- One `MatchingEngine` instance per uppercase instrument symbol, e.g. `XYZ`.
- Accept unique positive order IDs, BUY/SELL sides, positive integer price ticks (paise) and positive integer quantities.
- Limit orders only; good-till-cancelled while engine is running.
- Incoming orders trade against opposite resting orders if prices cross; best price wins, then FIFO at the same price.
- Execution price is the resting order's price; partial fills are allowed.
- Unfilled incoming quantity rests at the back of its price level.
- Cancellation removes a still-resting order; returns false for unknown/already-filled/cancelled orders.
- Duplicate IDs rejected even after fill or cancellation. Accepted orders are not reused.
- Read-only best bid, best ask, order lookup and trade history.

## Deliberately out of scope
- Authentication, balances, settlement, persistence/restart, network APIs, Kafka, WebSocket, concurrent invocation, production matching guarantees.
- No price-time timestamp from wall clock: submission call order determines FIFO.
- No real securities or real money.

## Non-functional constraints
- Single-writer only: `MatchingEngine` is NOT thread safe.
- Deterministic for the same ordered input commands.
- Use integer paise rather than floating point; quantities are integer units.
- Zero/negative values, invalid symbols, null sides, wrong instrument, duplicate IDs rejected.
- Trade results and snapshots returned as immutable views.

## Deferred decisions
- Crash recovery and durability of accepted orders; user/account reservations; order ownership; sequence IDs and time sources; resynchronization; multiple instruments; overflow-safe notional computation.
