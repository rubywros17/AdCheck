package com.adcheck.analysis.service;

import com.adcheck.product.service.OfficialFunctionReadModel;
import com.adcheck.product.service.ProductIngredientReadModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@link ProductIngredientReadModel}/{@link OfficialFunctionReadModel} 조회 결과를 AI #2
 * (Comparison) 입력 계약({@link ConfirmedIngredient}/{@link OfficialFunction})으로 조립한다.
 *
 * <p>결합 키는 반드시 {@code ingredientMasterId}(Long)만 쓴다 — 리스트 순서/길이를 가정한
 * 위치 기반 결합(zip)은 하지 않는다. {@code OfficialFunctionQueryService}는 원료 하나당
 * 0건(공식 기능성 정보가 없는 원료가 대부분) 또는 여러 건(원료 하나에 recognitionNo가 여러 개인
 * 경우 등)을 반환할 수 있어 두 리스트의 길이가 서로 다를 수 있기 때문이다.
 *
 * <p>{@code canonicalName}은 {@link ProductIngredientReadModel} 쪽에서 한 번만 가져와
 * 단일 진실 공급원으로 쓴다 — {@link OfficialFunctionReadModel#canonicalName()} 자체는
 * 독립적으로 신뢰하지 않는다. 지금은 두 서비스 모두 {@code IngredientMaster.standardName}을
 * 무가공으로 반환하므로 항상 같은 값이지만(2026-09-15 조사로 확인됨), 이 어댑터가 한 곳만
 * 신뢰하는 구조로 짜두면 둘 중 하나가 나중에 로직을 바꿔도 영향받지 않는다.
 *
 * <p>{@link ConfirmedIngredient#ingredientCode()}는 {@link ProductIngredientReadModel}에
 * 애초에 담겨 있지 않은 필드다({@code IngredientMaster.ingredientCode}는 607개 원료 중
 * MVP 10개만 값이 있어 조인 키로 부적합하다고 이미 확인됨 — canonicalName 채택 근거).
 * 조인/매칭에 전혀 쓰이지 않는 참고용 부가 필드라 지금은 항상 {@code null}로 둔다.
 */
@Component
public class ConfirmedIngredientAssembler {

    private static final Logger log = LoggerFactory.getLogger(ConfirmedIngredientAssembler.class);

    /**
     * @param productIngredients Product 1건에 대해 확정된 원료 목록 — canonicalName의
     *                           유일한 출처.
     * @param officialFunctions  {@code productIngredients}에서 뽑은 ingredientMasterId
     *                           집합으로 조회한 공식 기능성 목록 — 호출자는 반드시 이
     *                           집합을 그대로 써서 조회해야 한다(별도 소스로 만든 id
     *                           목록을 넘기지 말 것).
     */
    public Assembled assemble(
            List<ProductIngredientReadModel> productIngredients,
            List<OfficialFunctionReadModel> officialFunctions
    ) {
        Map<Long, String> canonicalNameByIngredientMasterId = productIngredients.stream()
                .collect(Collectors.toMap(
                        ProductIngredientReadModel::ingredientMasterId,
                        ProductIngredientReadModel::canonicalName,
                        (first, second) -> first
                ));

        List<ConfirmedIngredient> confirmedIngredients = productIngredients.stream()
                .map(model -> new ConfirmedIngredient(null, model.canonicalName()))
                .toList();

        List<OfficialFunction> officialFunctionContracts = officialFunctions.stream()
                .map(model -> toOfficialFunction(model, canonicalNameByIngredientMasterId))
                .filter(Objects::nonNull)
                .toList();

        return new Assembled(confirmedIngredients, officialFunctionContracts);
    }

    private OfficialFunction toOfficialFunction(
            OfficialFunctionReadModel model,
            Map<Long, String> canonicalNameByIngredientMasterId
    ) {
        String canonicalName = canonicalNameByIngredientMasterId.get(model.ingredientMasterId());
        if (canonicalName == null) {
            log.warn(
                    "확정 원료 목록에 없는 ingredientMasterId={}의 공식 기능성이 조회되어 스킵합니다.",
                    model.ingredientMasterId()
            );
            return null;
        }
        return new OfficialFunction(canonicalName, model.officialFunctionText());
    }

    public record Assembled(
            List<ConfirmedIngredient> confirmedIngredients,
            List<OfficialFunction> officialFunctions
    ) {
    }
}
