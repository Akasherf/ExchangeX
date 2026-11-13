package com.exchangex.engine;
import java.util.Objects;
/** Immutable accepted input; prices in positive integer paise (ticks). */
public record Order(long id, String symbol, Side side, long price, long quantity) {
  public Order {
    if (id <= 0 || price <= 0 || quantity <= 0) throw new IllegalArgumentException("id, price and quantity must be positive");
    Objects.requireNonNull(side, "side");
    if (symbol == null || !symbol.matches("[A-Z][A-Z0-9]{0,9}")) throw new IllegalArgumentException("invalid symbol");
  }
}
