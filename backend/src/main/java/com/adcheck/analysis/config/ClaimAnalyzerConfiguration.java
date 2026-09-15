package com.adcheck.analysis.config;

import com.adcheck.analysis.service.ClaimAnalyzer;
import com.adcheck.analysis.service.MockClaimAnalyzer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the fallback {@link ClaimAnalyzer} bean.
 *
 * <p>{@code @ConditionalOnMissingBean} must live on a {@code @Bean} factory method inside a
 * {@code @Configuration} class, not directly on {@link MockClaimAnalyzer} itself: since that
 * class implements the very type ({@code ClaimAnalyzer}) the condition searches for, placing the
 * annotation there makes Spring's self-exclusion unreliable and the bean fails to register at
 * all (verified: a plain {@code @Component @ConditionalOnMissingBean(ClaimAnalyzer.class)} on
 * {@code MockClaimAnalyzer} left zero {@code ClaimAnalyzer} beans in the context). A
 * {@code @Bean} method has an unambiguous "bean this method produces" to exclude from its own
 * search, so it works correctly.
 *
 * <p>Once GeminiClaimAnalyzer is ported as {@code @Service @Conditional(GeminiApiKeyPresentCondition.class)},
 * no change is needed here: when its condition matches, it registers as the other
 * {@code ClaimAnalyzer} bean, and this method's {@code @ConditionalOnMissingBean} sees it and
 * steps aside automatically.
 */
@Configuration
public class ClaimAnalyzerConfiguration {

    @Bean
    @ConditionalOnMissingBean(ClaimAnalyzer.class)
    public ClaimAnalyzer mockClaimAnalyzer() {
        return new MockClaimAnalyzer();
    }
}
