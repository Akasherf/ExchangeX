# ExchangeX Trading Terminal (Sprint 5)

React 19 + Vite 7 frontend for the ExchangeX educational stock-market simulator.

Run the Spring Boot API on port 8085 first; then:

```sh
cd exchange-web
npm install
npm run dev
```

Open http://127.0.0.1:5173. Vite proxies REST requests (`/api`) and WebSocket (`/ws`) to the Java backend. Do not expose the Vite development server to the public internet.

## Functionality

- Public AAPL/MSFT/GOOGL market snapshots, best bid/ask and recent trade prices.
- Anonymous STOMP subscription to `/topic/market/{symbol}`.
- Registration/login; JWT stays in **sessionStorage** only and is transmitted in HTTPS/WSS or local development requests (use TLS in production).
- STOMP private order notifications at `/user/queue/orders` with JWT in the **STOMP CONNECT header**, not in the WebSocket URL.
- Protected account balance, holdings, orders, limit-order entry and cancellation.
- Automatic reconnect and REST refresh every 15 seconds to recover missed events. Real-time stream is intentionally **best effort**, not a durable event log.

## Limitations

Fresh accounts have no simulation funds or shares. Order placement will be rejected as `INSUFFICIENT_BALANCE` until an authorized operator provides simulation-only seed balances; there is intentionally no public funding endpoint. No deposits, live securities, trading advice, or external market feed. Charts display recent **executed trade prices**, not OHLC or real-time stock quotes. Backend streaming is process-local and polling-based; no cross-node broker, durable outbox, fan-out backpressure or session revocation. No refresh tokens: login again after the 15-minute access token expires.
