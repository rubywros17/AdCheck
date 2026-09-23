package com.adcheck.analysis.service;

import com.adcheck.product.domain.FunctionalIngredient;
import com.adcheck.product.domain.IngredientMaster;
import com.adcheck.product.domain.IngredientSynonym;
import com.adcheck.product.domain.NotifiedIngredient;
import com.adcheck.product.repository.FunctionalIngredientRepository;
import com.adcheck.product.repository.IngredientMasterRepository;
import com.adcheck.product.repository.IngredientSynonymRepository;
import com.adcheck.product.repository.NotifiedIngredientRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 원료 표기 문자열 매칭 — Data Contract v0.2 확정 기준(팀원 협의 완료)으로 재작성.
 *
 * <p>v0.1 프로토타입에 있던 PARTIAL/FUZZY(부분 문자열·유사도 기반 자동 연결)는 계약 원칙
 * ("이름이 일부 비슷하다는 이유만으로 fuzzy match하여 자동 연결하지 않는다")에 위배돼서
 * 제거했다. PROBIOTIC_GENUS(학명 패턴 매칭)와 NOT_FUNCTIONAL_INGREDIENT(비기능성 원료 판정)도
 * 별도 특수 처리가 아니라, 동의어사전이 rawText→ingredientMasterId로 직접 연결되도록 바뀌면서
 * 자연스럽게 같은 매칭 경로(동의어 정확일치)로 흡수됐다 — 학명 표기든 아니든 동의어사전에
 * 정확히 등록돼 있으면 매칭되고, 그 결과가 비기능성 원료(ingredientCategory=NON_FUNCTIONAL)인지는
 * ingredientMasterId로 호출자가 확인하면 된다.
 *
 * <p>매칭 우선순위 (Data Contract v0.2 §11, ①은 productType 전용이라 이 서비스 밖에서 처리):
 * <ol>
 *   <li>원료 표기 문자열 안의 인정번호로 직접 조회(RAW_RECOGNITION_NO) — 동일 인정번호가
 *       서로 다른 표준원료로 연결되면 확정하지 않고 REVIEW_REQUIRED</li>
 *   <li>동의어사전(rawText→ingredientMasterId) 정확일치(SYNONYM_EXACT)</li>
 *   <li>정규화한 이름이 개별인정형/고시형 공식명과 완전일치(NORMALIZED_NAME)</li>
 *   <li>위 전부 실패 시 UNMATCHED — 부분일치·유사도로 억지 연결하지 않음</li>
 * </ol>
 *
 * <p>기동 시점에 우리 쪽 {@code com.adcheck.product} 도메인의 JPA Repository로 4개 테이블
 * (ingredient_master/ingredient_synonyms/functional_ingredients/notified_ingredients)을
 * 한 번에 읽어 인메모리 맵을 구성한다 — 매칭 로직 자체는 원본과 동일하고, 원본이 참조하던
 * {@code com.adcheck.ingredient.*}(별도 엔티티군, 이식 대상 아님) 대신 우리 쪽에 이미 있는
 * {@code com.adcheck.product.domain.*} 엔티티/Repository를 그대로 재사용한다. 원본의
 * 평평한 {@code getIngredientMasterId()} 호출은 우리 엔티티의 {@code @ManyToOne}
 * 관계({@code getIngredientMaster().getId()})로 치환했다. 테스트는 DB 없이도 빠르게
 * 돌아가야 하므로, 패키지 프라이빗 생성자(4개 엔티티 리스트를 직접 받는)를 매칭 로직의
 * 유일한 진입점으로 두고, Spring 생성자는 findAll() 결과를 그대로 위임한다.
 */
@Service
public class IngredientMatchingService {

    private static final Pattern RECOGNITION_NO_PATTERN = Pattern.compile("인정제\\s*([0-9]{4}\\s*-\\s*[0-9]+)\\s*호");
    private static final Pattern FALLBACK_NO_PATTERN = Pattern.compile("제\\s*([0-9]{4}\\s*-\\s*[0-9]+)\\s*호");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    /**
     * 원료명 끝에 붙은 "숫자+함량단위"를 제거한다(예: "비타민c500mg" -> "비타민c"). 반드시
     * 문자열 끝(anchor $)에서만, 단위가 실제로 붙어있을 때만 지운다 — 숫자만 있고 단위가
     * 없으면(예: "비타민b12") 매치되지 않아 원료명 자체의 숫자는 건드리지 않는다. 괄호로
     * 끝나는 문자열은 $ 바로 앞이 ')'라 이 패턴이 애초에 매치되지 않으므로, 괄호 안 내용은
     * IngredientSplitter가 보장하는 괄호 균형과 무관하게 항상 그대로 보존된다. whitespace
     * 제거·소문자화 다음 단계로 적용하므로 단위 앞뒤 공백·대소문자는 이미 정리된 상태다.
     */
    private static final Pattern TRAILING_DOSAGE_PATTERN =
            Pattern.compile("(?:[0-9][0-9,.]*(?:mg|g|㎎|㎍|μg|ug|iu|%)(?:/[가-힣a-z]+)?)+$");

    public static final String MATCHED = "MATCHED";
    public static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";
    public static final String UNMATCHED = "UNMATCHED";

    public static final String RAW_RECOGNITION_NO = "RAW_RECOGNITION_NO";
    public static final String PRODUCT_TYPE_RECOGNITION_NO = "PRODUCT_TYPE_RECOGNITION_NO";
    public static final String SYNONYM_EXACT = "SYNONYM_EXACT";
    public static final String NORMALIZED_NAME = "NORMALIZED_NAME";

    private final Map<Long, IngredientMaster> masterById = new HashMap<>();
    private final Map<String, List<Long>> functionalMasterIdsByRecognitionNo = new HashMap<>();
    private final Map<String, Long> masterIdByExactSynonym = new LinkedHashMap<>();
    private final Map<String, Long> masterIdByNormalizedSynonym = new LinkedHashMap<>();
    private final Map<String, Long> functionalMasterIdByNormalizedName = new LinkedHashMap<>();
    private final Map<String, Long> notifiedMasterIdByNormalizedName = new LinkedHashMap<>();

    @Autowired
    public IngredientMatchingService(
            IngredientMasterRepository masterRepository,
            IngredientSynonymRepository synonymRepository,
            FunctionalIngredientRepository functionalIngredientRepository,
            NotifiedIngredientRepository notifiedIngredientRepository
    ) {
        this(masterRepository.findAll(), synonymRepository.findAll(),
                functionalIngredientRepository.findAll(), notifiedIngredientRepository.findAll());
    }

    /** DB/Spring 없이 테스트에서 조립한 리스트로 바로 생성하기 위한 통로. */
    IngredientMatchingService(
            List<IngredientMaster> masters,
            List<IngredientSynonym> synonyms,
            List<FunctionalIngredient> functionalIngredients,
            List<NotifiedIngredient> notifiedIngredients
    ) {
        for (IngredientMaster master : masters) {
            masterById.put(master.getId(), master);
        }

        for (FunctionalIngredient rec : functionalIngredients) {
            Long ingredientMasterId = rec.getIngredientMaster() != null ? rec.getIngredientMaster().getId() : null;
            if (rec.getRecognitionNo() != null && !rec.getRecognitionNo().isBlank() && ingredientMasterId != null) {
                functionalMasterIdsByRecognitionNo
                        .computeIfAbsent(rec.getRecognitionNo().strip(), k -> new ArrayList<>())
                        .add(ingredientMasterId);
            }
            if (rec.getIngredientName() != null && ingredientMasterId != null) {
                functionalMasterIdByNormalizedName.putIfAbsent(normalizedKey(rec.getIngredientName()), ingredientMasterId);
            }
        }

        for (NotifiedIngredient rec : notifiedIngredients) {
            Long ingredientMasterId = rec.getIngredientMaster() != null ? rec.getIngredientMaster().getId() : null;
            if (rec.getStandardIngredientName() != null && ingredientMasterId != null) {
                notifiedMasterIdByNormalizedName.putIfAbsent(
                        normalizedKey(rec.getStandardIngredientName()), ingredientMasterId);
            }
        }

        for (IngredientSynonym entry : synonyms) {
            Long ingredientMasterId = entry.getIngredientMaster() != null ? entry.getIngredientMaster().getId() : null;
            if (entry.getRawText() != null && ingredientMasterId != null) {
                masterIdByExactSynonym.putIfAbsent(entry.getRawText().strip(), ingredientMasterId);
                masterIdByNormalizedSynonym.putIfAbsent(normalizedKey(entry.getRawText()), ingredientMasterId);
            }
        }
    }

    /**
     * 공백(개행 포함) 제거 + 소문자화 + 끝단 함량단위 제거를 한다 — 괄호 안 내용은 절대
     * 지우지 않는다. 팀원의 실제 계산 결과(product_ingredient_matches_v0.2.csv)로
     * 검증해보니, 괄호를 통째로 지워 비교하면(예: "인삼분말(가루, 과립)(인삼근 70%, 인삼미삼
     * 30%)" -> "인삼분말") 원래 UNMATCHED여야 할 설명형 문자열이 괄호 없는 동의어와
     * 우연히 같아져 오매칭이 발생했다. 반대로 "Bifidobacteriumbifidum(고시형)"처럼 공백
     * 유무 차이만 있는 표기는 동의어사전에 공백 포함 형태 그대로(괄호까지 포함해서) 등록돼
     * 있어서, 공백만 제거해도 정확히 일치한다.
     *
     * <p>끝단 함량단위 제거는 "비타민c500mg" -> "비타민c"처럼 상세페이지 원료표시(원료명 뒤에
     * 실제 함량이 따라붙는 표기)가 동의어사전의 순수 원료명과 매칭되게 하려고 추가했다
     * ({@link #TRAILING_DOSAGE_PATTERN} 참고) — 위 괄호 보존 원칙, 완전일치만 쓰는 원칙과
     * 동일하게 적용된다.
     */
    private static String normalizedKey(String name) {
        String noWhitespace = WHITESPACE_PATTERN.matcher(name).replaceAll("").toLowerCase();
        return TRAILING_DOSAGE_PATTERN.matcher(noWhitespace).replaceAll("");
    }

    /** 인정번호 패턴을 뽑아서 조회한다. productType 문자열에도 그대로 재사용 가능(①번 매칭용). */
    IngredientMatchResult matchByEmbeddedRecognitionNo(String rawInput, String matchMethodLabel) {
        String raw = rawInput.strip();
        Matcher m = RECOGNITION_NO_PATTERN.matcher(raw);
        Matcher found = m.find() ? m : null;
        if (found == null) {
            Matcher fallback = FALLBACK_NO_PATTERN.matcher(raw);
            found = fallback.find() ? fallback : null;
        }
        if (found == null) {
            return null;
        }
        String recognitionNo = found.group(1).replaceAll("\\s", "");
        List<Long> candidates = functionalMasterIdsByRecognitionNo.get(recognitionNo);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        Set<Long> distinct = new HashSet<>(candidates);
        if (distinct.size() > 1) {
            return new IngredientMatchResult(raw, null, null, null, matchMethodLabel, REVIEW_REQUIRED);
        }
        Long masterId = distinct.iterator().next();
        return matchedResult(raw, masterId, matchMethodLabel);
    }

    public IngredientMatchResult match(String rawInput) {
        String raw = rawInput.strip();

        IngredientMatchResult byRecognitionNo = matchByEmbeddedRecognitionNo(raw, RAW_RECOGNITION_NO);
        if (byRecognitionNo != null) {
            return byRecognitionNo;
        }

        Long exactSynonymMasterId = masterIdByExactSynonym.get(raw);
        if (exactSynonymMasterId != null) {
            return matchedResult(raw, exactSynonymMasterId, SYNONYM_EXACT);
        }

        String normRaw = normalizedKey(raw);

        Long normalizedSynonymMasterId = masterIdByNormalizedSynonym.get(normRaw);
        if (normalizedSynonymMasterId != null) {
            return matchedResult(raw, normalizedSynonymMasterId, NORMALIZED_NAME);
        }
        Long functionalMasterId = functionalMasterIdByNormalizedName.get(normRaw);
        if (functionalMasterId != null) {
            return matchedResult(raw, functionalMasterId, NORMALIZED_NAME);
        }
        Long notifiedMasterId = notifiedMasterIdByNormalizedName.get(normRaw);
        if (notifiedMasterId != null) {
            return matchedResult(raw, notifiedMasterId, NORMALIZED_NAME);
        }

        return new IngredientMatchResult(raw, null, null, null, null, UNMATCHED);
    }

    public IngredientFieldMatchResult matchField(String rawField) {
        IngredientSplitter.SplitResult split = IngredientSplitter.split(rawField);
        List<IngredientMatchResult> items = split.items().stream().map(this::match).toList();

        // 괄호 짝이 안 맞아 통째로 한 항목이 된 경우(상세페이지 텍스트가 중간에 잘린 경우가
        // 대부분이다), 아무것도 못 건졌을 때만 짝 없는 괄호를 지우고 한 번 더 쪼개본다.
        // 원래 경로에서 하나라도 인식됐으면 건드리지 않으므로 기존 매칭 결과는 그대로 유지된다.
        if (!split.parsed() && items.stream().allMatch(item -> UNMATCHED.equals(item.matchStatus()))) {
            List<String> lenient = IngredientSplitter.splitLenient(rawField);
            if (!lenient.isEmpty()) {
                List<IngredientMatchResult> retried = lenient.stream().map(this::match).toList();
                if (retried.stream().anyMatch(item -> !UNMATCHED.equals(item.matchStatus()))) {
                    return new IngredientFieldMatchResult(false, retried);
                }
            }
        }
        return new IngredientFieldMatchResult(split.parsed(), items);
    }

    private IngredientMatchResult matchedResult(String rawText, Long masterId, String method) {
        IngredientMaster master = masterById.get(masterId);
        String standardName = master != null ? master.getStandardName() : null;
        String ingredientCode = master != null ? master.getIngredientCode() : null;
        return new IngredientMatchResult(rawText, masterId, ingredientCode, standardName, method, MATCHED);
    }
}
