package com.traceability.notifications;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class EmailGatewayConfig {

    @Bean
    @ConditionalOnMissingBean(EmailGateway.class)
    public EmailGateway loggingEmailGateway() {
        return new LoggingEmailGateway();
    }
}
