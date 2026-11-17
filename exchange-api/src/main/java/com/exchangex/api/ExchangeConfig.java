package com.exchangex.api;

import com.exchangex.api.repository.CheckpointRepository;
import com.exchangex.api.repository.CommandRepository;
import com.exchangex.api.repository.OrderRepository;
import com.exchangex.api.repository.TradeRepository;
import com.exchangex.engine.MatchingEngine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class ExchangeConfig {

    @Bean
    MatchingEngine matchingEngine() {
        return new MatchingEngine("AAPL");
    }

    @Bean
    @Profile("!test")
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    DurableCommandCoordinator durableCommandCoordinator(
            MatchingEngine matchingEngine,
            CommandRepository commandRepository,
            OrderRepository orderRepository,
            TradeRepository tradeRepository,
            CheckpointRepository checkpointRepository,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        return new DurableCommandCoordinator(
                matchingEngine,
                commandRepository,
                orderRepository,
                tradeRepository,
                checkpointRepository,
                jdbcTemplate,
                transactionTemplate
        );
    }
}