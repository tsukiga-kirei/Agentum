export function streamPausedStorageKey(runId: string): string {
  return `agentum:stream-paused:${runId}`;
}

export function isStreamPausedByUser(runId: string): boolean {
  if (typeof window === "undefined") {
    return false;
  }
  return window.sessionStorage.getItem(streamPausedStorageKey(runId)) === "1";
}

export function markStreamPausedByUser(runId: string): void {
  if (typeof window === "undefined") {
    return;
  }
  window.sessionStorage.setItem(streamPausedStorageKey(runId), "1");
}

export function clearStreamPausedByUser(runId: string): void {
  if (typeof window === "undefined") {
    return;
  }
  window.sessionStorage.removeItem(streamPausedStorageKey(runId));
}
