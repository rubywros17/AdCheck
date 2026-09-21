package com.adcheck.analysis.service;

import com.adcheck.product.repository.FunctionalIngredientRepository;
import com.adcheck.product.repository.IngredientMasterRepository;
import com.adcheck.product.repository.IngredientSynonymRepository;
import com.adcheck.product.repository.NotifiedIngredientRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code IngredientMatchingService}는 기동 시점에 findAll()로 인메모리 맵을 만드는
 * 싱글톤이라, 테스트에서 새로 넣은 DB 행이 오토와이어드 빈에 반영되지 않는다. 그래서
 * 클래스 javadoc이 문서화한 대로 패키지 프라이빗 생성자(리포지토리 findAll() 결과를
 * 그대로 넘기는)로 시드 직후 매번 새 인스턴스를 만든다 — {@code FindingAssemblerTest}가
 * 쓰는 것과 동일한 JdbcTemplate 시딩 패턴이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IngredientMatchingServiceTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private IngredientMasterRepository masterRepository;

    @Autowired
    private IngredientSynonymRepository synonymRepository;

    @Autowired
    private FunctionalIngredientRepository functionalIngredientRepository;

    @Autowired
    private NotifiedIngredientRepository notifiedIngredientRepository;

    private Long seedIngredient(String standardName, String ingredientCode, String... synonyms) {
        jdbc.update(
                "INSERT INTO ingredient_master (standard_name, ingredient_code, ingredient_category, support_status) "
                        + "VALUES (?, ?, 'NUTRIENT', 'SUPPORTED')",
                standardName, ingredientCode);
        Long masterId = jdbc.queryForObject(
                "SELECT id FROM ingredient_master WHERE ingredient_code = ?", Long.class, ingredientCode);
        for (String synonym : synonyms) {
            jdbc.update(
                    "INSERT INTO ingredient_synonyms (ingredient_master_id, raw_text) VALUES (?, ?)",
                    masterId, synonym);
        }
        return masterId;
    }

    private IngredientMatchingService freshService() {
        return new IngredientMatchingService(
                masterRepository.findAll(), synonymRepository.findAll(),
                functionalIngredientRepository.findAll(), notifiedIngredientRepository.findAll());
    }

    @Test
    void 끝단_함량단위가_붙어도_정규화되어_매칭된다() {
        seedIngredient("비타민C", "VITC001", "비타민C");
        IngredientMatchingService service = freshService();

        IngredientMatchResult result = service.match("비타민C 500mg");

        assertThat(result.matchStatus()).isEqualTo(IngredientMatchingService.MATCHED);
        assertThat(result.matchMethod()).isEqualTo(IngredientMatchingService.NORMALIZED_NAME);
        assertThat(result.standardName()).isEqualTo("비타민C");
    }

    @Test
    void 쉼표_포함_큰_숫자_함량단위도_정규화되어_매칭된다() {
        seedIngredient("EPA 및 DHA 함유 유지", "EPADHA001", "EPA 및 DHA 함유 유지");
        IngredientMatchingService service = freshService();

        IngredientMatchResult result = service.match("EPA 및 DHA 함유 유지 1,000mg");

        assertThat(result.matchStatus()).isEqualTo(IngredientMatchingService.MATCHED);
        assertThat(result.standardName()).isEqualTo("EPA 및 DHA 함유 유지");
    }

    @Test
    void 괄호_안_내용은_함량단위_제거_전처리에도_그대로_보존된다() {
        seedIngredient("정제어유", "FISHOIL001", "정제어유(EPA 18%이상)");
        IngredientMatchingService service = freshService();

        IngredientMatchResult withoutDosage = service.match("정제어유(EPA 18%이상)");
        IngredientMatchResult withDosage = service.match("정제어유(EPA 18%이상) 1,200mg");

        assertThat(withoutDosage.matchStatus()).isEqualTo(IngredientMatchingService.MATCHED);
        assertThat(withDosage.matchStatus()).isEqualTo(IngredientMatchingService.MATCHED);
        assertThat(withDosage.standardName()).isEqualTo("정제어유");
    }

    @Test
    void 원료명_자체에_포함된_숫자는_단위가_없으면_지워지지_않는다() {
        seedIngredient("비타민B12", "VITB12001", "비타민B12");
        IngredientMatchingService service = freshService();

        IngredientMatchResult result = service.match("비타민B12");

        assertThat(result.matchStatus()).isEqualTo(IngredientMatchingService.MATCHED);
        assertThat(result.standardName()).isEqualTo("비타민B12");
    }

    @Test
    void 단위만_다르고_내용이_다른_문자열은_여전히_UNMATCHED다() {
        seedIngredient("비타민C", "VITC002", "비타민C");
        IngredientMatchingService service = freshService();

        IngredientMatchResult result = service.match("전혀 다른 원료 500mg");

        assertThat(result.matchStatus()).isEqualTo(IngredientMatchingService.UNMATCHED);
    }
}
