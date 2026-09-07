package com.adcheck.analysis;

import com.adcheck.analysis.domain.Analysis;
import com.adcheck.analysis.domain.AnalysisStatus;
import com.adcheck.analysis.repository.AnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AnalysisApiIntegrationTest {

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private AnalysisRepository analysisRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
    }

    @Test
    void createsCompletedAnalysisWithoutAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pageUrl": "https://example.com/product/123",
                                  "pageTitle": "루테인 건강기능식품",
                                  "productName": "OO 루테인",
                                  "texts": [
                                    {
                                      "content": "시력을 회복하고 노안을 예방합니다.",
                                      "selector": "#product-detail p:nth-child(3)"
                                    },
                                    {
                                      "content": "눈 건강에 도움을 줄 수 있습니다.",
                                      "selector": "#product-detail p:nth-child(5)"
                                    }
                                  ],
                                  "images": []
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.analysisId").isNumber())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.summary.findingCount").value(1))
                .andExpect(jsonPath("$.summary.officialFunctionMatchedCount").value(1))
                .andExpect(jsonPath("$.findings[0].riskLevel").value("CAUTION"))
                .andExpect(jsonPath("$.findings[0].category").value("FUNCTION_CLAIM"));
    }

    @Test
    void rejectsRequestWithoutPageUrl() throws Exception {
        mockMvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pageTitle": "상품 상세 페이지",
                                  "texts": [],
                                  "images": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.pageUrl").exists());
    }

    @Test
    void rejectsNullTextElement() throws Exception {
        mockMvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pageUrl": "https://example.com/product/null-text",
                                  "texts": [null],
                                  "images": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
    }

    @Test
    void rejectsNullImageElement() throws Exception {
        mockMvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pageUrl": "https://example.com/product/null-image",
                                  "texts": [],
                                  "images": [null]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
    }

    @Test
    void returnsNoFindingsForNormalWording() throws Exception {
        mockMvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pageUrl": "https://example.com/product/normal",
                                  "texts": [
                                    {
                                      "content": "건강한 일상을 위한 영양 성분을 담았습니다.",
                                      "selector": ".description"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary.findingCount").value(0))
                .andExpect(jsonPath("$.findings").isEmpty());
    }

    @Test
    void persistsAnalysisMetadata() throws Exception {
        mockMvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pageUrl": "https://example.com/product/persisted",
                                  "pageTitle": "저장 확인 페이지",
                                  "productName": "저장 확인 상품",
                                  "texts": null,
                                  "images": null
                                }
                                """))
                .andExpect(status().isCreated());

        List<Analysis> analyses = analysisRepository.findAll();
        assertThat(analyses).hasSize(1);
        Analysis analysis = analyses.getFirst();
        assertThat(analysis.getPageUrl()).isEqualTo("https://example.com/product/persisted");
        assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(analysis.getCreatedAt()).isNotNull();
        assertThat(analysis.getCompletedAt()).isNotNull();
    }
}
