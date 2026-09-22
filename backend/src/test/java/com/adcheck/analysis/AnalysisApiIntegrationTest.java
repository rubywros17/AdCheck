package com.adcheck.analysis;

import com.adcheck.analysis.domain.Analysis;
import com.adcheck.analysis.domain.AnalysisStatus;
import com.adcheck.analysis.config.AnalysisProperties;
import com.adcheck.analysis.dto.CreateAnalysisRequest;
import com.adcheck.analysis.dto.PageTextEvidence;
import com.adcheck.analysis.repository.AnalysisRepository;
import com.adcheck.analysis.result.AnalysisResultJsonCodec;
import com.adcheck.analysis.config.AnalysisAsyncConfiguration;
import com.adcheck.analysis.service.AnalysisRequestFingerprint;
import com.adcheck.analysis.service.AnalysisUrlNormalizer;
import com.adcheck.analysis.service.ClaimAnalyzer;
import com.adcheck.analysis.service.FindingAssembler;
import com.adcheck.analysis.service.MockClaimAnalyzer;
import com.adcheck.finding.domain.Finding;
import com.adcheck.finding.domain.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class AnalysisApiIntegrationTest {

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private AnalysisResultJsonCodec resultJsonCodec;

    @Autowired
    private AnalysisUrlNormalizer urlNormalizer;

    @Autowired
    private AnalysisRequestFingerprint requestFingerprint;

    @Autowired
    private AnalysisProperties analysisProperties;

    @Autowired
    @Qualifier(AnalysisAsyncConfiguration.EXECUTOR_NAME)
    private ThreadPoolTaskExecutor analysisTaskExecutor;

    @MockitoBean
    private ClaimAnalyzer claimAnalyzer;

    @MockitoBean
    private FindingAssembler findingAssembler;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        awaitBackgroundJobs();
        analysisRepository.deleteAll();
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        // GEMINI_API_KEY가 셸에 설정되어 있으면 실제 GeminiClaimAnalyzer가 뜨면서 이 파일의
        // finding 개수/내용 검증이 실키 유무에 따라 흔들린다 — MockClaimAnalyzer에 그대로
        // 위임해서 키 유무와 무관하게 항상 동일한 결정론적 결과가 나오도록 고정한다.
        when(claimAnalyzer.analyze(anyList(), anyList())).thenAnswer(invocation ->
                new MockClaimAnalyzer().analyze(invocation.getArgument(0), invocation.getArgument(1)));
        // 이 파일은 재사용 판정/202·200 응답/PENDING→COMPLETED 흐름을 검증하는 게 목적이라,
        // Rule Engine 판정 내용 자체(그건 FindingAssemblerTest가 별도로 검증)와는 무관하게
        // 항상 같은 결과가 나와야 한다. 실제 FindingAssembler는 seed되지 않은 test DB의 빈
        // rules 테이블 때문에 항상 findings=[]를 반환하게 되므로, 예전 MockClaimAnalyzer가
        // riskSignalCandidate 1건당 Finding 1건을 만들던 것과 동일한 결정론적 로직으로 대체한다.
        when(findingAssembler.assemble(any())).thenAnswer(invocation -> {
            com.adcheck.analysis.service.ClaimAnalysisResult claimResult = invocation.getArgument(0);
            List<Finding> findings = claimResult.riskSignalCandidates().stream()
                    .map(signal -> new Finding(
                            signal.text(),
                            signal.source() != null ? signal.source().selector() : null,
                            RiskLevel.CAUTION,
                            "FUNCTION_CLAIM",
                            "공식 인정 기능성보다 강한 표현일 가능성이 있습니다.",
                            null,
                            List.of()
                    ))
                    .toList();
            return new FindingAssembler.Result(null, findings, 0, 0);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        awaitBackgroundJobs();
    }

    @Test
    void submitsPendingAnalysisWithoutAuthenticationAndCompletesInBackground() throws Exception {
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
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.analysisId").isNumber())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.summary").value((Object) null))
                .andExpect(jsonPath("$.findings").isEmpty());

        awaitBackgroundJobs();
        assertThat(analysisRepository.findAll()).singleElement().satisfies(completed -> {
            assertThat(completed.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
            assertThat(completed.getResultJson()).isNotBlank();
            var snapshot = resultJsonCodec.deserialize(completed.getResultJson());
            assertThat(snapshot.summary().findingCount()).isEqualTo(1);
            // Rule Engine/AI#2 비교 파이프라인이 아직 연결되지 않아 officialFunctionMatchedCount는
            // 항상 0이다 (AnalysisBackgroundJob의 임시 riskSignalCandidate -> Finding 매핑 참고).
            assertThat(snapshot.summary().officialFunctionMatchedCount()).isZero();
        });
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
                .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.fieldErrors").isMap())
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
                .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.fieldErrors").isMap())
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
                .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.fieldErrors").isMap())
                .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
    }

    @Test
    void rejectsMalformedJsonWithUnifiedErrorResponse() throws Exception {
        expectError(
                mockMvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageUrl\":")),
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "요청 형식을 확인해 주세요."
        );
    }

    @Test
    void rejectsEmptyRequestBodyWithUnifiedErrorResponse() throws Exception {
        expectError(
                mockMvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON)),
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "요청 형식을 확인해 주세요."
        );
    }

    @Test
    void returnsUnifiedErrorResponseForUnknownEndpoint() throws Exception {
        expectError(
                mockMvc.perform(get("/api/v1/unknown")),
                HttpStatus.NOT_FOUND,
                "NOT_FOUND",
                "요청한 API를 찾을 수 없습니다."
        );
    }

    @Test
    void returnsUnifiedErrorResponseForUnsupportedMethod() throws Exception {
        expectError(
                mockMvc.perform(get("/api/v1/analyses")),
                HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED",
                "지원하지 않는 요청 방식입니다."
        );
    }

    @Test
    void returnsUnifiedErrorResponseForUnsupportedContentType() throws Exception {
        expectError(
                mockMvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{}")),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE",
                "지원하지 않는 Content-Type입니다."
        );
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
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.summary").value((Object) null))
                .andExpect(jsonPath("$.findings").isEmpty());

        awaitBackgroundJobs();
        Analysis completed = analysisRepository.findAll().getFirst();
        assertThat(resultJsonCodec.deserialize(completed.getResultJson()).summary().findingCount())
                .isZero();
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
                .andExpect(status().isAccepted());

        awaitBackgroundJobs();
        List<Analysis> analyses = analysisRepository.findAll();
        assertThat(analyses).hasSize(1);
        Analysis analysis = analyses.getFirst();
        assertThat(analysis.getPageUrl()).isEqualTo("https://example.com/product/persisted");
        assertThat(analysis.getNormalizedUrl()).isEqualTo("https://example.com/product/persisted");
        assertThat(analysis.getContentHash()).hasSize(64);
        assertThat(analysis.getPipelineVersion()).isEqualTo("v1");
        assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(analysis.getResultJson()).isNotBlank();
        assertThat(resultJsonCodec.deserialize(analysis.getResultJson()).summary().findingCount())
                .isZero();
        assertThat(analysis.getCreatedAt()).isNotNull();
        assertThat(analysis.getCompletedAt()).isNotNull();
    }

    @Test
    void reusesFreshCompletedAnalysisWithoutCreatingAnotherRow() throws Exception {
        String requestBody = requestBody(
                "https://example.com/product/reused",
                "시력을 회복하고 노안을 예방합니다."
        );
        mockMvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isAccepted());
        awaitBackgroundJobs();
        Analysis existing = analysisRepository.findAll().getFirst();
        var updatedAt = existing.getUpdatedAt();

        mockMvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisId").value(existing.getId()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.summary.findingCount").value(1))
                .andExpect(jsonPath("$.findings[0].sourceText")
                        .value("시력을 회복하고 노안을 예방합니다."));

        assertThat(analysisRepository.count()).isOne();
        assertThat(analysisRepository.findById(existing.getId()).orElseThrow().getUpdatedAt())
                .isEqualTo(updatedAt);
    }

    @Test
    void getReturnsPendingAnalysisWithMinimalResponse() throws Exception {
        Analysis pending = Analysis.create(
                "https://example.com/product/get-pending",
                "상품 페이지",
                "테스트 상품"
        );
        pending = analysisRepository.saveAndFlush(pending);

        mockMvc.perform(get("/api/v1/analyses/{id}", pending.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisId").value(pending.getId()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.summary").value((Object) null))
                .andExpect(jsonPath("$.findings").isEmpty());
    }

    @Test
    void getReturnsProcessingAnalysisWithMinimalResponse() throws Exception {
        Analysis processing = Analysis.create(
                "https://example.com/product/get-processing",
                "상품 페이지",
                "테스트 상품"
        );
        processing.startProcessing();
        processing = analysisRepository.saveAndFlush(processing);

        mockMvc.perform(get("/api/v1/analyses/{id}", processing.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisId").value(processing.getId()))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.summary").value((Object) null))
                .andExpect(jsonPath("$.findings").isEmpty());
    }

    @Test
    void getReturnsCompletedAnalysisWithSameShapeAsPostReuse() throws Exception {
        String requestBody = requestBody(
                "https://example.com/product/get-completed",
                "시력을 회복하고 노안을 예방합니다."
        );
        mockMvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isAccepted());
        awaitBackgroundJobs();
        Analysis completed = analysisRepository.findAll().getFirst();

        String getResponseJson = mockMvc.perform(get("/api/v1/analyses/{id}", completed.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisId").value(completed.getId()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.summary.findingCount").value(1))
                .andExpect(jsonPath("$.findings[0].sourceText")
                        .value("시력을 회복하고 노안을 예방합니다."))
                .andReturn().getResponse().getContentAsString();

        String postReuseResponseJson = mockMvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(getResponseJson).isEqualTo(postReuseResponseJson);
    }

    @Test
    void getReturns404ForUnknownAnalysisId() throws Exception {
        expectError(
                mockMvc.perform(get("/api/v1/analyses/{id}", 999_999_999L)),
                HttpStatus.NOT_FOUND,
                "ANALYSIS_NOT_FOUND",
                "분석 결과를 찾을 수 없습니다. analysisId=999999999"
        );
    }

    @Test
    void returnsPendingAnalysisAsAcceptedWithoutChangingIt() throws Exception {
        assertInProgressResponse(AnalysisStatus.PENDING);
    }

    @Test
    void returnsProcessingAnalysisAsAcceptedWithoutChangingIt() throws Exception {
        assertInProgressResponse(AnalysisStatus.PROCESSING);
    }

    @Test
    void createsNewAnalysisWhenContentChanges() throws Exception {
        String pageUrl = "https://example.com/product/content-change";
        mockMvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(pageUrl, "기존 광고 문구")))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(pageUrl, "변경된 광고 문구")))
                .andExpect(status().isAccepted());

        awaitBackgroundJobs();
        assertThat(analysisRepository.count()).isEqualTo(2);
    }

    @Test
    void createsNewAnalysisWhenPipelineVersionChanges() throws Exception {
        String originalVersion = analysisProperties.getPipelineVersion();
        String requestBody = requestBody(
                "https://example.com/product/pipeline-change",
                "동일 광고 문구"
        );
        try {
            mockMvc.perform(post("/api/v1/analyses")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isAccepted());

            analysisProperties.setPipelineVersion("v2");
            mockMvc.perform(post("/api/v1/analyses")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isAccepted());

            awaitBackgroundJobs();
            assertThat(analysisRepository.count()).isEqualTo(2);
        } finally {
            analysisProperties.setPipelineVersion(originalVersion);
        }
    }

    @Test
    void reusesAnalysisWhenOnlyTrackingParametersDiffer() throws Exception {
        mockMvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(
                                "https://example.com/product/tracking?item=1&utm_source=first",
                                "동일 광고 문구"
                        )))
                .andExpect(status().isAccepted());

        awaitBackgroundJobs();

        mockMvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(
                                "https://example.com/product/tracking?item=1&gclid=second",
                                "동일 광고 문구"
                        )))
                .andExpect(status().isOk());

        assertThat(analysisRepository.count()).isOne();
    }

    private void awaitBackgroundJobs() throws Exception {
        analysisTaskExecutor.submit(() -> {
        }).get(5, TimeUnit.SECONDS);
    }

    private void assertInProgressResponse(AnalysisStatus status) throws Exception {
        String pageUrl = "https://example.com/product/in-progress-" + status.name().toLowerCase();
        String content = "진행 중 광고 문구";
        CreateAnalysisRequest request = new CreateAnalysisRequest(
                pageUrl,
                "상품 페이지",
                "테스트 상품",
                List.of(new PageTextEvidence(content, "#claim")),
                List.of()
        );
        Analysis active = Analysis.create(
                pageUrl,
                request.pageTitle(),
                request.productName(),
                urlNormalizer.normalize(pageUrl),
                requestFingerprint.generate(request),
                analysisProperties.getPipelineVersion()
        );
        if (status == AnalysisStatus.PROCESSING) {
            active.startProcessing();
        }
        active = analysisRepository.saveAndFlush(active);
        var updatedAt = analysisRepository.findById(active.getId()).orElseThrow().getUpdatedAt();

        mockMvc.perform(post("/api/v1/analyses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(pageUrl, content)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.analysisId").value(active.getId()))
                .andExpect(jsonPath("$.status").value(status.name()))
                .andExpect(jsonPath("$.summary").value((Object) null))
                .andExpect(jsonPath("$.findings").isEmpty());

        assertThat(analysisRepository.count()).isOne();
        assertThat(analysisRepository.findById(active.getId()).orElseThrow().getStatus())
                .isEqualTo(status);
        assertThat(analysisRepository.findById(active.getId()).orElseThrow().getUpdatedAt())
                .isEqualTo(updatedAt);
    }

    private String requestBody(String pageUrl, String content) {
        return """
                {
                  "pageUrl": "%s",
                  "pageTitle": "상품 페이지",
                  "productName": "테스트 상품",
                  "texts": [
                    {
                      "content": "%s",
                      "selector": "#claim"
                    }
                  ],
                  "images": []
                }
                """.formatted(pageUrl, content);
    }

    private void expectError(
            ResultActions result,
            HttpStatus expectedStatus,
            String expectedCode,
            String expectedMessage
    ) throws Exception {
        result.andExpect(status().is(expectedStatus.value()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.message").value(expectedMessage))
                .andExpect(jsonPath("$.fieldErrors").isMap())
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }
}
