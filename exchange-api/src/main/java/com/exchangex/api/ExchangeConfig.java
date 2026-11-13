package com.exchangex.api;

import com.exchangex.engine.MatchingEngine;
import com.exchangex.engine.OrderCommandProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExchangeConfig {
    @Bean
    MatchingEngine matchingEngine() { return new MatchingEngine("AAPL"); }

    @Bean(destroyMethod = "close")
    OrderCommandProcessor orderCommandProcessor(MatchingEngine engine) {
        return new OrderCommandProcessor(engine, 1024);
    }
}
