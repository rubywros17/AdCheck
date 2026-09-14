package com.adcheck.analysis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@EnableAsync
@Configuration(proxyBeanMethods = false)
public class AnalysisAsyncConfiguration {

    public static final String EXECUTOR_NAME = "analysisTaskExecutor";

    @Bean(name = EXECUTOR_NAME)
    public ThreadPoolTaskExecutor analysisTaskExecutor(AnalysisProperties properties) {
        AnalysisProperties.Async settings = properties.getAsync();
        settings.validatePoolSizeOrder();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(settings.getCorePoolSize());
        executor.setMaxPoolSize(settings.getMaxPoolSize());
        executor.setQueueCapacity(settings.getQueueCapacity());
        executor.setThreadNamePrefix(settings.getThreadNamePrefix());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
