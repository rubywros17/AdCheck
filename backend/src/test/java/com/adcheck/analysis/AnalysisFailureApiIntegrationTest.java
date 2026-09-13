package com.adcheck.analysis;

import com.adcheck.analysis.domain.Analysis;
import com.adcheck.analysis.domain.AnalysisStatus;
import com.adcheck.analysis.repository.AnalysisRepository;
import com.adcheck.analysis.service.ClaimAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class AnalysisFailureApiIntegrationTest {

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private AnalysisRepository analysisRepository;

    @MockitoBean
    private ClaimAnalyzer claimAnalyzer;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        analysisRepository.deleteAll();
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
    }

    @Test
    void preservesCommittedRowAsFailedWhenAnalyzerThrows() throws Exception {
        when(claimAnalyzer.analyze(anyList())).thenAnswer(invocation -> {
            Analysis processing = analysisRepository.findAll().getFirst();
            assertThat(processing.getStatus()).isEqualTo(AnalysisStatus.PROCESSING);
            throw new IllegalStateException("Mock 분석 실패");
        });

        mockMvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pageUrl": "https://example.com/product/failure",
                                  "pageTitle": "실패 테스트",
                                  "productName": "테스트 상품",
                                  "texts": [{"content": "광고 문구", "selector": "#claim"}],
                                  "images": []
                                }
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));

        verify(claimAnalyzer).analyze(anyList());
        assertThat(analysisRepository.findAll()).singleElement().satisfies(failed -> {
            assertThat(failed.getStatus()).isEqualTo(AnalysisStatus.FAILED);
            assertThat(failed.getErrorMessage()).isEqualTo("Mock 분석 실패");
            assertThat(failed.getResultJson()).isNull();
            assertThat(failed.getCompletedAt()).isNull();
            assertThat(failed.getUpdatedAt()).isAfterOrEqualTo(failed.getCreatedAt());
        });
    }

    @Test
    void storesExceptionClassNameWhenFailureMessageIsNull() throws Exception {
        when(claimAnalyzer.analyze(anyList())).thenThrow(new RuntimeException());

        performFailingAnalysis("null-message");

        assertThat(analysisRepository.findAll()).singleElement().satisfies(failed -> {
            assertThat(failed.getStatus()).isEqualTo(AnalysisStatus.FAILED);
            assertThat(failed.getErrorMessage()).isEqualTo("RuntimeException");
            assertThat(failed.getErrorMessage()).isNotBlank();
        });
    }

    @Test
    void storesFixedFallbackWhenMessageAndSimpleNameAreBlank() throws Exception {
        RuntimeException anonymousFailure = new RuntimeException() {
        };
        when(claimAnalyzer.analyze(anyList())).thenThrow(anonymousFailure);

        performFailingAnalysis("anonymous-exception");

        assertThat(analysisRepository.findAll()).singleElement().satisfies(failed -> {
            assertThat(failed.getStatus()).isEqualTo(AnalysisStatus.FAILED);
            assertThat(failed.getErrorMessage()).isEqualTo("분석 처리 중 오류가 발생했습니다.");
            assertThat(failed.getErrorMessage()).isNotBlank();
        });
    }

    private void performFailingAnalysis(String requestKey) throws Exception {
        mockMvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pageUrl": "https://example.com/product/%s",
                                  "pageTitle": "실패 테스트",
                                  "productName": "테스트 상품",
                                  "texts": [{"content": "광고 문구", "selector": "#claim"}],
                                  "images": []
                                }
                                """.formatted(requestKey)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
    }
}
