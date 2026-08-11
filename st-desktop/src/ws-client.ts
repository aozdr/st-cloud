import WebSocket from 'ws';
import { getToken } from './api-client';
import { getServerUrl } from './server-config';

/**
 * 同步 WebSocket 客户端
 * <p>
 * 连接服务端 /api/sync/ws?token=<JWT>，接收变更推送通知。
 * 收到 "change" 事件时触发 onChange 回调，由 sync-manager 调度对应引擎增量同步。
 * <p>
 * 断线自动重连（指数退避，1s -> 2s -> 4s -> ... -> 60s 上限）。
 * 心跳：每 25s 发送 "ping"，服务端回 "pong"。
 */
export class SyncWsClient {
  private ws: WebSocket | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private reconnectDelay = 1000;
  private readonly MAX_RECONNECT_DELAY = 60_000;
  private readonly HEARTBEAT_INTERVAL = 25_000;
  private running = false;
  private onChangeCallback: (() => void) | null = null;
  private onStatusCallback: ((connected: boolean) => void) | null = null;

  /** 设置变更通知回调 */
  onChange(cb: () => void): void {
    this.onChangeCallback = cb;
  }

  /** 设置连接状态回调 */
  onStatus(cb: (connected: boolean) => void): void {
    this.onStatusCallback = cb;
  }

  /** 启动 WebSocket 客户端 */
  start(): void {
    if (this.running) return;
    this.running = true;
    this.connect();
  }

  /** 停止 WebSocket 客户端 */
  stop(): void {
    this.running = false;
    this.clearTimers();
    if (this.ws) {
      try {
        this.ws.removeAllListeners();
        this.ws.close();
      } catch { /* ignore */ }
      this.ws = null;
    }
    this.onStatusCallback?.(false);
  }

  /** 当前是否已连接 */
  isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  private connect(): void {
    if (!this.running) return;

    const token = getToken();
    if (!token) {
      console.log('[ws] no token, retrying in 3s');
      this.scheduleReconnect(3000);
      return;
    }

    const httpUrl = getServerUrl();
    const wsUrl = httpUrl.replace(/^http/, 'ws') + '/api/sync/ws?token=' + encodeURIComponent(token);

    console.log('[ws] connecting to', wsUrl.replace(/token=[^&]+/, 'token=***'));

    try {
      this.ws = new WebSocket(wsUrl);
    } catch (err) {
      console.error('[ws] create failed:', err);
      this.scheduleReconnect(this.reconnectDelay);
      return;
    }

    this.ws.on('open', () => {
      console.log('[ws] connected');
      this.reconnectDelay = 1000; // 重置退避
      this.onStatusCallback?.(true);
      this.startHeartbeat();
    });

    this.ws.on('message', (data: WebSocket.RawData) => {
      try {
        const msg = JSON.parse(data.toString());
        if (msg.event === 'change') {
          console.log('[ws] change notification: logId=', msg.logId);
          this.onChangeCallback?.();
        }
        // pong 响应由 on('pong') 或消息处理
        if (data.toString() === 'pong') {
          // heartbeat response
        }
      } catch {
        // 非 JSON 消息（如 pong），忽略
      }
    });

    this.ws.on('close', (code: number, reason: Buffer) => {
      console.log(`[ws] closed: code=${code}, reason=${reason?.toString() || ''}`);
      this.onStatusCallback?.(false);
      this.stopHeartbeat();
      if (this.running) {
        this.scheduleReconnect(this.reconnectDelay);
      }
    });

    this.ws.on('error', (err: Error) => {
      console.error('[ws] error:', err.message);
      // close 事件会随后触发，由 close 处理重连
    });
  }

  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      if (this.ws?.readyState === WebSocket.OPEN) {
        try {
          this.ws.send('ping');
        } catch {
          // 发送失败，连接可能已断
        }
      }
    }, this.HEARTBEAT_INTERVAL);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  private scheduleReconnect(delay: number): void {
    this.clearReconnectTimer();
    console.log(`[ws] reconnecting in ${delay / 1000}s`);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectDelay = Math.min(this.reconnectDelay * 2, this.MAX_RECONNECT_DELAY);
      this.connect();
    }, delay);
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private clearTimers(): void {
    this.clearReconnectTimer();
    this.stopHeartbeat();
  }
}