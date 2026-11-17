package com.ags.transactions;

import java.time.Clock;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConfigurationPropertiesScan
class AppConfig {

    /** Injected rather than calling Instant.now() so tests can control time (e.g. key expiry). */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
