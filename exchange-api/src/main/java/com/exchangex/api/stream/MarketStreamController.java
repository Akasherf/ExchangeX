package com.exchangex.api.stream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/market")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "exchangex.stream.enabled", havingValue = "true", matchIfMissing = true)
public class MarketStreamController {
    private final MarketStreamService stream;

    public MarketStreamController(MarketStreamService stream) {
        this.stream = stream;
    }

    @GetMapping("/instruments/{symbol}/snapshot")
    public MarketStreamService.MarketSnapshot get(@PathVariable String symbol) {
        return stream.snapshot(symbol);
    }
}
