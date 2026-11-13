# SLDNS engine integration notes

## Confirmed server-side command

The upstream SLDNS install script configures its client like this:

```sh
sldns-client -udp 8.8.8.8:53 --pubkey-file /etc/slowdns/server.pub <nameserver> 127.0.0.1:3369
```

The same script configures the server as:

```sh
sldns-server -udp :5300 -privkey-file /etc/slowdns/server.key <nameserver> 127.0.0.1:2269
```

This establishes the expected protocol family and the role of the public key, nameserver, UDP resolver, and SSH endpoint. It does **not** establish that the published client executable can run on Android/ARM64.

## Important compatibility gate

The upstream repository currently publishes `sldns-client` as a prebuilt executable (about 9 MB) rather than exposing its implementation source in the repository tree. Before packaging it, CI must verify:
- ELF architecture is AArch64 / ARM64.
- Android/Bionic compatibility (a Linux/glibc binary is not automatically Android-compatible).
- The executable can start on Android and accepts the expected arguments.
- Its local TCP forwarding behavior is understood and can be connected to an SSH client.

Do not copy the server's private key into this Android project. Only a user's server public key belongs in the client profile.

## VPN data path still required

A complete Android VPN needs a local SSH transport and a TUN-to-SOCKS/TCP bridge (or an equivalent userspace IP stack). Starting the DNS client alone is not enough to route all device traffic. The app must not display CONNECTED until the DNS tunnel, SSH transport, and VPN interface are all healthy.

## Reference

Upstream SLDNS: https://github.com/fisabiliyusri/SLDNS
