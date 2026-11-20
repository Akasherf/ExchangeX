> Historical installation overlay, not the current installation instructions. Relative links in this archived document refer to its former root location.

> **Historical Sprint 2 instructions — not the current installation guide.** This document describes an early unprotected API overlay and must not be used to deploy the current authenticated, durable ExchangeX application. For current setup, use [README.md](README.md#getting-started), [compose.yaml](compose.yaml), and the [release checklist](docs/releases/v1.0.0-rc1-verification.md). Do not overwrite existing source, configuration, or PostgreSQL volumes using the commands below.

# ExchangeX Sprint 2 — Spring Boot API add-on

This is an **overlay**, not a replacement for your current matching-engine source/tests. Copy `pom.xml` and `exchange-api/` into your existing `~/Downloads/exchangex-sprint1/` folder. Your original tests remain intact. Back up or commit your work first.

## REQUIRED: add read methods to existing OrderCommandProcessor.java

Your matching engine has mutable in-memory collections and is single-writer only. Therefore HTTP reads MUST also pass through the same worker, not directly access the engine. Inside `OrderCommandProcessor`, add these public methods (after `cancel` is fine):

```java
public CompletableFuture<java.util.Optional<OrderView>> find(long orderId) {
    return execute(() -> engine.find(orderId));
}

public record BookSnapshot(
    java.util.Optional<OrderView> bestBid,
    java.util.Optional<OrderView> bestAsk) {}

public CompletableFuture<BookSnapshot> snapshot() {
    return execute(() -> new BookSnapshot(engine.bestBid(), engine.bestAsk()));
}

public CompletableFuture<java.util.List<Trade>> trades() {
    return execute(engine::trades);
}
```

Do NOT call engine.find/bestBid/bestAsk/trades from arbitrary threads after starting the processor. The methods above serialize all reads with order writes.

## Build and run

```sh
cd ~/Downloads/exchangex-sprint1
mvn clean test
mvn -pl exchange-api -am package -DskipTests
java -jar exchange-api/target/exchange-api-0.1.0-SNAPSHOT.jar
```

API on http://localhost:8085. Run in a separate terminal:

```sh
curl -i -X POST http://localhost:8085/api/v1/orders -H 'Content-Type: application/json' -d '{"id":1,"side":"BUY","price":10000,"quantity":10}'
curl -i -X POST http://localhost:8085/api/v1/orders -H 'Content-Type: application/json' -d '{"id":2,"side":"SELL","price":9900,"quantity":6}'
curl -s http://localhost:8085/api/v1/orderbook/AAPL
curl -s http://localhost:8085/api/v1/orders/1
curl -s http://localhost:8085/api/v1/trades
curl -i -X DELETE http://localhost:8085/api/v1/orders/1
```

`price` is in **paise**, so 10000 means ₹100. Only AAPL is supported in this initial iteration. API has no auth/persistence and MUST remain local (do not expose publicly). POST returns an array of trades created by that command, not an order acknowledgement/status object. GET /orders/{id} only finds currently active/resting orders; fully filled or cancelled IDs return 404. Futures are used for serialized processing, but error codes for asynchronous failures require verification against actual Spring MVC exception resolution.

## Troubleshooting

If `OrderCommandProcessor` lacks `find`, `snapshot`, or `trades`, add the methods above. If port 8085 is busy, change `exchange-api/src/main/resources/application.properties`. If Spring Boot dependencies fail to download, verify Maven internet access and version resolution. Module uses Spring Boot 4.1.2 (Java 21 compatible).

## Next engineering changes

1. Create integration tests for the HTTP success and failure paths (MockMvc).
2. Validate queue rejection maps to 503 under async MVC.
3. Add command receipt/accepted status semantics and explicit error type mapping.
4. Add instruments/other symbols, security, durable persistence and recovery in subsequent phases.
