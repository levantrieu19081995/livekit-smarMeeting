package vn.cusc.meetingservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import vn.cusc.meetingservice.dto.conclusion.ConclusionContracts.Result;

public final class ConclusionText {
    private ConclusionText() { }
    public static String manual(JsonNode content) {
        if (content == null) return "";
        if (content.path("conclusion").isTextual()) return content.path("conclusion").asText();
        if (content.path("conclusions").isArray()) {
            List<String> lines = new ArrayList<>(); content.path("conclusions").forEach(v -> { if (v.isTextual()) lines.add(v.asText()); });
            return String.join("\n", lines);
        }
        return "";
    }
    public static String main(JsonNode content) {
        if (content == null || content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        for (String field : List.of("mainContent", "text", "content", "minutesText")) {
            if (content.path(field).isTextual()) return content.path(field).asText();
        }
        return content.toString();
    }
    public static String format(Result result) {
        StringBuilder text = new StringBuilder("Tổng quan\n").append(result.summary());
        if (!result.decisions().isEmpty()) {
            text.append("\n\nNội dung đã thống nhất"); result.decisions().forEach(item -> text.append("\n- ").append(item.content()));
        }
        if (!result.pollConclusions().isEmpty()) {
            text.append("\n\nKết quả biểu quyết"); result.pollConclusions().forEach(item -> text.append("\n- ").append(item.content()));
        }
        if (!result.actionItems().isEmpty()) {
            text.append("\n\nCông việc cần thực hiện"); result.actionItems().forEach(item -> {
                text.append("\n- ").append(item.task());
                if (item.assignee() != null) text.append(" | Phụ trách: ").append(item.assignee());
                if (item.deadline() != null) text.append(" | Thời hạn: ").append(item.deadline());
            });
        }
        if (!result.unresolvedIssues().isEmpty()) {
            text.append("\n\nVấn đề chưa thống nhất"); result.unresolvedIssues().forEach(item -> text.append("\n- ").append(item.content()));
        }
        return text.toString();
    }
}
