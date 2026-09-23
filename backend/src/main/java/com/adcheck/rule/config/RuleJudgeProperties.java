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
 * 검증 데이터셋 라벨 오류로 확인돼 팀 승인 후 정정, 재측정 3회 반복 9/9, 8차 6개(2026-09-21):
 * 미조사 백로그를 규칙별 few-shot·실제 심의사례 인용으로 수정 — 1차 수정 후 같은 규칙 안
 * 트레이드오프가 발견돼 2차 보정까지 거쳐 3회 반복 9/9, 9차 2개(2026-09-21): B03은 테스트
 * 하네스가 officialFunctions를 항상 비워 보내던 버그 수정 + 라벨 오류 정정, C03은 라벨
 * 오류 정정 + 심의기준 용어 수정표 인용 프롬프트 수정 — 각각 3회 반복 9/9, 10차 4개
 * (2026-09-22): S02·E04·B01·M02 — 각각 라벨 오류 정정 및/또는 실제 심의사례·심의기준
 * 원문 인용 프롬프트 수정으로 3회 반복 9/9. C13_TESTIMONIAL은 같이 조사했지만 남은
 * 케이스("5kg 감량")가 원출처(DISC-15)부터 "논의 필요"로 남겨진 진짜 애매 사례라 팀
 * 승인 하에 라벨은 그대로 두고 이번 확장에서 제외) = 50개. allowlist에 없는 코드는
 * evaluator가 있어도 무시되고 {@code UNSUPPORTED_RULE}로 남는다.
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
            "S04_SEASON",
            // AiRuleEvaluator 8차 확장(미조사 백로그 6개, 각각 규칙 코드로 게이팅한 targeted
            // 프롬프트 수정 — C11은 부원료 나열 시 매체·함량표기 확인 필요 경고, C28은 "인구 중
            // 몇 %가 해당하는가" 판단 기준 추가, C30은 무첨가/무검출 무근거 단정이 그 자체로
            // 위반임을 명시, G01은 "섭취 간편한" vs "간편한 다이어트" 경계 설명, L02는 "눈은
            // 있지만 자외선 언급 없음"을 명시적 REVIEW_REQUIRED 트리거로 지정, M01은 실제
            // 사례(BAD-05)와 "정확한 단어 아니라 의미로 판단" 일반화 문구 추가 — 1차 수정 후
            // C28·L02·M01에서 같은 규칙 안 다른 케이스가 새로 틀리는 트레이드오프가 발견돼 2차
            // 보정까지 거침, 최종 6개 전체 3회 반복 9/9) — 6개
            "C11_SUB_INGREDIENT_FUNCTION", "C28_TARGET_SPECIALIZATION", "C30_NATURAL_FREE",
            "G01_EASY_DIET", "L02_UV", "M01_FATIGUE",
            // AiRuleEvaluator 9차 확장(2026-09-21) — B03_OTHER_ORAL: 테스트 하네스가
            // officialFunctions를 항상 빈 값으로 넘겨 반복검증 자체가 안 됐던 문제를 실제
            // 원료(프로폴리스) 공식 기능성 문구로 고침 + REVIEW_REQUIRED 라벨이 REVIEW-03
            // "미세먼지"·"목관리/기관지" 일괄 금지 조항과 맞지 않아 팀 승인 후 MATCHED로
            // 정정. C03_MEDICINE_CONFUSION: REVIEW_REQUIRED 라벨이 REVIEW-01 용어
            // 수정표("약국용"→"약국 내 건강기능식품코너")와 맞지 않아 팀 승인 후 MATCHED로
            // 정정 + 그 용어 수정표를 인용하는 프롬프트 수정 추가 — 각각 3회 반복 9/9 — 2개
            "B03_OTHER_ORAL", "C03_MEDICINE_CONFUSION",
            // AiRuleEvaluator 10차 확장(2026-09-22) — S02_BODY_AREA: REVIEW-03 p.75(MSM)
            // "허리/목/척추/고관절 사용 불가" 일괄 금지 조항 인용. E04_ALIAS: 라벨 오류
            // 정정(팀 승인, NOTICE-01·DISC-18 대조 — "지방산 복합체"는 정식 명칭 아님) +
            // NOTICE-01 원문 인용. B01_IMMUNE_INFLAMMATION: BAD-09 few-shot(질환 증상
            // 나열 케이스) + 라벨 오류 정정(팀 승인, REVIEW-03 p.79 "감염, 염증" 일괄 금지).
            // M02_LIVER_MARKER: 테스트 하네스 개선 + 라벨 오류 정정(팀 승인, DISC-09 —
            // "내장지방"은 M02가 아니라 G03 영역) + "간 지표 전용" 명시 프롬프트 수정.
            // 4개 전부 3회 반복 9/9 — 4개
            "S02_BODY_AREA", "E04_ALIAS", "B01_IMMUNE_INFLAMMATION", "M02_LIVER_MARKER"
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
     * 곧 동시 Gemini 호출 수다.
     *
     * <p><b>4에서 12로 올렸다</b>(2026-09-23). OCR이 Google Cloud Vision으로 넘어가면서 규칙
     * 판정이 파이프라인 최대 병목이 됐는데(전체 약 11초 중 5.6~6.2초), 그 시간의 상당 부분이
     * 추론이 아니라 <b>줄서기</b>였다 — 호출 12회를 4개씩 나눠 돌리면 3웨이브가 되고, 개별 호출이
     * 1.2~2.5초이므로 3웨이브 ≈ 5.4초가 된다. 규칙끼리는 완전히 독립적이라 한 번에 던져도 판정
     * 내용은 바뀌지 않는다.
     *
     * <p><b>분당 한도와의 관계</b>: 무료 티어 15회/분은 "1분에 몇 번 불렀나"를 보므로, 동시성을
     * 올려도 <b>분석 1건이 쓰는 호출 수는 12~13회로 동일하다</b> — 같은 양을 더 빨리 소비할 뿐이다.
     * 따라서 분석을 하나씩 돌리는 전제에서는 quota 중립이다. 실제로 오늘 관찰한 429는 동시성이
     * 아니라 <b>분석을 30초 간격으로 연달아 돌려서</b> 났다(분석 2건이면 같은 분에 24~26회).
     *
     * <p><b>전제</b>: 사용자 한 명이 분석을 하나씩 돌리는 상황을 가정한 값이다. 여러 사용자의
     * 분석이 겹치는 실서비스에서는 이 값과 무관하게 분당 한도를 넘으므로, 그때는 사전 스로틀링을
     * 넣거나 유료 티어로 올려야 한다.
     */
    private int concurrency = 12;

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
