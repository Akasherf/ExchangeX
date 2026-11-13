package com.exchangex.api;

import com.exchangex.engine.Side;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record OrderRequest(@Positive long id, @NotNull Side side, @Positive long price, @Positive long quantity) {}
