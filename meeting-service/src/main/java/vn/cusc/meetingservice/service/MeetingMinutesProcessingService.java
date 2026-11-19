package vn.cusc.meetingservice.service;

import vn.cusc.meetingservice.dto.response.MinutesProcessingResponse;

public interface MeetingMinutesProcessingService {
    MinutesProcessingResponse generate(Long meetingId);
    MinutesProcessingResponse getProcessing(Long meetingId);
    MinutesProcessingResponse retry(Long meetingId);
}
