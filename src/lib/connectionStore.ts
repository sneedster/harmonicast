import type { Connection } from '@/types';
import { getConnection, saveConnection, deleteConnection, type ConnectionInfo } from '@/lib/api';

export async function loadConnection(): Promise<ConnectionInfo | null> {
  const info = await getConnection();
  if (!info.configured) return null;
  return info;
}

export function connectionFromInfo(info: ConnectionInfo): Connection {
  return {
    baseUrl: info.baseUrl!,
    username: info.username!,
    password: '',
    serverName: info.serverName,
  };
}

export async function saveConn(conn: Connection): Promise<void> {
  await saveConnection(conn);
}

export async function clearConnection(): Promise<void> {
  await deleteConnection();
}

export async function isHost(): Promise<boolean> {
  const info = await getConnection();
  return info.isHost ?? false;
}
