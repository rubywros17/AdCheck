package com.adcheck.analysis.service;

import com.adcheck.rule.service.RuleAnalysisRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 2단계(Claim 추출) + 3단계 원료표시 추출을 Gemini 호출 1회로 합친 서비스.
 *
 * <p>원래는 ClaimExtractionService(1회 호출)와 IngredientLabelExtractionService(1회 호출)로
 * 나뉘어 있었고, 파이프라인에서 OCR도 각자 따로 호출해 상품 1개당 총 4회(OCR 2회 + 추출 2회)의
 * Gemini 호출이 나갔다. 실측 결과 이미지 1장짜리 상품 하나를 동기 파이프라인으로 처리하는 데
 * 64.6초가 걸렸는데, OCR은 AnalysisPipelineService에서 한 번만 돌리고 이 서비스가 Claim 추출과
 * 원료표시 추출을 프롬프트 하나로 합쳐서 상품당 총 2회(OCR 1 + 추출 1)로 줄인다.
 *
 * <p>원료표시 추출 부분은 처음엔 LLM한테 "문자열을 새로 만들어 반환해"라고 시켰는데 두 가지
 * 문제가 있었다: (1) 후보가 2개 이상이면 고르지 못하고 null을 반환하는 비결정적 누락,
 * (2) "원문 그대로 옮기라"는 지시를 따르다 OCR 원문의 개행을 그대로 보존해서 콤마 분리가
 * 깨짐. 그래서 LLM한테 문자열을 "생성"시키지 않고, 본문+OCR 텍스트를 줄 단위로 번호 매겨 주고
 * "원료표에 해당하는 줄 번호만 골라줘"라고 시킨다(buildIndexedLines/reconstructCandidates) —
 * 실제 문자열 조립은 코드가 하므로 원문 왜곡·개행 보존 문제가 원천적으로 생기지 않는다. 그래도
 * LLM이 원료표가 아닌 걸(광고 문구 속 수치 등) 잘못 고를 수 있으므로, 재구성한 후보 문자열을
 * 곧바로 IngredientMatchingService에 넣어보고 하나도 매칭되는 원료가 없으면 후보 자체를 버린다
 * (hasAtLeastOneRecognizedIngredient) — 이미 검증된 매칭엔진을 품질 필터로 재활용.
 *
 * <p>Claim 추출은 "무슨 주장을 했는지"만 뽑는다 — 그 주장이 어느 규칙(rule)에 해당하고
 * 위반인지는 판정하지 않는다. 원래는 71개 규칙 전체를 프롬프트에 하드코딩해서 Claim마다
 * ruleCode까지 이 서비스가 직접 매칭했었는데, 규칙의 Source of Truth를 Backend DB의
 * {@code rules} 테이블 하나로 유지하기 위해 팀원 요청으로 제거했다 — 규칙 매칭/위반 판정은
 * Backend Rule Engine이 제품/원료 확정 후 DB에서 적용 가능한 규칙을 조회해서 판단한다.
 *
 * <p>Risk Signal 후보도 같은 원칙이다 — {@link #RISK_SIGNAL_CATEGORY_GUIDE}에 담긴
 * {@code docs/rules_v0.1.json}의 COMMON scope judgmentCategory 30개를 참고 자료로만 주고,
 * 최종 ruleCode/severity/위반 여부는 판정하지 않는다. LLM이 위험 표현 문구를 다시 옮겨 적으면
 * 원문 왜곡 위험이 있어서, claims 배열의 인덱스(claimIndex)만 받고 text/source는 해당 Claim
 * 것을 코드에서 그대로 재사용한다.
 */
@Service
public class ProductContentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ProductContentExtractionService.class);

    /**
     * {@code docs/rules_v0.1.json}의 COMMON scope judgmentCategory 30개를 그대로 옮긴
     * 프롬프트용 참고 목록 — Rule 데이터가 바뀌면 이 목록도 함께 갱신해야 한다. DB 조회 없이
     * 이 서비스가 상태 없이 동작하도록 하드코딩했다(이 시점엔 어떤 원료/제품인지도 확정되지
     * 않아 INGREDIENT_SPECIFIC Rule은 애초에 적용 대상이 아니다).
     */
    private static final String RISK_SIGNAL_CATEGORY_GUIDE = """
            - DISEASE_PREVENTION: 질병명 + 예방
            - DISEASE_TREATMENT: 치료·완치·낫는다·증상 완화
            - MEDICINE_CONFUSION: 약 대체·처방·병원용 등
            - DISEASE_INFO_LINK: 질병 통계·기사·체크리스트 뒤 제품 소개
            - FUNCTION_EXCEED: 공식 기능성보다 강하거나 다른 효능
            - OFFICIAL_FUNCTION: 인정 기능성 표현
            - ABSOLUTE_EFFECT: 100% 효과·무조건·완벽한 회복
            - RESULT_TIME_AMOUNT: n kg 감량·n일이면 개선
            - COMPLETE_SOLUTION: 고민 끝·관리 끝·한번에 끝
            - INGREDIENT_100: 원료 100%
            - SUB_INGREDIENT_FUNCTION: 부원료가 항암·피로개선
            - SUB_INGREDIENT_EMPHASIS: 일부 부원료만 큰 함량·이미지
            - TESTIMONIAL: 먹고 나았다·감량했다는 후기
            - EXPERT_ENDORSEMENT: 의사 추천·약사 보증
            - EXPERT_DEVELOPMENT: 의사가 개발·배합
            - HUMAN_STUDY: 인체적용시험 수치·그래프
            - NONHUMAN_STUDY: 동물·시험관 실험 효과
            - RESEARCH_DISTORTION: 축 축소·유리한 집단만 발췌
            - PATENT_MISUSE: 특허로 치료효과 인정·출원 중 효능
            - CERTIFICATION: FDA·GMP·수상·안전 인증
            - UNFAIR_COMPARISON: 함량 n배이므로 효과 n배
            - SUPERLATIVE: 유일·최고·최대·최초·고순도
            - MIXED_PRODUCT: 일반식품과 건기식 공동광고
            - OVERCONSUMPTION: 많이 먹을수록·식사 대신
            - REQUIRED_IDENTITY: 제품명·업소명·건기식 정보 누락
            - REVIEW_STATUS: 심의 여부·승인 시안 차이
            - FUNCTION_SYNERGY: 혈당 관리로 살이 안 찜
            - TARGET_SPECIALIZATION: 수험생 전용 집중·학업 효과
            - ABSORPTION: 흡수율·생체이용률 우수
            - NATURAL_FREE: 천연·자연·무첨가·무검출
            """;

    private final GeminiClient geminiClient;
    private final IngredientMatchingService matchingService;
    private final ObjectMapper objectMapper;

    public ProductContentExtractionService(GeminiClient geminiClient, IngredientMatchingService matchingService) {
        this.geminiClient = geminiClient;
        this.matchingService = matchingService;
        this.objectMapper = new ObjectMapper();
    }

    public record ExtractionResult(
            List<ExtractedClaim> claims,
            List<ProductCandidate> productCandidates,
            List<IngredientCandidate> ingredientCandidates,
            List<RiskSignalCandidate> riskSignalCandidates
    ) {
    }

    /**
     * @param cleanedText DetailTextCleaner로 정제된 본문 텍스트
     * @param ocrResults  이미지 URL -> OCR 텍스트 (빈 텍스트는 호출 전에 걸러서 넘길 것)
     */
    public ExtractionResult extract(String cleanedText, Map<String, String> ocrResults) {
        List<String> allLines = new ArrayList<>();
        List<Source> lineSources = new ArrayList<>();
        buildIndexedLines(cleanedText, ocrResults, allLines, lineSources);

        String prompt = buildPrompt(cleanedText, ocrResults, allLines);
        String rawResponse = geminiClient.generate(prompt, true);
        CombinedResponse response = parseResponse(rawResponse);

        List<RawClaim> rawClaims = response.claims() != null ? response.claims() : List.of();
        List<ExtractedClaim> claims = toExtractedClaims(rawClaims, ocrResults);

        List<RawProductCandidate> rawProductCandidates =
                response.productCandidates() != null ? response.productCandidates() : List.of();
        List<ProductCandidate> productCandidates = toProductCandidates(rawProductCandidates, ocrResults);

        List<List<Integer>> lineGroups = response.labelLineGroups() != null ? response.labelLineGroups() : List.of();
        List<Double> groupConfidences =
                response.labelGroupConfidences() != null ? response.labelGroupConfidences() : List.of();
        List<IngredientCandidate> candidates =
                reconstructCandidates(allLines, lineSources, lineGroups, groupConfidences);
        List<IngredientCandidate> filteredCandidates =
                candidates.stream().filter(candidate -> hasAtLeastOneRecognizedIngredient(candidate.rawText())).toList();

        List<RawRiskSignal> rawRiskSignals = response.riskSignals() != null ? response.riskSignals() : List.of();
        List<RiskSignalCandidate> riskSignalCandidates = toRiskSignalCandidates(rawRiskSignals, claims);

        return new ExtractionResult(claims, productCandidates, filteredCandidates, riskSignalCandidates);
    }

    /**
     * source가 OCR 결과의 이미지 URL과 일치하면 OCR_IMAGE, 아니면 DOM_TEXT로 분류한다.
     * DOM_TEXT의 selector는 프롬프트가 "본문"이라는 마커만 반환하고 어느 텍스트 블록인지는
     * 구분해주지 않아 현재는 항상 null이다.
     */
    private static Source toSource(String rawSource, Map<String, String> ocrResults) {
        return ocrResults.containsKey(rawSource) ? Source.ocrImage(rawSource) : Source.domText(null);
    }

    /** claimId는 이 분석 1건 안에서만 유일하면 되는 로컬 식별자라 순서대로 채번한다. */
    private static List<ExtractedClaim> toExtractedClaims(List<RawClaim> rawClaims, Map<String, String> ocrResults) {
        List<ExtractedClaim> claims = new ArrayList<>();
        int index = 1;
        for (RawClaim raw : rawClaims) {
            claims.add(new ExtractedClaim(
                    "claim-" + index, raw.claimText(), toSource(raw.source(), ocrResults),
                    parseContext(raw.context()), raw.contextEvidence()));
            index++;
        }
        return claims;
    }

    /** 모르는 값·null이면 안전하게 UNKNOWN으로 — RiskLevel.fromSeverity()와 같은 폴백 정책. */
    private static RuleAnalysisRequest.Context parseContext(String rawContext) {
        if (rawContext == null || rawContext.isBlank()) {
            return RuleAnalysisRequest.Context.UNKNOWN;
        }
        try {
            return RuleAnalysisRequest.Context.valueOf(rawContext.strip());
        } catch (IllegalArgumentException e) {
            return RuleAnalysisRequest.Context.UNKNOWN;
        }
    }

    private static List<ProductCandidate> toProductCandidates(
            List<RawProductCandidate> rawCandidates, Map<String, String> ocrResults) {
        return rawCandidates.stream()
                .map(raw -> new ProductCandidate(
                        raw.productReportNo(),
                        raw.productName(),
                        raw.companyName(),
                        raw.confidence(),
                        toSource(raw.source(), ocrResults)))
                .toList();
    }

    /**
     * claimIndex는 같은 응답의 claims 배열 순서를 가리킨다(0-based) — LLM이 위험 표현의
     * 본문을 다시 옮겨 적게 하면 원문 왜곡 위험이 있어서, text/source는 새로 만들지 않고
     * 해당 Claim 것을 그대로 재사용한다. claimIndex가 범위를 벗어나면 그 신호는 버린다.
     */
    private static List<RiskSignalCandidate> toRiskSignalCandidates(
            List<RawRiskSignal> rawSignals, List<ExtractedClaim> claims) {
        List<RiskSignalCandidate> signals = new ArrayList<>();
        for (RawRiskSignal raw : rawSignals) {
            if (raw.claimIndex() == null || raw.claimIndex() < 0 || raw.claimIndex() >= claims.size()) {
                continue;
            }
            ExtractedClaim claim = claims.get(raw.claimIndex());
            signals.add(new RiskSignalCandidate(
                    claim.claimId(), claim.claimText(), raw.signalType(), raw.confidence(), claim.source()));
        }
        return signals;
    }

    private static void buildIndexedLines(
            String cleanedText, Map<String, String> ocrResults, List<String> lines, List<Source> lineSources) {
        if (!cleanedText.isBlank()) {
            for (String line : cleanedText.split("\n")) {
                lines.add(line);
                lineSources.add(Source.domText(null));
            }
        }
        for (Map.Entry<String, String> entry : ocrResults.entrySet()) {
            if (!entry.getValue().isBlank()) {
                for (String line : entry.getValue().split("\n")) {
                    lines.add(line);
                    lineSources.add(Source.ocrImage(entry.getKey()));
                }
            }
        }
    }

    /**
     * 한 그룹(원료표 한 장) 안의 줄이 본문/여러 이미지에 걸쳐 있을 수 있으므로, 대표 source는
     * 그룹의 첫 번째 줄 기준으로 정한다 — 완벽하진 않지만 원료표 한 장은 대개 같은 출처에서
     * 나오므로 근사치로 충분하다. confidence는 labelGroupConfidences가 lineGroups와 같은
     * 순서·길이로 온다고 가정하고 인덱스로 대응시키며, 길이가 안 맞으면 null로 둔다.
     */
    private static List<IngredientCandidate> reconstructCandidates(
            List<String> allLines, List<Source> lineSources, List<List<Integer>> lineGroups,
            List<Double> groupConfidences) {
        List<IngredientCandidate> candidates = new ArrayList<>();
        for (int groupIndex = 0; groupIndex < lineGroups.size(); groupIndex++) {
            TreeSet<Integer> sortedValidIndices = new TreeSet<>();
            for (Integer index : lineGroups.get(groupIndex)) {
                if (index != null && index >= 0 && index < allLines.size()) {
                    sortedValidIndices.add(index);
                }
            }
            if (sortedValidIndices.isEmpty()) {
                continue;
            }
            String joined = sortedValidIndices.stream().map(allLines::get).reduce((a, b) -> a + " " + b).orElse("");
            if (!joined.isBlank()) {
                Source representativeSource = lineSources.get(sortedValidIndices.first());
                Double confidence = groupIndex < groupConfidences.size() ? groupConfidences.get(groupIndex) : null;
                candidates.add(new IngredientCandidate(joined, confidence, representativeSource));
            }
        }
        return candidates;
    }

    private boolean hasAtLeastOneRecognizedIngredient(String candidate) {
        IngredientFieldMatchResult result = matchingService.matchField(candidate);
        boolean recognized = result.items().stream()
                .anyMatch(item -> !IngredientMatchingService.UNMATCHED.equals(item.matchStatus()));
        if (!recognized) {
            log.info("원료표 후보에서 인식되는 원료가 하나도 없어 제외함: {}", candidate);
        }
        return recognized;
    }

    private String buildPrompt(String cleanedText, Map<String, String> ocrResults, List<String> allLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 건강기능식품 온라인 광고 페이지를 검수하는 도구입니다. ");
        sb.append("아래 자료에서 서로 다른 네 항목을 동시에 찾아야 합니다.\n\n");

        sb.append("[항목 1] 효능·기능성 주장 표현 (claims)\n");
        sb.append("- \"간 건강에 도움을 줍니다\", \"체지방 감소 효과\" 같은 효능/기능성을 내세우는 문장을 전부 찾으세요.\n");
        sb.append("- 이 문장이 광고 규정을 위반하는지, 어떤 규칙에 해당하는지는 판정하지 마세요 — 그건 당신의 역할이 아닙니다. 효능/기능성 주장으로 보이면 일단 포함하세요(과탐 허용).\n");
        sb.append("- 제외 대상: 배송안내, 보관방법, 브랜드/회사 소개, 단순 성분·원재료 나열, 이벤트 안내\n");
        sb.append("- 해당하는 문장이 하나도 없으면 claims는 빈 배열로 반환하세요.\n");
        sb.append("- 각 claim마다 이 문장을 판매자의 제품 효과 주장으로 볼 수 있는지, 근거 위치와 함께 판단해서 context/contextEvidence로 표시하세요. ");
        sb.append("candidateExamples 같은 단어 하나만 보고 정하지 말고, 바로 앞뒤 문장과 전체 문맥까지 실제로 확인한 경우에만 UNKNOWN이 아닌 값을 쓰세요.\n");
        sb.append("  · PRODUCT_HEALTH_EFFECT_COPY: 주변 문맥에서 이 문장이 이 제품의 건강 효과를 주장하는 것으로 확인됨(배송·편의성 등 비건강 효과가 아님)\n");
        sb.append("  · PRODUCT_COPY: 제품에 대한 독립 문장인 것은 확인되지만, 그 효과가 건강 효과인지까지는 확신할 수 없음\n");
        sb.append("  · NON_PRODUCT_INFORMATION: 구매자 리뷰, 전문가 발언 인용, 제품과 무관한 배경지식, 효과를 부정하는 문장 등 제품 효과에 귀속되지 않는 독립 정보\n");
        sb.append("  · UNKNOWN: 위 어느 것도 확신할 수 없음(모르면 이 값을 쓰세요, 추측 금지)\n");
        sb.append("- contextEvidence에는 그렇게 판단한 근거를 한 문장으로 남기세요(예: \"바로 앞 문장이 '수면의 질 개선에 도움'이라는 제품 효과를 설명 중\"). ");
        sb.append("UNKNOWN이면 null로 두세요.\n\n");

        sb.append("[항목 2] 제품 후보 (productCandidates)\n");
        sb.append("- 찾을 대상: 품목보고번호/신고번호(보통 숫자로만 이루어지거나 숫자+하이픈 조합), 제품명, 제조원/판매원 같은 업체명.\n");
        sb.append("- 페이지 안에 이런 정보가 여러 곳(제목, 상세 이미지 등)에 보이면 후보를 여러 개 반환해도 됩니다.\n");
        sb.append("- 신고번호를 찾지 못했으면 productReportNo는 null로 두세요 — 절대로 번호를 추측하거나 지어내지 마세요.\n");
        sb.append("- confidence는 이 후보가 실제 이 제품의 정보라는 확신도를 0~1 사이 숫자로 반환하세요.\n");
        sb.append("- 아무 후보도 없으면 productCandidates는 빈 배열로 반환하세요.\n\n");

        sb.append("[항목 3] 원재료명 및 함량 표시 (labelLineGroups)\n");
        sb.append("- 찾을 대상: 성분명 뒤에 %, mg, g, μg, IU 같은 함량 단위가 붙어서 나열된 부분(원재료명/원료명 표).\n");
        sb.append("- 주의: 광고 문구 안에 있는 수치(예: \"지표성분 몇 mg\", \"진세노사이드 합 16mg\" 같은 효능 주장·비교용 수치)는 ");
        sb.append("원재료명 표시가 아니므로 포함하지 마세요. 실제로 여러 성분이 나열된 표/목록 형태만 고르세요.\n");
        sb.append("- 아래 [번호가 매겨진 줄 목록]에서 원료표를 이루는 줄 번호들만 순서대로 골라 labelLineGroups의 배열 원소 하나로 담으세요. ");
        sb.append("표가 여러 개(앞면 요약, 뒷면 상세 등)면 그룹도 여러 개로 나누세요.\n");
        sb.append("- 줄 번호만 고르면 됩니다. 텍스트를 직접 옮겨 적지 마세요.\n");
        sb.append("- labelGroupConfidences에는 labelLineGroups와 같은 순서·개수로, 그 그룹이 실제 원료표라는 확신도(0~1)를 반환하세요.\n");
        sb.append("- 원료표가 하나도 없으면 labelLineGroups와 labelGroupConfidences는 빈 배열로 반환하세요.\n\n");

        sb.append("[항목 4] 주의 필요 표현 (riskSignals)\n");
        sb.append("- [항목 1]에서 찾은 claims 중에서, 아래 카테고리에 해당하는 표현이 있으면 표시하세요.\n");
        sb.append("- 최종 위반 여부는 당신이 판정하지 않습니다 — 그냥 어떤 카테고리로 의심되는지만 표시하세요(과탐 허용).\n");
        sb.append(RISK_SIGNAL_CATEGORY_GUIDE);
        sb.append("- claimIndex는 claims 배열에서 그 표현이 나온 Claim의 위치(0부터 시작)입니다. 새로 텍스트를 만들지 말고 인덱스만 알려주세요.\n");
        sb.append("- confidence는 그 카테고리에 해당한다는 확신도를 0~1 사이 숫자로 반환하세요.\n");
        sb.append("- 해당하는 게 없으면 riskSignals는 빈 배열로 반환하세요.\n\n");

        sb.append("반드시 아래 JSON 형식으로만 응답하세요. 다른 설명은 붙이지 마세요.\n");
        sb.append("{\"claims\": [{\"claimText\": \"주장/표현 문장 원문 그대로\", \"source\": \"본문\" 또는 해당 이미지 URL, ");
        sb.append("\"context\": \"PRODUCT_HEALTH_EFFECT_COPY\" 또는 \"PRODUCT_COPY\" 또는 \"NON_PRODUCT_INFORMATION\" 또는 \"UNKNOWN\", ");
        sb.append("\"contextEvidence\": \"판단 근거 한 문장\" 또는 null}], ");
        sb.append("\"productCandidates\": [{\"productReportNo\": \"...\" 또는 null, \"productName\": \"...\" 또는 null, ");
        sb.append("\"companyName\": \"...\" 또는 null, \"confidence\": 0.9, \"source\": \"본문\" 또는 해당 이미지 URL}], ");
        sb.append("\"labelReview\": \"원료표 검토 메모\", \"labelLineGroups\": [[3,4,5], [12,13]], \"labelGroupConfidences\": [0.95, 0.8], ");
        sb.append("\"riskSignals\": [{\"claimIndex\": 0, \"signalType\": \"ABSOLUTE_EFFECT\", \"confidence\": 0.9}]}\n\n");

        sb.append("[본문 텍스트]\n");
        sb.append(cleanedText.isBlank() ? "(본문 텍스트 없음)" : cleanedText).append("\n\n");
        sb.append("[이미지 OCR 결과] (source로 이미지 URL을 쓸 때 참고)\n");
        if (ocrResults.isEmpty()) {
            sb.append("(텍스트가 검출된 이미지 없음)\n");
        } else {
            ocrResults.forEach((url, text) -> sb.append("- ").append(url).append(": ").append(text).append("\n"));
        }
        sb.append("\n[번호가 매겨진 줄 목록] (labelLineGroups 선택 전용 — 본문+OCR 텍스트를 줄 단위로 이어붙여 번호를 매긴 것)\n");
        for (int i = 0; i < allLines.size(); i++) {
            sb.append(i).append(": ").append(allLines.get(i)).append("\n");
        }
        return sb.toString();
    }

    private CombinedResponse parseResponse(String rawJson) {
        try {
            return objectMapper.readValue(rawJson, CombinedResponse.class);
        } catch (JacksonException e) {
            log.warn("통합 추출 응답 JSON 파싱 실패: {}", e.getMessage());
            return new CombinedResponse(List.of(), List.of(), null, List.of(), List.of(), List.of());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CombinedResponse(
            List<RawClaim> claims,
            List<RawProductCandidate> productCandidates,
            String labelReview,
            List<List<Integer>> labelLineGroups,
            List<Double> labelGroupConfidences,
            List<RawRiskSignal> riskSignals
    ) {
    }

    /**
     * Gemini 응답 JSON 그대로의 claim 모양 — source는 "본문" 마커 또는 이미지 URL 문자열.
     * context는 {@link RuleAnalysisRequest.Context} 이름 문자열(모르면 null/빈 문자열 허용,
     * {@link #parseContext(String)}가 안전하게 UNKNOWN으로 폴백).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawClaim(String claimText, String source, String context, String contextEvidence) {
    }

    /** Gemini 응답 JSON 그대로의 제품 후보 모양 — source는 "본문" 마커 또는 이미지 URL 문자열. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawProductCandidate(
            String productReportNo, String productName, String companyName, Double confidence, String source) {
    }

    /** Gemini 응답 JSON 그대로의 주의 필요 표현 후보 모양 — claimIndex는 같은 응답 claims 배열의 0-based 위치. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawRiskSignal(Integer claimIndex, String signalType, Double confidence) {
    }
}
