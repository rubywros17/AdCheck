package com.adcheck.product.service;

import com.adcheck.product.domain.FunctionalIngredient;
import com.adcheck.product.domain.IngredientMaster;
import com.adcheck.product.domain.NotifiedIngredient;
import com.adcheck.product.repository.FunctionalIngredientRepository;
import com.adcheck.product.repository.NotifiedIngredientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class OfficialFunctionQueryService {

    private final FunctionalIngredientRepository functionalIngredientRepository;
    private final NotifiedIngredientRepository notifiedIngredientRepository;

    public OfficialFunctionQueryService(
            FunctionalIngredientRepository functionalIngredientRepository,
            NotifiedIngredientRepository notifiedIngredientRepository
    ) {
        this.functionalIngredientRepository = functionalIngredientRepository;
        this.notifiedIngredientRepository = notifiedIngredientRepository;
    }

    public List<OfficialFunctionReadModel> findAllByIngredientMasterIds(
            List<Long> ingredientMasterIds
    ) {
        if (ingredientMasterIds == null || ingredientMasterIds.isEmpty()) {
            return List.of();
        }

        List<Long> queryIds = ingredientMasterIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (queryIds.isEmpty()) {
            return List.of();
        }

        List<OfficialFunctionReadModel> result = new ArrayList<>();
        Set<OfficialFunctionKey> seen = new LinkedHashSet<>();

        for (FunctionalIngredient ingredient
                : functionalIngredientRepository.findAllByIngredientMaster_IdIn(queryIds)) {
            addFunctionalIngredient(result, seen, ingredient);
        }
        for (NotifiedIngredient ingredient
                : notifiedIngredientRepository.findAllByIngredientMaster_IdIn(queryIds)) {
            addNotifiedIngredient(result, seen, ingredient);
        }

        return List.copyOf(result);
    }

    private void addFunctionalIngredient(
            List<OfficialFunctionReadModel> result,
            Set<OfficialFunctionKey> seen,
            FunctionalIngredient ingredient
    ) {
        if (ingredient == null) {
            return;
        }

        IngredientMaster master = ingredient.getIngredientMaster();
        if (master == null || master.getId() == null) {
            return;
        }

        OfficialFunctionReadModel model = new OfficialFunctionReadModel(
                master.getId(),
                master.getStandardName(),
                ingredient.getOfficialFunctionRaw(),
                OfficialFunctionReadModel.SourceType.FUNCTIONAL,
                ingredient.getRecognitionNo(),
                ingredient.getSourceName()
        );
        addIfAbsent(result, seen, model);
    }

    private void addNotifiedIngredient(
            List<OfficialFunctionReadModel> result,
            Set<OfficialFunctionKey> seen,
            NotifiedIngredient ingredient
    ) {
        if (ingredient == null) {
            return;
        }

        IngredientMaster master = ingredient.getIngredientMaster();
        if (master == null || master.getId() == null) {
            return;
        }

        OfficialFunctionReadModel model = new OfficialFunctionReadModel(
                master.getId(),
                master.getStandardName(),
                ingredient.getOfficialFunctionRaw(),
                OfficialFunctionReadModel.SourceType.NOTIFIED,
                null,
                ingredient.getSourceName()
        );
        addIfAbsent(result, seen, model);
    }

    private void addIfAbsent(
            List<OfficialFunctionReadModel> result,
            Set<OfficialFunctionKey> seen,
            OfficialFunctionReadModel model
    ) {
        OfficialFunctionKey key = new OfficialFunctionKey(
                model.ingredientMasterId(),
                model.sourceType(),
                model.recognitionNo(),
                model.officialFunctionText()
        );
        if (seen.add(key)) {
            result.add(model);
        }
    }

    private record OfficialFunctionKey(
            Long ingredientMasterId,
            OfficialFunctionReadModel.SourceType sourceType,
            String recognitionNo,
            String officialFunctionText
    ) {
    }
}
