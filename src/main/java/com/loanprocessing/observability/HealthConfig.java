package com.loanprocessing.observability;

import com.loanprocessing.config.RuntimeConfigurationService;
import com.loanprocessing.document.DocumentStoragePort;
import com.loanprocessing.security.KeyManagementPort;
import org.flywaydb.core.Flyway;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HealthConfig {
    @Bean
    HealthIndicator configPrime(RuntimeConfigurationService config) {
        return () -> config.ready() ? Health.up().withDetail("generation", config.current().generation()).build() : Health.down().build();
    }

    @Bean
    HealthIndicator objectStorage(DocumentStoragePort storage) {
        return () -> storage.head() ? Health.up().build() : Health.down().build();
    }

    @Bean
    HealthIndicator kmsSelfCheck(KeyManagementPort kms) {
        return () -> {
            try {
                kms.selfCheck();
                return Health.up().build();
            } catch (RuntimeException e) {
                return Health.down().build();
            }
        };
    }

    @Bean
    HealthIndicator migrations(Flyway flyway) {
        return () -> flyway.info().current() == null ? Health.down().build() : Health.up().build();
    }
}
