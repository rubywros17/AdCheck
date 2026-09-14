package com.adcheck.rule.service;

import com.adcheck.rule.domain.Rule;
import com.adcheck.rule.repository.RuleIngredientRepository;
import com.adcheck.rule.repository.RuleRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RuleSelector {
    private final RuleRepository rules;
    private final RuleIngredientRepository ingredients;

    public RuleSelector(RuleRepository rules, RuleIngredientRepository ingredients) {
        this.rules = rules;
        this.ingredients = ingredients;
    }

    public List<Rule> select(Set<Long> confirmedIds) {
        Map<Long, Rule> selected = new LinkedHashMap<>();
        rules.findAllByScopeTypeOrderByRuleCodeAsc("COMMON").forEach(r -> selected.put(r.getId(), r));
        if (!confirmedIds.isEmpty()) {
            ingredients.findIngredientSpecificRules(confirmedIds).forEach(r -> selected.put(r.getId(), r));
        }
        return selected.values().stream().sorted(Comparator.comparing(Rule::getRuleCode)).toList();
    }
}
