package com.adcheck.rule.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 실제로 자동 판정을 켤 규칙 코드 allowlist. {@code AiRuleEvaluator}/{@code LiteralRuleEvaluator}는
 * 각자 "판정 가능한" 전체 규칙 집합(39개/9개)을 {@code ruleCodes()}로 선언하지만, 그중
 * 실제로 {@code RuleEvaluatorRegistry}에 등록해 파이프라인에 연결할지는 이 allowlist가
 * 최종 결정한다 — 검증되지 않은 규칙까지 한꺼번에 켜지 않고 단계적으로 넓혀가기 위함.
 *
 * <p>기본값은 2026-09-17 파일럿(검증 데이터셋 반복 실행으로 검증된 것만) — Common 3개 +
 * 정규식 9개 + AI 25개(1차 9개: 3회 반복 9/9 안정 정답, 2차 7개: "왔다갔다"하던 것을 2회 더
 * 재실행해 5회 중 4~5회 정답으로 확인된 것, 3차 3개: 패턴1 프롬프트 수정 후 3회 반복 실행에서
 * 8~9/9로 안정적으로 정답, 4차 1개: 패턴2 프롬프트 수정 후 서로 다른 문구 버전 2개에서 연속
 * 3/3, 5차 2개(2026-09-21): 검증 데이터셋 라벨 오류로 확인돼 정정 후 3회 반복 9/9, 6차
 * 3개(2026-09-21): 개별 targeted 프롬프트 수정으로 3회 반복 8~9/9, 7차 1개(2026-09-21):
 * 검증 데이터셋 라벨 오류로 확인돼 팀 승인 후 정정, 재측정 3회 반복 9/9) = 38개. allowlist에
 * 없는 코드는 evaluator가 있어도 무시되고 {@code UNSUPPORTED_RULE}로 남는다.
 */
@Component
@ConfigurationProperties(prefix = "adcheck.rule-judge")
public class RuleJudgeProperties {

    private List<String> enabledRuleCodes = List.of(
            // 기존 운영 중(Common, 정규식) — 3개
            "C05_FUNCTION_EXCEED", "C07_ABSOLUTE_EFFECT", "C24_OVERCONSUMPTION",
            // LiteralRuleEvaluator(정규식, LLM 노이즈 없어 검증 완료) — 9개
            "C08_RESULT_TIME_AMOUNT", "C09_COMPLETE_SOLUTION", "G02_PERIOD", "T01_WEIGHT_RESULT",
            "T02_DETOX", "T03_DIET_DRUG", "B05_ANTIBACTERIAL_WORD", "M03_ALCOHOL", "S01_PAIN",
            // AiRuleEvaluator 1차 파일럿(검증 데이터셋 3회 반복 실행 9/9 안정적으로 정답) — 9개
            "B02_VIRUS", "C27_FUNCTION_SYNERGY", "G03_WEIGHT_FAT", "G04_SATIETY_COFFEE",
            "O01_SEXUAL", "P05_INFANT", "R01_COLD", "R02_MENOPAUSE", "T04_ANTIAGING",
            // AiRuleEvaluator 2차 확장(원래 "왔다갔다"했으나 2회 재실행 후 5회 중 4~5회 정답) — 7개
            "C01_DISEASE_PREVENTION", "C02_DISEASE_TREATMENT", "P01_VAGINAL_SCOPE",
            "E02_OTHER_FUNCTION", "E03_GENERATION", "O02_ENERGY_EXPANSION", "P03_DISEASE_GUT",
            // AiRuleEvaluator 3차 확장(패턴1 프롬프트 수정 후 3회 반복 실행 8~9/9로 안정) — 3개
            "E01_VESSEL", "L03_EYE_DISEASE", "C22_SUPERLATIVE",
            // AiRuleEvaluator 4차 확장(패턴2 "핵심 단어=자동매치 아님" 수정 후 서로 다른 프롬프트
            // 버전 2개에서 연속 3/3) — 1개
            "L01_VISION",
            // AiRuleEvaluator 5차 확장(검증 데이터셋 라벨 오류로 확인돼 팀 승인 후 정정 —
            // 라벨 정정 후 재측정하니 모델은 원래 정답을 내고 있었음, 3회 반복 9/9) — 2개
            "G05_GLUCOSE_DIET", "M04_REGEN_CANCER",
            // AiRuleEvaluator 6차 확장(개별 targeted 프롬프트 수정 3건 — C04는 구분 기호·출처
            // 인용을 인식하라는 경고 추가(56%→89%), C21은 실제 심의사례를 few-shot으로 추가
            // (67%→89%), C14는 별도 수정 없이 C22용 requiredEvidence 경고의 부수 효과로 이미
            // 100%였음을 확인만 함) — 3개
            "C04_DISEASE_INFO_LINK", "C14_EXPERT_ENDORSEMENT", "C21_UNFAIR_COMPARISON",
            // AiRuleEvaluator 7차 확장(검증 데이터셋 라벨 오류로 확인돼 팀 승인 후 정정 —
            // REVIEW-03 p.76 "특정 계절/날씨 관련 표현 사용 불가" 일괄 금지 조항과 실제
            // 심의사례 BAD-07 패턴 일치로 확인, 라벨 정정 후 재측정 3회 반복 9/9) — 1개
            "S04_SEASON"
    );

    /**
     * <b>배치에서 빼고 Claim마다 개별 호출할 규칙 코드.</b> 규칙 축 배치는 규칙 하나당 호출 1회로
     * 끝나 무료 티어 안에서 완주하게 해주지만, 같은 프롬프트 안의 다른 Claim이 판단에 영향을 줘서
     * 개별 호출과 다른 판정이 나오는 규칙이 있다. 여기 적힌 규칙만 예전처럼 Claim마다 부른다 —
     * 호출이 1회에서 Claim 수만큼 늘어나는 대신 개별 호출의 정확도를 되찾는 절충이다.
     *
     * <p>목록은 추측이 아니라 실측으로 정했다. AI 규칙 20개 전체를 배치로 돌려 검증 데이터셋
     * 라벨과 대조하고(합계 51/60), 어긋난 규칙은 2회 더 돌려 "배치라서 틀린 것"과 "원래 흔들리는
     * 것"을 갈랐다. 반면 E01_VESSEL은 회차마다 결과가 달라(flaky) 개별 호출로 바꿔도 소용이
     * 없으므로 넣지 않았다.
     *
     * <p>C22_SUPERLATIVE도 3회 내내 틀렸지만 <b>일부러 뺐다</b> — COMMON이라 모든 페이지·모든
     * Claim에 적용돼서, 제외하면 어느 페이지를 분석하든 Claim 수만큼 호출이 늘어난다(텍스트만
     * 있는 가벼운 페이지도 6회 → 11회). 같은 방향으로 재현되는 오류라 고칠 수 있는 문제로 보고
     * 프롬프트 수정으로 가기로 팀에서 정했다.
     *
     * <p><b>R02_MENOPAUSE·O02_ENERGY_EXPANSION은 여기 있었다가 뺐다(2026-09-21)</b> — 애초에
     * "배치가 라벨과 다르다"는 근거로 넣었는데, 그 라벨 자체가 검증 데이터셋 작성 오류였음이
     * REVIEW-03 원문 대조와 팀 승인으로 확인됐다. 라벨을 정정한 뒤 다시 재보니 배치 결과가
     * 오히려 원래부터 옳았다(R02 9/9, O02 8/9) — 배치가 틀렸던 게 아니라 정답이 틀렸던 것이다.
     *
     * <p><b>C21_UNFAIR_COMPARISON은 후보로 검토했다가 뺐다(2026-09-21)</b> — 배치 축 반복
     * 측정에서 18/24(75%)로 낮게 나와 여기 추가를 시도했지만, Claim마다 개별 호출로 재측정해도
     * 6/9(66.7%)로 똑같이 낮았다. "다른 제품보다 흡수가 잘 되는 이유가 있습니다"(REVIEW_REQUIRED
     * 기대) 건이 배치·개별 구분 없이 3회 내내 MATCHED로 틀려서, "다른 Claim이 섞여 흔들리는"
     * 배치 특유의 문제가 아니라 프롬프트 자체의 문제로 확인됐다 — 여기 넣어도 해결 안 된다.
     */
    private List<String> batchExcludedRuleCodes = List.of("B02_VIRUS", "L01_VISION");

    /**
     * 규칙 판정을 동시에 몇 개까지 진행할지. 규칙 하나당 호출 1회(모든 Claim 배치)이므로 이 값이
     * 곧 동시 Gemini 호출 수다 — 무료 티어 분당 한도(15회)를 한꺼번에 소진하지 않도록 보수적으로
     * 잡는다. 429가 나도 {@code GeminiClient}가 재시도하므로 치명적이진 않지만, 재시도 대기가
     * 오히려 전체를 느리게 만들 수 있다.
     */
    private int concurrency = 4;

    public List<String> getEnabledRuleCodes() {
        return enabledRuleCodes;
    }

    public void setEnabledRuleCodes(List<String> enabledRuleCodes) {
        this.enabledRuleCodes = enabledRuleCodes;
    }

    public List<String> getBatchExcludedRuleCodes() {
        return batchExcludedRuleCodes;
    }

    public void setBatchExcludedRuleCodes(List<String> batchExcludedRuleCodes) {
        this.batchExcludedRuleCodes = batchExcludedRuleCodes;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }
}
