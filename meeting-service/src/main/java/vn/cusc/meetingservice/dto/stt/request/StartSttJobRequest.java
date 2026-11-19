package vn.cusc.meetingservice.dto.stt.request;

import java.util.List;

public record StartSttJobRequest(
    String requestId,
    Long meetingId,
    List<String> manifestPaths
) {
}
