import type { ScannedPhone } from "./types.js";

/**
 * TODO(build-order step 7): implement with a pure-JS mDNS lib
 * (bonjour-service / multicast-dns) browsing for `_buddycc._tcp`.
 * Left as a stub returning no results so `/buddy-scan` already works
 * end-to-end (empty list) ahead of that step — pairing in the meantime
 * happens via `/buddy-pair <ip> <pin>` with a manually-read IP.
 */
export async function scanForPhones(): Promise<ScannedPhone[]> {
  return [];
}
