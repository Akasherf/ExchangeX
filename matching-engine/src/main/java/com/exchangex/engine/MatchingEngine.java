package com.exchangex.engine;
import java.util.*;
/** In-memory, single-writer limit-order book. Not thread-safe: caller must serialize commands. */
public final class MatchingEngine {
  private final String symbol;
  private final NavigableMap<Long, Deque<Resting>> bids = new TreeMap<>(Comparator.reverseOrder());
  private final NavigableMap<Long, Deque<Resting>> asks = new TreeMap<>();
  private final Map<Long, Resting> live = new HashMap<>();
  private final Set<Long> seenIds = new HashSet<>();
  private final List<Trade> history = new ArrayList<>();
  private static final class Resting {
    final Order order;
    long remaining;
    Resting(Order order, long remaining) { this.order = order; this.remaining = remaining; }
  }
  public MatchingEngine(String symbol) {
    if (symbol == null || !symbol.matches("[A-Z][A-Z0-9]{0,9}")) throw new IllegalArgumentException("invalid symbol");
    this.symbol = symbol;
  }
  public List<Trade> submit(Order order) {
    Objects.requireNonNull(order, "order");
    if (!symbol.equals(order.symbol())) throw new IllegalArgumentException("wrong instrument");
    if (!seenIds.add(order.id())) throw new IllegalArgumentException("duplicate order ID");
    int tradeStart = history.size();
    long remaining = order.quantity();
    NavigableMap<Long, Deque<Resting>> opposite = order.side() == Side.BUY ? asks : bids;

    while (remaining > 0 && !opposite.isEmpty()) {
      long best = opposite.firstKey();
      boolean crosses = order.side() == Side.BUY ? order.price() >= best : order.price() <= best;
      if (!crosses) break;
      Deque<Resting> level = opposite.firstEntry().getValue();
      Resting maker = level.peekFirst();
      long fill = Math.min(remaining, maker.remaining);
      Trade trade = order.side() == Side.BUY
        ? new Trade(order.id(), maker.order.id(), maker.order.price(), fill)
        : new Trade(maker.order.id(), order.id(), maker.order.price(), fill);
      history.add(trade);
      remaining -= fill;
      maker.remaining -= fill;
      if (maker.remaining == 0) {
        level.removeFirst();
        live.remove(maker.order.id());
        if (level.isEmpty()) opposite.pollFirstEntry();
      }
    }
    if (remaining > 0) {
      Resting rest = new Resting(order, remaining);
      NavigableMap<Long, Deque<Resting>> own = order.side() == Side.BUY ? bids : asks;
      own.computeIfAbsent(order.price(), ignored -> new ArrayDeque<>()).addLast(rest);
      live.put(order.id(), rest);
    }
    return List.copyOf(history.subList(tradeStart, history.size()));
  }
  public boolean cancel(long orderId) {
    Resting rest = live.remove(orderId);
    if (rest == null) return false;
    NavigableMap<Long, Deque<Resting>> own = rest.order.side() == Side.BUY ? bids : asks;
    Deque<Resting> level = own.get(rest.order.price());
    level.remove(rest);
    if (level.isEmpty()) own.remove(rest.order.price());
    return true;
  }
  public Optional<OrderView> bestBid() { return best(bids); }
  public Optional<OrderView> bestAsk() { return best(asks); }
  private Optional<OrderView> best(NavigableMap<Long, Deque<Resting>> book) {
    if (book.isEmpty()) return Optional.empty();
    Resting r = book.firstEntry().getValue().peekFirst();
    return Optional.of(new OrderView(r.order.id(), r.order.side(), r.order.price(), r.remaining));
  }
  public List<Trade> trades() { return List.copyOf(history); }
  public Optional<OrderView> find(long orderId) {
    Resting r = live.get(orderId);
    return r == null ? Optional.empty() : Optional.of(new OrderView(r.order.id(), r.order.side(), r.order.price(), r.remaining));
  }
}
