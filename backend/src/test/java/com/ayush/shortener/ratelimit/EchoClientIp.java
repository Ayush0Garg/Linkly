package com.ayush.shortener.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only: a public endpoint that reports the client address the application sees. */
@TestConfiguration
public class EchoClientIp {

    @RestController
    static class Echo {
        @GetMapping("/__test/ip")
        String ip(HttpServletRequest request) {
            return request.getRemoteAddr();
        }
    }

    @Bean
    @Order(0)
    SecurityFilterChain testChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/__test/**").authorizeHttpRequests(a -> a.anyRequest().permitAll()).csrf(c -> c.disable());
        return http.build();
    }
}
