package com.adcheck.analysis.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AnalysisPropertiesTest {

    @Autowired
    private AnalysisProperties properties;

    @Test
    void bindsPipelineVersionAndReuseTtl() {
        assertThat(properties.getPipelineVersion()).isEqualTo("v1");
        assertThat(properties.getReuseTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.getAsync().getCorePoolSize()).isEqualTo(1);
        assertThat(properties.getAsync().getMaxPoolSize()).isEqualTo(1);
        assertThat(properties.getAsync().getQueueCapacity()).isEqualTo(20);
        assertThat(properties.getAsync().getThreadNamePrefix()).isEqualTo("analysis-test-");
    }
}
