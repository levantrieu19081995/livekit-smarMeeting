package vn.cusc.meetingservice.client.capture;

import vn.cusc.meetingservice.dto.capture.CaptureServiceSessionResponse;
import vn.cusc.meetingservice.dto.capture.CaptureServiceStatusResponse;
import vn.cusc.meetingservice.dto.capture.StartCaptureRequest;
import vn.cusc.meetingservice.dto.capture.StopCaptureRequest;

public interface CaptureClient {
    CaptureServiceSessionResponse start(StartCaptureRequest request);
    CaptureServiceSessionResponse stop(Long meetingId, StopCaptureRequest request);
    CaptureServiceStatusResponse status(Long meetingId, Long captureSessionId, String externalSessionId);
}
