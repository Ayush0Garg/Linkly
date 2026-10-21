package com.ayush.shortener.ratelimit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;

/** Turns a client address into the thing a limit is counted against. */
public final class ClientIps {

    private ClientIps() {
    }

    /**
     * IPv4 addresses count individually. IPv6 addresses count by their /64 network: one subscriber usually owns a whole
     * /64, so counting single addresses would let an attacker dodge every limit by rotating through billions of them.
     */
    public static String key(String address) {
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        try {
            // The servlet container gives us an address literal, so this never performs a DNS lookup.
            InetAddress parsed = InetAddress.getByName(address);
            byte[] bytes = parsed.getAddress();
            if (bytes.length == 16) {
                return HexFormat.of().formatHex(bytes, 0, 8) + "::/64";
            }
            return parsed.getHostAddress(); // an IPv4-mapped IPv6 address has already become IPv4 here
        } catch (UnknownHostException | RuntimeException e) {
            return address.length() > 64 ? address.substring(0, 64) : address;
        }
    }
}
