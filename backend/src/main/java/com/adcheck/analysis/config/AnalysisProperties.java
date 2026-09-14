package com.adcheck.analysis.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Component
@Validated
@ConfigurationProperties(prefix = "adcheck.analysis")
public class AnalysisProperties {

    private String pipelineVersion;

    @NotNull(message = "adcheck.analysis.reuse-ttl-clean must not be null")
    private Duration reuseTtlClean = Duration.ofDays(30);

    @NotNull(message = "adcheck.analysis.reuse-ttl-with-finding must not be null")
    private Duration reuseTtlWithFinding = Duration.ofDays(7);

    @Valid
    private final Async async = new Async();

    public String getPipelineVersion() {
        return pipelineVersion;
    }

    public void setPipelineVersion(String pipelineVersion) {
        this.pipelineVersion = pipelineVersion;
    }

    public Duration getReuseTtlClean() {
        return reuseTtlClean;
    }

    public void setReuseTtlClean(Duration reuseTtlClean) {
        this.reuseTtlClean = reuseTtlClean;
    }

    public Duration getReuseTtlWithFinding() {
        return reuseTtlWithFinding;
    }

    public void setReuseTtlWithFinding(Duration reuseTtlWithFinding) {
        this.reuseTtlWithFinding = reuseTtlWithFinding;
    }

    public Async getAsync() {
        return async;
    }

    public static class Async {

        @Min(value = 1, message = "adcheck.analysis.async.core-pool-size must be at least 1")
        private int corePoolSize = 2;

        @Min(value = 1, message = "adcheck.analysis.async.max-pool-size must be at least 1")
        private int maxPoolSize = 4;

        @Min(value = 1, message = "adcheck.analysis.async.queue-capacity must be at least 1")
        private int queueCapacity = 20;

        @NotBlank(message = "adcheck.analysis.async.thread-name-prefix must not be blank")
        private String threadNamePrefix = "analysis-";

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }

        void validatePoolSizeOrder() {
            if (corePoolSize > maxPoolSize) {
                throw new IllegalStateException(
                        "adcheck.analysis.async.core-pool-size (" + corePoolSize
                                + ") must be less than or equal to "
                                + "adcheck.analysis.async.max-pool-size (" + maxPoolSize + ")"
                );
            }
        }
    }
}
