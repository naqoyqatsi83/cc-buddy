export interface PeerInfo {
  id: string;
  name: string;
  ip: string;
  port: number;
  connected: boolean;
  pairedAt: string;
  /** Round-trip time of the last ping/pong exchange, in ms. */
  latencyMs?: number;
  /** ISO timestamp of the last pong received from this peer. */
  lastSeenAt?: string;
}

export interface SessionInfo {
  id: string;
  cwd: string;
  controlPort: number;
  startedAt: string;
  peers: PeerInfo[];
}

export interface ScannedPhone {
  name: string;
  ip: string;
  port: number;
}
