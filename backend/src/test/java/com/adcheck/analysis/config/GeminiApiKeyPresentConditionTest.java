package com.adcheck.analysis.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeminiApiKeyPresentConditionTest {

    private final GeminiApiKeyPresentCondition condition = new GeminiApiKeyPresentCondition();

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " "})
    void doesNotMatchWhenApiKeyIsMissingOrBlank(String apiKey) {
        assertThat(condition.matches(contextWithApiKey(apiKey), null)).isFalse();
    }

    @Test
    void matchesWhenApiKeyHasARealValue() {
        assertThat(condition.matches(contextWithApiKey("real-key"), null)).isTrue();
    }

    private ConditionContext contextWithApiKey(String apiKey) {
        Environment environment = mock(Environment.class);
        when(environment.getProperty(GeminiApiKeyPresentCondition.PROPERTY_NAME)).thenReturn(apiKey);
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(environment);
        return context;
    }
}
