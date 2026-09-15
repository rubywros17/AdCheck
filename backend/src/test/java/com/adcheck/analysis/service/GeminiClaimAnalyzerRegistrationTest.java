package com.adcheck.analysis.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ClaimAnalyzerConditionalWiringTest}는 격리된 {@code ApplicationContextRunner}로
 * "키 없음 -> Mock" 경로만 빠르게 검증한다. {@code gemini.api-key}가 있을 때 실제로
 * {@link GeminiClaimAnalyzer}가 뜨는지는 그 경로의 의존성 체인
 * (DetailTextCleaner/GeminiOcrService/ProductContentExtractionService/GeminiClient/
 * IngredientMatchingService, 그리고 IngredientMatchingService가 필요로 하는 JPA
 * Repository들)이 전부 갖춰져야 확인할 수 있어, 여기서는 전체 Spring Boot 컨텍스트를
 * 띄워서 확인한다. 생성자 호출만으로는 Gemini API에 실제로 접속하지 않으므로
 * (호출은 {@code analyze()} 실행 시점에만 일어남) 이 테스트는 네트워크 없이 통과한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "gemini.api-key=dummy-test-key-1234")
class GeminiClaimAnalyzerRegistrationTest {

    @Autowired
    private ClaimAnalyzer claimAnalyzer;

    @Test
    void registersGeminiClaimAnalyzerInsteadOfMockWhenApiKeyIsPresent() {
        assertThat(claimAnalyzer).isInstanceOf(GeminiClaimAnalyzer.class);
        assertThat(claimAnalyzer).isNotInstanceOf(MockClaimAnalyzer.class);
    }
}
