import Bonjour, { Service } from "bonjour-service";
import { MDNS_TYPE_BARE } from "./constants.js";
import type { ScannedPhone } from "./types.js";

const SCAN_WINDOW_MS = 3000;

/**
 * Browses the LAN for phones advertising `_buddycc._tcp` (build-order
 * step 7). Opens a fresh browser per call rather than keeping one running
 * continuously — `/buddy-scan` is an on-demand action, not a background
 * watch.
 */
export async function scanForPhones(): Promise<ScannedPhone[]> {
  const bonjour = new Bonjour();
  const found = new Map<string, ScannedPhone>();

  return new Promise((resolve) => {
    const browser = bonjour.find({ type: MDNS_TYPE_BARE }, (service: Service) => {
      const ip = (service.addresses ?? []).find(isIPv4);
      if (!ip) return;
      found.set(`${ip}:${service.port}`, {
        name: service.name,
        ip,
        port: service.port,
      });
    });

    setTimeout(() => {
      browser.stop();
      bonjour.destroy();
      resolve([...found.values()]);
    }, SCAN_WINDOW_MS);
  });
}

function isIPv4(addr: string): boolean {
  return /^\d{1,3}(\.\d{1,3}){3}$/.test(addr);
}
