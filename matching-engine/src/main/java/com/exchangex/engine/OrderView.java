package com.exchangex.engine;
/** Immutable view of an unfilled resting order. */
public record OrderView(long id, Side side, long price, long remainingQuantity) {}
