package com.adcheck.analysis;

import com.adcheck.analysis.config.AnalysisAsyncConfiguration;
import com.adcheck.analysis.domain.AnalysisStatus;
import com.adcheck.analysis.repository.AnalysisRepository;
import com.adcheck.analysis.service.ClaimAnalysisResult;
import com.adcheck.analysis.service.ClaimAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class AnalysisAsyncApiIntegrationTest {

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    @Qualifier(AnalysisAsyncConfiguration.EXECUTOR_NAME)
    private ThreadPoolTaskExecutor analysisTaskExecutor;

    @MockitoBean
    private ClaimAnalyzer claimAnalyzer;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        awaitBackgroundJobs();
        analysisRepository.deleteAll();
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
    }

    @Test
    void returnsAcceptedPendingBeforeSlowAnalyzerCompletes() throws Exception {
        CountDownLatch analyzerStarted = new CountDownLatch(1);
        CountDownLatch releaseAnalyzer = new CountDownLatch(1);
        when(claimAnalyzer.analyze(anyList(), anyList())).thenAnswer(invocation -> {
            analyzerStarted.countDown();
            if (!releaseAnalyzer.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트 analyzer 대기 시간이 초과되었습니다.");
            }
            return new ClaimAnalysisResult(List.of(), List.of(), List.of(), List.of());
        });

        ExecutorService requestExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<?> response = requestExecutor.submit(() -> {
                mockMvc.perform(post("/api/v1/analyses")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "pageUrl": "https://example.com/product/slow-analysis",
                                          "texts": [{"content": "광고 문구", "selector": "#claim"}],
                                          "images": []
                                        }
                                        """))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.status").value("PENDING"))
                        .andExpect(jsonPath("$.summary").value((Object) null))
                        .andExpect(jsonPath("$.findings").isEmpty());
                return null;
            });

            assertThat(analyzerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            response.get(2, TimeUnit.SECONDS);
            assertThat(releaseAnalyzer.getCount()).isOne();
        } finally {
            releaseAnalyzer.countDown();
            requestExecutor.shutdownNow();
        }

        awaitBackgroundJobs();
        assertThat(analysisRepository.findAll()).singleElement().satisfies(completed -> {
            assertThat(completed.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
            assertThat(completed.getResultJson()).isNotBlank();
        });
    }

    private void awaitBackgroundJobs() throws Exception {
        analysisTaskExecutor.submit(() -> {
        }).get(5, TimeUnit.SECONDS);
    }
}
