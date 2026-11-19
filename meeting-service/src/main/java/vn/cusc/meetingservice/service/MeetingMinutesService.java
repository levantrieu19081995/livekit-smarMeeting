package vn.cusc.meetingservice.service;

import java.util.List;
import vn.cusc.meetingservice.dto.request.CreateMinutesVersionRequest;
import vn.cusc.meetingservice.dto.request.SaveMeetingMinutesRequest;
import vn.cusc.meetingservice.dto.request.UpdateMinutesDraftRequest;
import vn.cusc.meetingservice.dto.response.MeetingMinutesResponse;
import vn.cusc.meetingservice.dto.response.MinutesTranscriptEntryResponse;
import vn.cusc.meetingservice.dto.response.MinutesVersionResponse;

public interface MeetingMinutesService {
    List<MeetingMinutesResponse> getMinutes(Long meetingId);
    List<MinutesTranscriptEntryResponse> getTranscript(Long meetingId);
    MeetingMinutesResponse saveMinutes(Long meetingId, SaveMeetingMinutesRequest request);
    MinutesVersionResponse getCurrent(Long meetingId);
    List<MinutesVersionResponse> getVersions(Long meetingId);
    MinutesVersionResponse getVersion(Long meetingId, Integer version);
    MinutesVersionResponse createVersion(Long meetingId, CreateMinutesVersionRequest request);
    MinutesVersionResponse updateDraft(Long meetingId, Integer version, UpdateMinutesDraftRequest request);
    MinutesVersionResponse finalizeVersion(Long meetingId, Integer version);
}
