package com.adcheck.analysis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "adcheck.analysis")
public class AnalysisProperties {

    private String pipelineVersion;
    private Duration reuseTtl;

    public String getPipelineVersion() {
        return pipelineVersion;
    }

    public void setPipelineVersion(String pipelineVersion) {
        this.pipelineVersion = pipelineVersion;
    }

    public Duration getReuseTtl() {
        return reuseTtl;
    }

    public void setReuseTtl(Duration reuseTtl) {
        this.reuseTtl = reuseTtl;
    }
}
