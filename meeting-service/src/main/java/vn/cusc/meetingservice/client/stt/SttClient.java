package vn.cusc.meetingservice.client.stt;

import java.util.Optional;
import vn.cusc.meetingservice.dto.stt.request.StartSttJobRequest;
import vn.cusc.meetingservice.dto.stt.response.SttJobAcceptedResponse;
import vn.cusc.meetingservice.dto.stt.response.SttJobResultResponse;
import vn.cusc.meetingservice.dto.stt.response.SttJobStatusResponse;

public interface SttClient {

    SttJobAcceptedResponse startJob(
        StartSttJobRequest request
    );

    SttJobResultResponse getResult(
        String processingId
    );

    Optional<SttJobStatusResponse> getStatus(
        String processingId
    );
}
