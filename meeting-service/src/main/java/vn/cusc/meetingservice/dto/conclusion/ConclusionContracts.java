package vn.cusc.meetingservice.dto.conclusion;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class ConclusionContracts {
    private ConclusionContracts() { }
    public record Generate(@NotNull @Min(1) Integer expectedMinutesVersion, @Size(max = 200000) String manualContent,
        @NotNull @Size(max = 200000) String expectedStoredConclusion) { }
    public record Item(@NotBlank @Size(max = 10000) String content,
        @NotNull @Size(min = 1, max = 20) List<@NotBlank String> sourceRefs) { }
    public record Task(@NotBlank @Size(max = 5000) String task, @Size(max = 255) String assignee,
        @Size(max = 10) String deadline, @NotNull @Size(min = 1, max = 20) List<@NotBlank String> sourceRefs) { }
    public record Result(@NotBlank @Size(max = 10000) String summary,
        @NotNull @Size(max = 50) List<@NotNull @Valid Item> decisions,
        @NotNull @Size(max = 200) List<@NotNull @Valid Item> pollConclusions,
        @NotNull @Size(max = 50) List<@NotNull @Valid Task> actionItems,
        @NotNull @Size(max = 50) List<@NotNull @Valid Item> unresolvedIssues) { }
    public record ProviderRequest(String generationId, Long meetingId, JsonNode context) { }
    public record ProviderResponse(String generationId, Result result) { }
    public record Work(Long meetingId, String generationId, String leaseToken, JsonNode context) { }
    public record View(String status, String stage, String generationId, Integer sourceMinutesVersion,
        String manualContentBeforeAi, Result aiGeneratedContent, String finalContent,
        LocalDateTime generatedAt, long revision, String errorCode, String errorMessage,
        boolean canManage, boolean canGenerate, Integer currentMinutesVersion) { }
}
