package com.adcheck.analysis.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True only when {@code gemini.api-key} resolves to a non-blank value.
 *
 * <p>{@code application.yaml} declares {@code gemini.api-key: ${GEMINI_API_KEY:}} with an
 * empty-string default so startup never fails when the env var is unset. That means
 * {@code @ConditionalOnProperty(name = "gemini.api-key")} cannot be used here — it treats any
 * resolved value, including {@code ""}, as "present" (its {@code havingValue} only matches
 * against a fixed literal, not "non-blank"). This condition reads the property directly and
 * checks {@link String#isBlank()} instead.
 *
 * <p>Apply to the real analyzer once it is ported:
 * <pre>{@code
 * @Service
 * @Conditional(GeminiApiKeyPresentCondition.class)
 * public class GeminiClaimAnalyzer implements ClaimAnalyzer { ... }
 * }</pre>
 * Paired with {@link ClaimAnalyzerConfiguration}'s {@code @ConditionalOnMissingBean(ClaimAnalyzer.class)}
 * fallback bean, exactly one {@code ClaimAnalyzer} bean registers regardless of whether
 * {@code GEMINI_API_KEY} is set.
 */
public class GeminiApiKeyPresentCondition implements Condition {

    static final String PROPERTY_NAME = "gemini.api-key";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String apiKey = context.getEnvironment().getProperty(PROPERTY_NAME);
        return apiKey != null && !apiKey.isBlank();
    }
}
