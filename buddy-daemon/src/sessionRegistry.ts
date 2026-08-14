import type { BuddySession } from "./session.js";

/** In-process registry of sessions this daemon instance owns. */
class SessionRegistry {
  private sessions = new Map<string, BuddySession>();

  add(session: BuddySession) {
    this.sessions.set(session.info.id, session);
  }

  remove(id: string) {
    this.sessions.delete(id);
  }

  get(id: string) {
    return this.sessions.get(id);
  }

  list() {
    return [...this.sessions.values()].map((s) => s.info);
  }
}

export const sessionRegistry = new SessionRegistry();
