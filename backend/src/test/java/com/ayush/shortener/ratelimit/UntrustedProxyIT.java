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

/** When the connecting address is NOT a configured proxy, a client-supplied X-Forwarded-For must be ignored. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.tomcat.remoteip.internal-proxies=10\\.9\\.9\\.9")
@ActiveProfiles("embedded")
@Import(EchoClientIp.class)
class UntrustedProxyIT {

    @Value("${local.server.port}") int port;

    @Test
    void aClientCannotChooseItsOwnAddressByLyingInTheHeader() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/__test/ip"))
                .header("X-Forwarded-For", "203.0.113.9").build();
        String seen = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
        assertThat(seen).as("the spoofed header is ignored: 127.0.0.1 is not a trusted proxy here").isEqualTo("127.0.0.1");
    }
}
