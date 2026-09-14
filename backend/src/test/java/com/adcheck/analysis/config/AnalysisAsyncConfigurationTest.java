package com.adcheck.analysis.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisAsyncConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class
            ))
            .withUserConfiguration(
                    AnalysisProperties.class,
                    AnalysisAsyncConfiguration.class
            );

    @Test
    void createsBoundedExecutorWithAbortPolicy() {
        AnalysisProperties properties = new AnalysisProperties();
        ThreadPoolTaskExecutor executor =
                new AnalysisAsyncConfiguration().analysisTaskExecutor(properties);
        executor.initialize();

        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("analysis-");
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity())
                    .isEqualTo(20);
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void startsWithDefaultSettings() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ThreadPoolTaskExecutor executor = context.getBean(
                    AnalysisAsyncConfiguration.EXECUTOR_NAME,
                    ThreadPoolTaskExecutor.class
            );
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity())
                    .isEqualTo(20);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("analysis-");
        });
    }

    @ParameterizedTest
    @MethodSource("invalidMinimumSettings")
    void rejectsNonPositiveSettingsAtStartup(String propertyName, int value) {
        contextRunner
                .withPropertyValues(propertyName + "=" + value)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(propertyName)
                            .hasStackTraceContaining("must be at least 1");
                });
    }

    @Test
    void rejectsCorePoolSizeGreaterThanMaxPoolSizeAtStartup() {
        contextRunner
                .withPropertyValues(
                        "adcheck.analysis.async.core-pool-size=5",
                        "adcheck.analysis.async.max-pool-size=4"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("adcheck.analysis.async.core-pool-size (5)")
                            .hasStackTraceContaining("adcheck.analysis.async.max-pool-size (4)");
                });
    }

    @Test
    void rejectsBlankThreadNamePrefixAtStartup() {
        String propertyName = "adcheck.analysis.async.thread-name-prefix";
        contextRunner
                .withPropertyValues(propertyName + "=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(propertyName)
                            .hasStackTraceContaining("must not be blank");
                });
    }

    private static Stream<Arguments> invalidMinimumSettings() {
        return Stream.of(
                Arguments.of("adcheck.analysis.async.core-pool-size", 0),
                Arguments.of("adcheck.analysis.async.max-pool-size", 0),
                Arguments.of("adcheck.analysis.async.queue-capacity", 0),
                Arguments.of("adcheck.analysis.async.core-pool-size", -1),
                Arguments.of("adcheck.analysis.async.max-pool-size", -1),
                Arguments.of("adcheck.analysis.async.queue-capacity", -1)
        );
    }
}
