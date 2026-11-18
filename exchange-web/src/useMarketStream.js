import { useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';

export function useMarketStream({ symbol, token, onSnapshot, onOrderChanged, onReconnect }) {
  const [status, setStatus] = useState('connecting');
  const callbacks = useRef({ onSnapshot, onOrderChanged, onReconnect });
  useEffect(() => { callbacks.current = { onSnapshot, onOrderChanged, onReconnect }; },
    [onSnapshot, onOrderChanged, onReconnect]);

  useEffect(() => {
    if (!symbol) return;
    let active = true;
    const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const brokerURL = `${scheme}//${window.location.host}/ws`;
    const client = new Client({
      brokerURL,
      connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
      reconnectDelay: 3000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        if (!active) return;
        setStatus('live');
        client.subscribe(`/topic/market/${symbol}`, frame => {
          try { callbacks.current.onSnapshot(JSON.parse(frame.body)); } catch { /* discard malformed data */ }
        });
        if (token) client.subscribe('/user/queue/orders', frame => {
          try { callbacks.current.onOrderChanged(JSON.parse(frame.body)); } catch { /* use REST resync */ }
        });
        callbacks.current.onReconnect();
      },
      onStompError: () => { if (active) setStatus('error'); },
      onWebSocketClose: () => { if (active) setStatus('reconnecting'); },
      onWebSocketError: () => { if (active) setStatus('reconnecting'); }
    });
    setStatus('connecting');
    client.activate();
    return () => { active = false; void client.deactivate(); };
  }, [symbol, token]);

  return status;
}
