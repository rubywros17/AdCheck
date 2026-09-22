package com.adcheck.analysis.service;

import com.adcheck.analysis.config.AnalysisAsyncConfiguration;
import com.adcheck.analysis.domain.AnalysisStatus;
import com.adcheck.analysis.dto.AnalysisResponse;
import com.adcheck.analysis.dto.AnalysisSummary;
import com.adcheck.analysis.dto.FindingResponse;
import com.adcheck.analysis.result.AnalysisResultJsonCodec;
import com.adcheck.analysis.result.AnalysisResultSnapshot;
import com.adcheck.analysis.result.AnalysisResultSnapshotMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class AnalysisBackgroundJob {

    private static final Logger log = LoggerFactory.getLogger(AnalysisBackgroundJob.class);
    private static final String DEFAULT_FAILURE_MESSAGE = "분석 처리 중 오류가 발생했습니다.";

    private final AnalysisLifecycleService lifecycleService;
    private final ClaimAnalyzer claimAnalyzer;
    private final FindingAssembler findingAssembler;
    private final AnalysisResultSnapshotMapper snapshotMapper;
    private final AnalysisResultJsonCodec resultJsonCodec;

    public AnalysisBackgroundJob(
            AnalysisLifecycleService lifecycleService,
            ClaimAnalyzer claimAnalyzer,
            FindingAssembler findingAssembler,
            AnalysisResultSnapshotMapper snapshotMapper,
            AnalysisResultJsonCodec resultJsonCodec
    ) {
        this.lifecycleService = lifecycleService;
        this.claimAnalyzer = claimAnalyzer;
        this.findingAssembler = findingAssembler;
        this.snapshotMapper = snapshotMapper;
        this.resultJsonCodec = resultJsonCodec;
    }

    @Async(AnalysisAsyncConfiguration.EXECUTOR_NAME)
    public void process(Long analysisId, AnalysisJobInput input) {
        try {
            lifecycleService.markProcessing(analysisId);
            long startedAt = System.currentTimeMillis();
            int callsBefore = GeminiClient.totalCalls();
            int rateLimitedBefore = GeminiClient.totalRateLimited();

            ClaimAnalysisResult claimResult = claimAnalyzer.analyze(input.texts(), input.images());
            FindingAssembler.Result assembled = findingAssembler.assemble(claimResult);
            logBudget(analysisId, input, claimResult, assembled, startedAt, callsBefore, rateLimitedBefore);

            lifecycleService.assignProduct(analysisId, assembled.product());

            AnalysisResponse response = responseFrom(analysisId, assembled);
            AnalysisResultSnapshot snapshot = snapshotMapper.toSnapshot(response);
            String resultJson = resultJsonCodec.serialize(snapshot);
            boolean hasFinding = !response.findings().isEmpty();
            lifecycleService.completeWithResult(analysisId, resultJson, hasFinding);
        } catch (RuntimeException exception) {
            markFailed(analysisId, exception);
            log.error("Background analysis failed. analysisId={}", analysisId, exception);
        }
    }

    /**
     * 분석 1건이 끝날 때 예산과 신호를 한 줄로 남긴다. 두 가지를 보려는 목적이다.
     *
     * <p><b>호출 수·429</b> — {@code GeminiClient}가 호출마다 찍는 번호는 앱 기동 후 누적이라
     * "이 분석이 몇 회를 썼는지"는 로그를 세어야 알 수 있었다. 무료 티어는 분당 15회라 이 값이
     * 한도에 얼마나 붙어 있는지가 곧 규칙을 더 켤 수 있는지의 판단 근거가 된다.
     *
     * <p><b>입력 구성</b> — 확장이 실제로 무엇을 보내는지 서버에 기록이 없어서, Claim이 적게
     * 나올 때 "페이지에 효능 문구가 원래 없다"와 "확장이 본문을 못 걷어온다"를 구분할 수 없었다.
     * 텍스트 개수·총 글자 수와 이미지 장수를 남기면 다음 실행부터 바로 갈린다(OCR로 뽑힌 글자
     * 수는 {@code GeminiOcrService}가 별도 줄로 남긴다).
     *
     * <p><b>원료 확정</b> — 원료가 확정되지 않으면 원료별 규칙이 통째로 건너뛰어지고
     * {@code officialFunction}이 null이 되어 "식약처 고시 기준" 칸이 비는데, 그 원인이
     * "AI#1이 원료를 못 뽑았다"인지 "뽑았지만 DB와 매칭이 안 됐다"인지 구분할 방법이 없었다.
     * 후보 수와 확정 수를 같이 남기면 바로 갈린다.
     *
     * <p><b>AI#1 위험 신호</b> — 지금은 이 신호가 규칙 선택에 쓰이지 않고 프롬프트 힌트로만
     * 들어간다. 신호로 평가할 규칙을 좁히는 "깔때기"를 도입할지 판단하려면 페이지당 신호가 몇
     * 건·어떤 종류로 뜨는지를 알아야 하는데, 지금까지 어디에도 기록되지 않아 효과를 추정조차 할
     * 수 없었다. 여기 남겨두면 돌리는 분석마다 근거가 쌓인다.
     */
    private void logBudget(Long analysisId, AnalysisJobInput input, ClaimAnalysisResult claimResult,
                           FindingAssembler.Result assembled, long startedAt,
                           int callsBefore, int rateLimitedBefore) {
        int textChars = input.texts().stream().mapToInt(text -> text.content() == null ? 0 : text.content().length()).sum();
        log.info("[TIMING] 분석 {} 완료 — {}ms, Gemini {}회(429 {}건), 입력 텍스트 {}개·{}자/이미지 {}장, "
                        + "Claim {}건, Finding {}건, 원료 후보 {}건→확정 {}건, 공식 기능성 {}건, AI#1 신호 {}건({})",
                analysisId, System.currentTimeMillis() - startedAt,
                GeminiClient.totalCalls() - callsBefore,
                GeminiClient.totalRateLimited() - rateLimitedBefore,
                input.texts().size(), textChars,
                input.images().size(), claimResult.claims().size(), assembled.findings().size(),
                claimResult.ingredientCandidates().size(), assembled.confirmedIngredientCount(),
                assembled.officialFunctionMatchedCount(),
                claimResult.riskSignalCandidates().size(), signalSummary(claimResult.riskSignalCandidates()));
    }

    /**
     * 신호 종류별 개수를 {@code "SUPERLATIVE 2, TESTIMONIAL 1"} 형태로 만든다. 종류 이름순으로
     * 정렬해 실행마다 같은 순서로 찍히게 하고(로그를 눈으로 비교하기 위함), 종류가 비어 있는
     * 신호는 세지 않는다 — AI#1이 목록에 없는 값을 내는 경우가 실제로 있어서다.
     */
    static String signalSummary(List<RiskSignalCandidate> signals) {
        Map<String, Long> histogram = signals.stream()
                .filter(signal -> signal.signalType() != null && !signal.signalType().isBlank())
                .collect(Collectors.groupingBy(RiskSignalCandidate::signalType,
                        TreeMap::new, Collectors.counting()));
        return histogram.isEmpty() ? "없음"
                : histogram.entrySet().stream()
                        .map(entry -> entry.getKey() + " " + entry.getValue())
                        .collect(Collectors.joining(", "));
    }

    private AnalysisResponse responseFrom(Long analysisId, FindingAssembler.Result assembled) {
        List<FindingResponse> findings = assembled.findings().stream()
                .map(FindingResponse::from)
                .toList();
        AnalysisSummary summary = new AnalysisSummary(findings.size(), assembled.officialFunctionMatchedCount());

        return new AnalysisResponse(
                analysisId,
                AnalysisStatus.COMPLETED,
                summary,
                findings
        );
    }

    private void markFailed(Long analysisId, RuntimeException originalException) {
        try {
            lifecycleService.fail(analysisId, failureMessage(originalException));
        } catch (RuntimeException failurePersistenceException) {
            originalException.addSuppressed(failurePersistenceException);
        }
    }

    private String failureMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }

        String simpleName = exception.getClass().getSimpleName();
        return simpleName == null || simpleName.isBlank()
                ? DEFAULT_FAILURE_MESSAGE
                : simpleName;
    }
}
