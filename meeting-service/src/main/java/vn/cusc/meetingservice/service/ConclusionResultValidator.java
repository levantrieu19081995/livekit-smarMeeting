package vn.cusc.meetingservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.cusc.meetingservice.dto.conclusion.ConclusionContracts.*;

@Component @RequiredArgsConstructor
public class ConclusionResultValidator {
    private final Validator validator;
    public void validate(Result result, JsonNode context) {
        if (result == null || !validator.validate(result).isEmpty()) throw new IllegalArgumentException("Invalid structured result");
        Set<String> refs = new HashSet<>(); refs.add(context.path("minutes").path("sourceRef").asText());
        if (!context.path("manual").path("content").asText().isBlank()) refs.add(context.path("manual").path("sourceRef").asText());
        for (String group : new String[]{"polls", "qa", "opinions"}) context.path(group).forEach(row -> refs.add(row.path("sourceRef").asText()));
        Stream.of(result.decisions(), result.pollConclusions(), result.unresolvedIssues()).flatMap(java.util.Collection::stream)
            .forEach(item -> checkRefs(item.sourceRefs(), refs));
        result.actionItems().forEach(task -> {
            checkRefs(task.sourceRefs(), refs);
            if (task.deadline() != null) LocalDate.parse(task.deadline());
        });
        if (ConclusionText.format(result).length() > 200000) throw new IllegalArgumentException("Result too large");
    }
    private void checkRefs(java.util.List<String> refs, Set<String> known) {
        if (refs.stream().anyMatch(ref -> !known.contains(ref))) throw new IllegalArgumentException("Unknown source");
    }
}
