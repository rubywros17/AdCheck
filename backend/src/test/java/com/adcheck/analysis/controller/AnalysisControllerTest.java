package com.adcheck.analysis.controller;

import com.adcheck.analysis.domain.AnalysisStatus;
import com.adcheck.analysis.dto.AnalysisResponse;
import com.adcheck.analysis.dto.AnalysisSummary;
import com.adcheck.analysis.dto.CreateAnalysisRequest;
import com.adcheck.analysis.service.AnalysisService;
import com.adcheck.analysis.service.AnalysisSubmissionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisControllerTest {

    private static final String CLIENT_IP = "203.0.113.20";

    private AnalysisService analysisService;
    private AnalysisController controller;
    private CreateAnalysisRequest request;
    private MockHttpServletRequest httpRequest;

    @BeforeEach
    void setUp() {
        analysisService = mock(AnalysisService.class);
        controller = new AnalysisController(analysisService);
        request = new CreateAnalysisRequest(
                "https://example.com/product/1", null, null, List.of(), List.of()
        );
        httpRequest = new MockHttpServletRequest();
        httpRequest.setRemoteAddr(CLIENT_IP);
    }

    @Test
    void returnsAcceptedForSubmittedAnalysis() {
        assertStatus(AnalysisSubmissionResult.Outcome.SUBMITTED, HttpStatus.ACCEPTED);
    }

    @Test
    void returnsOkForReusedAnalysis() {
        assertStatus(AnalysisSubmissionResult.Outcome.REUSED, HttpStatus.OK);
    }

    @Test
    void returnsAcceptedForInProgressAnalysis() {
        assertStatus(AnalysisSubmissionResult.Outcome.IN_PROGRESS, HttpStatus.ACCEPTED);
    }

    @Test
    void returnsOkWithAnalysisServiceResponseForGetById() {
        AnalysisResponse response = new AnalysisResponse(
                5L,
                AnalysisStatus.COMPLETED,
                new AnalysisSummary(1, 1, 0),
                List.of()
        );
        when(analysisService.getAnalysis(5L)).thenReturn(response);

        ResponseEntity<AnalysisResponse> entity = controller.getAnalysis(5L);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.getBody()).isSameAs(response);
    }

    private void assertStatus(AnalysisSubmissionResult.Outcome outcome, HttpStatus expectedStatus) {
        AnalysisResponse response = new AnalysisResponse(
                1L,
                outcome == AnalysisSubmissionResult.Outcome.IN_PROGRESS
                        ? AnalysisStatus.PENDING
                        : AnalysisStatus.COMPLETED,
                outcome == AnalysisSubmissionResult.Outcome.IN_PROGRESS
                        ? null
                        : new AnalysisSummary(0, 0, 0),
                List.of()
        );
        when(analysisService.analyze(request, CLIENT_IP))
                .thenReturn(new AnalysisSubmissionResult(outcome, response));

        ResponseEntity<AnalysisResponse> entity = controller.analyze(request, httpRequest);

        assertThat(entity.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(entity.getBody()).isSameAs(response);
        verify(analysisService).analyze(request, CLIENT_IP);
    }
}
