# ExchangeX — Sprint 1 (Java 21)

A learning-oriented, deterministic **limit-order matching engine**. No Spring Boot or Docker is needed for this sprint.

## Requirements
- JDK 21
- Maven 3.9+ (with `mvn` on PATH)

## Run all five tests
```bash
cd exchangex-sprint1
mvn clean test
```

## Compile without Maven (no JUnit tests)
```bash
javac -d /tmp/exchangex-classes $(find matching-engine/src/main/java -name '*.java')
```

## Structure
- `pom.xml`: reactor parent
- `matching-engine/`: pure-Java module and five JUnit tests
- `docs/requirements.md`: scope and acceptance requirements
- `docs/matching-rules.md`: algorithm, ordering and complexity

## Design
`Order` and `Trade` are immutable records. `MatchingEngine` uses a descending `TreeMap` for bids and ascending `TreeMap` for asks; each price holds a FIFO `ArrayDeque`. A map tracks active orders for lookup. The engine is *not thread safe*: only one caller/thread should submit and cancel orders in this Sprint.

## Correctness tests
1. Noncrossing orders rest.
2. Crossing order executes at resting price.
3. Partial fill keeps remaining quantity.
4. Best-price first and FIFO inside price.
5. Cancellation prevents a fill and duplicates are rejected.

## Known limitations
No persistence, user balances, authentication, order ownership, REST API, event streaming, or crash recovery. The returned trade list represents this single submission; `trades()` contains complete in-memory history. This project is a prototype, **not a production exchange**.

## Next
Expand tests for crossing sells, invalid input, repeated fills, deterministic replay and snapshots. Then implement ordered command processing before concurrent producers.
