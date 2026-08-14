export interface PeerInfo {
  id: string;
  name: string;
  ip: string;
  port: number;
  connected: boolean;
  pairedAt: string;
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
