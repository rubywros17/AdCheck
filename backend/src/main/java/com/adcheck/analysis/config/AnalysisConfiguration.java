package com.adcheck.analysis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class AnalysisConfiguration {

    @Bean
    public Clock analysisClock() {
        return Clock.systemUTC();
    }
}
