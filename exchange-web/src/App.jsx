import { useCallback, useEffect, useState } from 'react';
import { exchangeApi } from './api';
import { useMarketStream } from './useMarketStream';

const fmt = value => typeof value === 'number' ? new Intl.NumberFormat('en-US').format(value) : '—';
const cashFmt = value => value == null ? '—' : `${fmt(value)} units`;
const initialToken = () => sessionStorage.getItem('exchangex_token') || '';
const nearTime = date => date ? new Date(date).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '—';

function Sparkline({ ticks = [] }) {
  const prices = [...ticks].reverse().map(item => Number(item.price)).filter(Number.isFinite);
  if (prices.length < 2) return <div className="empty-chart">Trade prices appear here after executions.</div>;
  const lo = Math.min(...prices), hi = Math.max(...prices), spread = hi - lo || 1;
  const points = prices.map((price, i) => `${(i / (prices.length - 1)) * 100},${53 - ((price - lo) / spread) * 43}`).join(' ');
  return <svg className="sparkline" viewBox="0 0 100 60" preserveAspectRatio="none" role="img" aria-label="Recent execution prices">
    <polyline points={points} fill="none" stroke="currentColor" strokeWidth="1.5" vectorEffect="non-scaling-stroke" strokeLinejoin="round" />
  </svg>;
}

function AuthPanel({ onAuthenticated }) {
  const [mode, setMode] = useState('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  async function submit(event) {
    event.preventDefault();
    setBusy(true); setMessage('');
    try {
      if (mode === 'register') {
        await exchangeApi.auth('register', email.trim(), password);
        setMode('login'); setMessage('Account created. Sign in to trade. New accounts have no simulation funds.');
      } else {
        const result = await exchangeApi.auth('login', email.trim(), password);
        onAuthenticated(result.accessToken);
      }
    } catch (error) { setMessage(error.message); }
    finally { setBusy(false); }
  }
  return <section className="panel auth-panel">
    <div className="eyebrow">TRADER ACCESS</div>
    <h2>{mode === 'login' ? 'Sign in to trade' : 'Create your account'}</h2>
    <p className="muted">Market data is public. Orders and account balances require authentication.</p>
    <form onSubmit={submit} className="form-stack">
      <label>Email<input type="email" required autoComplete="email" value={email} onChange={e => setEmail(e.target.value)} placeholder="trader@example.com" /></label>
      <label>Password<input type="password" required minLength={12} autoComplete={mode === 'login' ? 'current-password' : 'new-password'} value={password} onChange={e => setPassword(e.target.value)} placeholder="At least 12 characters" /></label>
      <button className="primary" disabled={busy}>{busy ? 'Working…' : mode === 'login' ? 'Sign in' : 'Register'}</button>
    </form>
    {message && <p role="status" className="notice">{message}</p>}
    <button className="link-button" onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setMessage(''); }}>
      {mode === 'login' ? 'Need an account? Register' : 'Already registered? Sign in'}
    </button>
  </section>;
}

function OrderTicket({ symbol, token, afterOrder }) {
  const [side, setSide] = useState('BUY');
  const [price, setPrice] = useState('100');
  const [quantity, setQuantity] = useState('1');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  async function submit(event) {
    event.preventDefault();
    setMessage('');
    const p = Number(price), q = Number(quantity);
    if (!Number.isSafeInteger(p) || p <= 0 || !Number.isSafeInteger(q) || q <= 0) {
      setMessage('Use positive whole numbers for price and quantity.'); return;
    }
    if (p * q > Number.MAX_SAFE_INTEGER) { setMessage('Notional exceeds safe browser integer range.'); return; }
    const id = Date.now() * 1000 + Math.floor(Math.random() * 1000);
    setBusy(true);
    try {
      const reply = await exchangeApi.submit(symbol, token, { id, side, price: p, quantity: q });
      setMessage(`Order #${id} accepted for processing (sequence ${reply.sequenceId}).`);
      afterOrder();
    } catch (error) { setMessage(`Order rejected: ${error.message}`); }
    finally { setBusy(false); }
  }
  return <section className="panel ticket">
    <div className="panel-head"><h2>Place a limit order</h2><span className="small-tag">{symbol}</span></div>
    <div className="segmented"><button className={side === 'BUY' ? 'active buy' : ''} onClick={() => setSide('BUY')}>Buy</button><button className={side === 'SELL' ? 'active sell' : ''} onClick={() => setSide('SELL')}>Sell</button></div>
    <form onSubmit={submit} className="form-stack">
      <label>Limit price <span>simulation units</span><input type="number" min="1" step="1" value={price} onChange={e => setPrice(e.target.value)} required /></label>
      <label>Quantity <span>shares</span><input type="number" min="1" step="1" value={quantity} onChange={e => setQuantity(e.target.value)} required /></label>
      <div className="estimated"><span>Estimated order value</span><strong>{cashFmt(Number(price) * Number(quantity))}</strong></div>
      <button disabled={busy} className={side === 'BUY' ? 'primary buy-action' : 'primary sell-action'}>{busy ? 'Submitting…' : `${side === 'BUY' ? 'Buy' : 'Sell'} ${symbol}`}</button>
    </form>
    {message && <p className="notice" role="status">{message}</p>}
    <p className="muted tiny">Orders are asynchronous. An acceptance response does not guarantee execution. Balances are simulation-only.</p>
  </section>;
}

function OrderHistory({ orders, token, reload, busy }) {
  const [pendingId, setPendingId] = useState(null);
  const [error, setError] = useState('');
  async function cancel(order) {
    setPendingId(order.id); setError('');
    try { await exchangeApi.cancel(order.symbol, order.id, token); reload(); }
    catch (err) { setError(err.message); }
    finally { setPendingId(null); }
  }
  return <section className="panel orders-panel">
    <div className="panel-head"><h2>My orders</h2><button className="subtle-button" onClick={reload} disabled={busy}>Refresh</button></div>
    {error && <p role="alert" className="notice">{error}</p>}
    <div className="table-scroll"><table><thead><tr><th>Symbol</th><th>Side</th><th>Price</th><th>Remaining</th><th>Status</th><th></th></tr></thead><tbody>
      {orders.map(order => <tr key={order.id}>
        <td><strong>{order.symbol}</strong><small>#{order.id}</small></td>
        <td className={order.side === 'BUY' ? 'positive' : 'negative'}>{order.side}</td>
        <td>{fmt(order.limitPrice)}</td><td>{fmt(order.remainingQuantity)} / {fmt(order.originalQuantity)}</td>
        <td><span className="order-state">{order.status}</span></td>
        <td>{order.remainingQuantity > 0 && !['CANCELLED','FILLED','REJECTED'].includes(order.status) && <button className="subtle-button" disabled={pendingId === order.id} onClick={() => cancel(order)}>Cancel</button>}</td>
      </tr>)}
      {!orders.length && <tr><td colSpan="6" className="table-empty">No orders yet. Sign in and place your first limit order.</td></tr>}
    </tbody></table></div>
    <p className="muted tiny">Last 100 persisted orders. Refresh after reconnect; outstanding admissions may not appear until committed.</p>
  </section>;
}

export default function App() {
  const [token, setToken] = useState(initialToken);
  const [symbols, setSymbols] = useState(['AAPL','GOOGL','MSFT']);
  const [symbol, setSymbol] = useState('AAPL');
  const [market, setMarket] = useState(null);
  const [cash, setCash] = useState(null);
  const [holdings, setHoldings] = useState([]);
  const [orders, setOrders] = useState([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const refreshAccount = useCallback(async () => {
    if (!token) return;
    try {
      const [c, h, o] = await Promise.all([exchangeApi.cash(token), exchangeApi.holdings(token), exchangeApi.orders(token)]);
      setCash(c); setHoldings(h); setOrders(o); setError('');
    } catch (err) {
      if (String(err.message).startsWith('401 ')) { sessionStorage.removeItem('exchangex_token'); setToken(''); }
      setError(`Account refresh failed: ${err.message}`);
    }
  }, [token]);
  const refreshMarket = useCallback(async () => {
    try { setMarket(await exchangeApi.snapshot(symbol)); }
    catch (err) { setError(`Market refresh failed: ${err.message}`); }
  }, [symbol]);

  useEffect(() => {
    exchangeApi.instruments().then(values => { if (values?.length) setSymbols(values); }).catch(() => {});
  }, []);
  useEffect(() => { setMarket(null); void refreshMarket(); }, [refreshMarket]);
  useEffect(() => { void refreshAccount(); }, [refreshAccount]);
  useEffect(() => {
    const timer = setInterval(() => { void refreshMarket(); void refreshAccount(); }, 15000);
    return () => clearInterval(timer);
  }, [refreshMarket, refreshAccount]);

  const onOrderChanged = useCallback(() => { void refreshAccount(); }, [refreshAccount]);
  const onReconnect = useCallback(() => { void refreshMarket(); void refreshAccount(); }, [refreshMarket, refreshAccount]);
  const connection = useMarketStream({ symbol, token, onSnapshot: setMarket, onOrderChanged, onReconnect });
  const ticks = market?.recentTrades || [];
  const latest = ticks[0];
  const spread = market?.bestAsk && market?.bestBid ? Number(market.bestAsk.price) - Number(market.bestBid.price) : null;

  function login(accessToken) { sessionStorage.setItem('exchangex_token', accessToken); setToken(accessToken); }
  function logout() { sessionStorage.removeItem('exchangex_token'); setToken(''); setCash(null); setOrders([]); setHoldings([]); setError(''); }

  return <div className="shell">
    <header className="app-header"><div className="brand"><span className="brand-mark">X</span><div><strong>ExchangeX</strong><small>SIMULATED EXCHANGE TERMINAL</small></div></div>
      <div className="header-actions"><span className={`connection ${connection}`}><span className="dot" />{connection === 'live' ? 'Market stream live' : connection === 'connecting' ? 'Connecting' : 'Reconnecting'}</span>{token ? <button className="subtle-button" onClick={logout}>Sign out</button> : <span className="guest">Guest market view</span>}</div>
    </header>
    <main className="main-content">
      <div className="page-title"><div><p className="eyebrow">MARKETS / EQUITIES</p><h1>Trading overview</h1><p className="muted">Real orders, simulated balances. Live updates backed by the durable exchange.</p></div><button className="subtle-button" onClick={() => { refreshMarket(); refreshAccount(); }}>Refresh data</button></div>
      {error && <div role="alert" className="alert">{error}</div>}
      <nav aria-label="Choose instrument" className="symbol-tabs">{symbols.map(s => <button key={s} className={s === symbol ? 'selected' : ''} onClick={() => setSymbol(s)}>{s}</button>)}</nav>
      <div className="market-stats">
        <div className="stat"><span>LAST TRADE</span><strong>{latest ? fmt(latest.price) : '—'}</strong><small>{latest ? nearTime(latest.executedAt) : 'No executions yet'}</small></div>
        <div className="stat"><span>BEST BID</span><strong className="positive">{market?.bestBid ? fmt(market.bestBid.price) : '—'}</strong><small>{market?.bestBid ? `${fmt(market.bestBid.quantity)} available` : 'No buy orders'}</small></div>
        <div className="stat"><span>BEST ASK</span><strong className="negative">{market?.bestAsk ? fmt(market.bestAsk.price) : '—'}</strong><small>{market?.bestAsk ? `${fmt(market.bestAsk.quantity)} available` : 'No sell orders'}</small></div>
        <div className="stat"><span>SPREAD</span><strong>{spread == null ? '—' : fmt(spread)}</strong><small>{market ? `Checkpoint #${fmt(market.sequenceId)}` : 'Loading market'}</small></div>
      </div>
      <div className="layout-grid">
        <div className="left-stack">
          <section className="panel chart-panel"><div className="panel-head"><div><p className="eyebrow">EXECUTION HISTORY</p><h2>{symbol} trade prices</h2></div><span className="small-tag">Last 20 trades</span></div><Sparkline ticks={ticks} /><p className="muted tiny">Visualization of committed execution prices, not external exchange quotes or OHLC candles.</p></section>
          <section className="panel"><div className="panel-head"><h2>Order book · Top level</h2><span className="small-tag">{symbol}</span></div><div className="book-head"><span>BUY / BID</span><span>SELL / ASK</span></div><div className="book-columns"><div className="book-half positive"><strong>{market?.bestBid ? fmt(market.bestBid.price) : '—'}</strong><small>{market?.bestBid ? `${fmt(market.bestBid.quantity)} shares` : 'No bids'}</small></div><div className="book-half negative"><strong>{market?.bestAsk ? fmt(market.bestAsk.price) : '—'}</strong><small>{market?.bestAsk ? `${fmt(market.bestAsk.quantity)} shares` : 'No asks'}</small></div></div><p className="muted tiny">Aggregated remaining quantities at the best price on each side.</p></section>
          <section className="panel"><div className="panel-head"><h2>Recent trades</h2><span className="small-tag">{ticks.length} events</span></div><div className="table-scroll"><table><thead><tr><th>Time</th><th>Price</th><th>Quantity</th></tr></thead><tbody>{ticks.map((tick, i) => <tr key={`${tick.executedAt}-${i}`}><td>{nearTime(tick.executedAt)}</td><td>{fmt(tick.price)}</td><td>{fmt(tick.quantity)}</td></tr>)}{!ticks.length && <tr><td colSpan="3" className="table-empty">No trades for {symbol} yet.</td></tr>}</tbody></table></div></section>
          {token && <OrderHistory orders={orders} token={token} reload={refreshAccount} busy={busy} />}
        </div>
        <aside className="right-stack">
          {token ? <>
            <section className="panel account-panel"><div className="panel-head"><h2>My account</h2><span className="small-tag">SIMULATION</span></div><p className="eyebrow">AVAILABLE CASH</p><div className="balance">{cashFmt(cash?.available)}</div><div className="balance-grid"><div><span>Reserved</span><strong>{cashFmt(cash?.reserved)}</strong></div><div><span>Total</span><strong>{cashFmt(cash?.total)}</strong></div></div><p className="muted tiny">New accounts start at zero. There is no public funding endpoint.</p></section>
            <OrderTicket key={symbol} symbol={symbol} token={token} afterOrder={refreshAccount} />
            <section className="panel"><div className="panel-head"><h2>My holdings</h2><span className="small-tag">{holdings.length} positions</span></div><div className="table-scroll"><table><thead><tr><th>Symbol</th><th>Available</th><th>Reserved</th></tr></thead><tbody>{holdings.map(h => <tr key={h.symbol}><td>{h.symbol}</td><td>{fmt(h.available)}</td><td>{fmt(h.reserved)}</td></tr>)}{!holdings.length && <tr><td colSpan="3" className="table-empty">No holdings.</td></tr>}</tbody></table></div></section>
          </> : <AuthPanel onAuthenticated={login} />}
        </aside>
      </div>
    </main>
    <footer className="footer"><span>ExchangeX · Java 21 / Spring Boot / PostgreSQL / React</span><span>Educational market simulator · Not real securities trading</span></footer>
  </div>;
}
