package com.ayush.shortener.auth;

import com.ayush.shortener.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class GoogleConfig {

    @Bean
    GoogleIdTokenVerifier googleIdTokenVerifier(AppProperties properties) {
        return GoogleIdTokenVerifier.forClientId(properties.auth().googleClientId());
    }
}
