package com.exchangex.engine;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MatchingEngineTest {
  private final MatchingEngine engine = new MatchingEngine("XYZ");
  private Order buy(long id, long price, long qty) { return new Order(id, "XYZ", Side.BUY, price, qty); }
  private Order sell(long id, long price, long qty) { return new Order(id, "XYZ", Side.SELL, price, qty); }

  @Test void nonCrossingOrdersRemainOpen() {
    assertTrue(engine.submit(buy(1, 10000, 10)).isEmpty());
    assertTrue(engine.submit(sell(2, 10100, 5)).isEmpty());
    assertEquals(10000, engine.bestBid().orElseThrow().price());
    assertEquals(10100, engine.bestAsk().orElseThrow().price());
  }
  @Test void crossingBuyTradesAtRestingPrice() {
    engine.submit(sell(1, 10100, 5));
    assertEquals(List.of(new Trade(2, 1, 10100, 5)), engine.submit(buy(2, 10500, 5)));
    assertTrue(engine.bestAsk().isEmpty());
    assertTrue(engine.bestBid().isEmpty());
  }
  @Test void partialFillLeavesCorrectQuantity() {
    engine.submit(sell(1, 10100, 12));
    assertEquals(List.of(new Trade(2, 1, 10100, 5)), engine.submit(buy(2, 10100, 5)));
    assertEquals(7, engine.find(1).orElseThrow().remainingQuantity());
    assertTrue(engine.find(2).isEmpty());
  }
  @Test void fifoAtSamePriceAndBetterPriceFirst() {
    engine.submit(sell(1, 10200, 5));
    engine.submit(sell(2, 10100, 3));
    engine.submit(sell(3, 10100, 4));
    assertEquals(List.of(new Trade(4, 2, 10100, 3), new Trade(4, 3, 10100, 4), new Trade(4, 1, 10200, 1)), engine.submit(buy(4, 10300, 8)));
    assertEquals(4, engine.find(1).orElseThrow().remainingQuantity());
  }
  @Test void cancellationPreventsLaterExecutionAndDuplicateIdsAreRejected() {
    engine.submit(sell(1, 10100, 4));
    assertTrue(engine.cancel(1));
    assertFalse(engine.cancel(1));
    assertTrue(engine.submit(buy(2, 10100, 4)).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> engine.submit(sell(1, 10100, 4)));
  }
    @Test
    void partiallyFilledBuyOrderShouldRemainActive() {
        MatchingEngine engine = new MatchingEngine("AAPL");

        engine.submit(
                new Order(1L, "AAPL", Side.BUY, 100L, 20L)
        );

        engine.submit(
                new Order(2L, "AAPL", Side.SELL, 99L, 8L)
        );

        OrderView remaining = engine.find(1L).orElseThrow();

        assertEquals(12L, remaining.remainingQuantity());
        assertTrue(engine.find(2L).isEmpty());
        assertEquals(1, engine.trades().size());
    }
    @Test
    void cancelledOrderMustNeverExecute() {
        MatchingEngine engine = new MatchingEngine("AAPL");

        engine.submit(
                new Order(1L, "AAPL", Side.BUY, 100L, 10L)
        );

        boolean cancelled = engine.cancel(1L);

        assertTrue(cancelled);
        assertTrue(engine.find(1L).isEmpty());

        engine.submit(
                new Order(2L, "AAPL", Side.SELL, 99L, 10L)
        );

        assertTrue(engine.trades().isEmpty());
        assertTrue(engine.bestBid().isEmpty());
        assertEquals(
                10L,
                engine.bestAsk().orElseThrow().remainingQuantity()
        );
    }


}
