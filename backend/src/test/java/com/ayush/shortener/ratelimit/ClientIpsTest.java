package com.ayush.shortener.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpsTest {

    @Test
    void ipv4AddressesAreCountedIndividually() {
        assertThat(ClientIps.key("203.0.113.9")).isEqualTo("203.0.113.9");
        assertThat(ClientIps.key("203.0.113.10")).isNotEqualTo(ClientIps.key("203.0.113.9"));
        assertThat(ClientIps.key("127.0.0.1")).isEqualTo("127.0.0.1");
    }

    @Test
    void ipv6AddressesInTheSame64AreOneClientBecauseOneSubscriberOwnsTheWholeBlock() {
        String a = ClientIps.key("2001:db8:abcd:12:1111:2222:3333:4444");
        String b = ClientIps.key("2001:db8:abcd:12:ffff:eeee:dddd:cccc");
        assertThat(a).isEqualTo(b).endsWith("/64");
        assertThat(ClientIps.key("2001:db8:abcd:13::1")).isNotEqualTo(a);   // a different /64 is a different client
    }

    @Test
    void ipv6AbbreviationsDoNotMatter() {
        assertThat(ClientIps.key("2001:db8::1")).isEqualTo(ClientIps.key("2001:0db8:0000:0000:0000:0000:0000:0001"));
    }

    @Test
    void anIpv4MappedIpv6AddressIsTheSameClientAsItsIpv4Form() {
        assertThat(ClientIps.key("::ffff:203.0.113.9")).isEqualTo("203.0.113.9");
    }

    @Test
    void missingOrUnparseableAddressesStillGiveAStableBoundedKey() {
        assertThat(ClientIps.key(null)).isEqualTo("unknown");
        assertThat(ClientIps.key("  ")).isEqualTo("unknown");
        assertThat(ClientIps.key("not an ip")).isNotBlank();
        assertThat(ClientIps.key("x".repeat(500))).hasSizeLessThanOrEqualTo(64);
    }
}
