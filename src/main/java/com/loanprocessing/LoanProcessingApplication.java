package com.loanprocessing;

import com.loanprocessing.config.RuntimeConfigurationService;
import com.loanprocessing.decision.DecisionService;
import com.loanprocessing.model.ConfigSnapshot;
import com.loanprocessing.security.KeyManagementPort;
import com.loanprocessing.security.FieldEncryptor;
import com.loanprocessing.security.SecretProvider;
import com.loanprocessing.support.FaultDataSource;
import com.loanprocessing.support.FaultSwitch;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class LoanProcessingApplication {
    public static void main(String[] args) {
        SpringApplication.run(LoanProcessingApplication.class, args);
    }

    @Bean
    ApplicationRunner prime(RuntimeConfigurationService config, KeyManagementPort kms,
                            @org.springframework.beans.factory.annotation.Value("${loan.production:false}") boolean production,
                            @org.springframework.beans.factory.annotation.Value("${loan.config.four-eyes-enabled:true}") boolean fourEyes,
                            @org.springframework.beans.factory.annotation.Value("${loan.security.mode:header}") String securityMode,
                            @org.springframework.beans.factory.annotation.Value("${loan.security.issuer-uri:}") String issuer,
                            @org.springframework.beans.factory.annotation.Value("${loan.malware.mode:fail-closed}") String malwareMode,
                            @org.springframework.beans.factory.annotation.Value("${loan.llm.client:openai}") String llmClient,
                            @org.springframework.beans.factory.annotation.Value("${loan.fault-injection:false}") boolean faultInjection) {
        return args -> {
            config.loadOrThrow();
            ConfigSnapshot snapshot = config.current();
            ProductionGate.assertBoot(production, fourEyes, securityMode, issuer, malwareMode, llmClient, faultInjection,
                    snapshot.llmLogRequests(), snapshot.scanRequired());
            kms.selfCheck();
        };
    }

    @Bean
    DecisionService decisionService() {
        return new DecisionService();
    }

    @Bean
    FieldEncryptor fieldEncryptor(KeyManagementPort kms, SecretProvider secrets, ObjectMapper mapper) {
        return new FieldEncryptor(kms, secrets, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "loan.fault-injection", havingValue = "true")
    BeanPostProcessor faultDataSource(FaultSwitch faults) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof FaultDataSource) && beanName.toLowerCase().contains("datasource")) {
                    return new FaultDataSource(dataSource, faults);
                }
                return bean;
            }
        };
    }
}
