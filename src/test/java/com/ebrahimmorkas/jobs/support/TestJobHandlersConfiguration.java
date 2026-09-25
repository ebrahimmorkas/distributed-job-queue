package com.ebrahimmorkas.jobs.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class TestJobHandlersConfiguration {

    @Bean
    CountingJobHandler countingJobHandler() {
        return new CountingJobHandler();
    }
}
