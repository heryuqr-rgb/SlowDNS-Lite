package id.litevpn.slowdns

import android.net.VpnService

/**
 * Declares the Android VPN endpoint so VpnService.prepare() can request user consent.
 * TUN establishment and packet forwarding are intentionally not enabled until a
 * packet engine is integrated; starting a black-hole TUN would cut off user traffic.
 */
class SlowDnsVpnService : VpnService()
