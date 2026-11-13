# Deterministic matching rules

1. Input commands are processed sequentially on ONE thread; their arrival to `submit` defines time priority.
2. For an incoming BUY limit order at P, match the lowest ASK price <= P. For incoming SELL at P, match the highest BID >= P.
3. Within the best price, match the oldest resting order first (FIFO queue).
4. Each fill quantity = min(incoming remaining, resting remaining); decrement both.
5. Execution price equals RESTING order's limit, never the incoming limit.
6. Remove a fully filled resting order; if its price queue empties, remove the price level.
7. Repeat until no crossing orders remain or incoming quantity reaches zero.
8. Any remaining incoming quantity rests at its own limit price behind orders already at that level.
9. Cancellations remove only outstanding quantity; filled orders cannot be cancelled.
10. Order IDs are globally unique for the lifetime of one engine instance.

## Example
ASK 1: 3 @ 10100, ASK 2: 4 @ 10100, ASK 3: 5 @ 10200. BUY 4: 8 @ 10300 fills 3 with ASK 1, 4 with ASK 2, 1 with ASK 3. ASK 3 has 4 remaining.

## Complexity
Price lookup O(log P) for price levels (P distinct prices). Traversal O(F) for F resting orders filled. Cancellation uses deque removal, O(Q) at that price level (Q number of resting orders there). A later phase can optimize this if benchmarks justify it.
