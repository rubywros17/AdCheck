package com.adcheck.analysis.service;

import com.adcheck.analysis.config.ClaimAnalyzerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link ClaimAnalyzerConfiguration}'s {@code @ConditionalOnMissingBean(ClaimAnalyzer.class)}
 * preserves today's behavior: with no other {@code ClaimAnalyzer} bean present (i.e. no
 * {@code gemini.api-key}, matching the current, Gemini-less state of the codebase),
 * {@link MockClaimAnalyzer} is the sole bean registered. Once GeminiClaimAnalyzer is ported, that
 * class registers under {@code @Conditional(GeminiApiKeyPresentCondition.class)} and this mock
 * steps aside instead.
 */
class ClaimAnalyzerConditionalWiringTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(ClaimAnalyzerConfiguration.class);

    @Test
    void mockClaimAnalyzerIsTheSoleBeanWithoutAGeminiApiKey() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ClaimAnalyzer.class);
            assertThat(context.getBean(ClaimAnalyzer.class)).isInstanceOf(MockClaimAnalyzer.class);
        });
    }
}
