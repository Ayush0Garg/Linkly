package com.ayush.shortener.ratelimit;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Over a real HTTP connection (Tomcat's own header handling is not part of MockMvc): by default X-Forwarded-For is
 * honoured only when it arrives from a private or loopback proxy address, such as the 127.0.0.1 used here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("embedded")
@Import(EchoClientIp.class)
class TrustedProxyIT {

    @Value("${local.server.port}") int port;

    String clientAddressSeenWhenSending(String forwardedFor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/__test/ip"));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void aTrustedProxyMayTellUsTheRealClientAddress() throws Exception {
        assertThat(clientAddressSeenWhenSending("203.0.113.9")).isEqualTo("203.0.113.9");
    }

    @Test
    void theLastUntrustedHopInAChainIsTheClient() throws Exception {
        // client -> (unknown) -> trusted proxy: the header lists addresses oldest first, the nearest hop last
        assertThat(clientAddressSeenWhenSending("203.0.113.9, 198.51.100.4")).isEqualTo("198.51.100.4");
    }

    @Test
    void withoutTheHeaderItIsTheConnectionAddress() throws Exception {
        assertThat(clientAddressSeenWhenSending(null)).isEqualTo("127.0.0.1");
    }
}
