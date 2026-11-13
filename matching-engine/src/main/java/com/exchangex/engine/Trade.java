package com.exchangex.engine;
/** Trade price is the RESTING order's limit price, in paise. */
public record Trade(long buyOrderId, long sellOrderId, long price, long quantity) {}
