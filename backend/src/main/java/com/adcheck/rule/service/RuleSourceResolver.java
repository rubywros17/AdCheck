package com.adcheck.rule.service;

import com.adcheck.rule.repository.RuleSourceRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.adcheck.rule.service.RuleAnalysisResult.SourceMetadata;

@Component
public class RuleSourceResolver {
    private final RuleSourceRepository sources;

    public RuleSourceResolver(RuleSourceRepository sources) { this.sources = sources; }

    public Map<Long, List<SourceMetadata>> resolve(Collection<Long> ruleIds) {
        Map<Long, List<SourceMetadata>> result = new LinkedHashMap<>();
        if (ruleIds.isEmpty()) return Map.of();
        for (var link : sources.findAllWithSourceByRuleIds(ruleIds)) {
            var source = link.getReferenceSource();
            result.computeIfAbsent(link.getRule().getId(), ignored -> new ArrayList<>()).add(
                    new SourceMetadata(source.getId(), source.getSourceId(), source.getTitle(),
                            source.getSourceType(), source.getIssuer(), source.getSourceUrl(),
                            source.getDocumentVersion(), source.getVerificationStatus(), source.getSection(),
                            source.getPrintedPage(), source.getPdfPage()));
        }
        result.replaceAll((id, values) -> List.copyOf(values));
        return Map.copyOf(result);
    }
}
