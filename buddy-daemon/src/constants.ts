/** Fixed port the CC Buddy Android app listens on for the daemon's WS connection. */
export const PHONE_WS_PORT = 8765;

/**
 * mDNS service type the phone advertises and the daemon browses for.
 * Android's NsdServiceInfo wants the full `_buddycc._tcp` form;
 * bonjour-service wants the bare name and appends `._tcp.local` itself —
 * both constants describe the same service, just in each library's
 * expected shape.
 */
export const MDNS_SERVICE_TYPE = "_buddycc._tcp";
export const MDNS_TYPE_BARE = "buddycc";
