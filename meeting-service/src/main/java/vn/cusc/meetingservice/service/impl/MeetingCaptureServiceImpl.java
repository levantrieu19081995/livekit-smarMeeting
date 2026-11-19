package vn.cusc.meetingservice.service.impl;

import java.util.List;
import org.springframework.stereotype.Service;
import vn.cusc.meetingservice.client.capture.CaptureClient;
import vn.cusc.meetingservice.config.LiveKitProperties;
import vn.cusc.meetingservice.dto.capture.CaptureServiceSessionResponse;
import vn.cusc.meetingservice.dto.capture.StartCaptureRequest;
import vn.cusc.meetingservice.dto.capture.StopCaptureRequest;
import vn.cusc.meetingservice.dto.response.MeetingCaptureResponse;
import vn.cusc.meetingservice.entity.MeetingCaptureSessionEntity;
import vn.cusc.meetingservice.model.enums.CaptureStatus;
import vn.cusc.meetingservice.repository.MeetingCaptureSessionRepository;
import vn.cusc.meetingservice.service.MeetingCaptureService;
import vn.cusc.meetingservice.service.MeetingCaptureStateService;
import vn.cusc.meetingservice.service.MeetingCaptureStateService.StartClaim;
import vn.cusc.meetingservice.service.MeetingCaptureStateService.StopClaim;

@Service
public class MeetingCaptureServiceImpl implements MeetingCaptureService {

    private final MeetingCaptureStateService stateService;
    private final MeetingCaptureSessionRepository captureRepository;
    private final CaptureClient captureClient;
    private final LiveKitProperties liveKitProperties;
    private vn.cusc.meetingservice.service.MeetingCaptureRecoveryService recovery;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRecovery(vn.cusc.meetingservice.service.MeetingCaptureRecoveryService service) { recovery = service; }

    public MeetingCaptureServiceImpl(
        MeetingCaptureStateService stateService,
        MeetingCaptureSessionRepository captureRepository,
        CaptureClient captureClient,
        LiveKitProperties liveKitProperties
    ) {
        this.stateService = stateService;
        this.captureRepository = captureRepository;
        this.captureClient = captureClient;
        this.liveKitProperties = liveKitProperties;
    }

    @Override
    public MeetingCaptureResponse getStatus(Long meetingId) {
        stateService.requireControlPermissionRead(meetingId);
        if (recovery != null) recovery.refreshObservedSession(meetingId);
        return captureRepository.findFirstByMeetingIdOrderByIdDesc(meetingId)
            .map(this::toResponse)
            .orElseGet(this::notStartedResponse);
    }

    @Override
    public List<MeetingCaptureResponse> getSessions(Long meetingId) {
        stateService.requireControlPermissionRead(meetingId);
        return captureRepository.findAllByMeetingIdOrderByStartedAtAscIdAsc(meetingId).stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    public MeetingCaptureResponse start(Long meetingId) {
        StartClaim claim = stateService.claimStart(meetingId);
        if (!claim.remoteStartRequired()) {
            return toResponse(claim.session());
        }

        try {
            CaptureServiceSessionResponse response = captureClient.start(new StartCaptureRequest(
                meetingId,
                claim.session().getId(),
                claim.roomName(),
                liveKitProperties.getUrl(),
                "CAPTURE_MEETING_" + meetingId
            ));
            return toResponse(stateService.markStartSucceeded(claim.session().getId(), response));
        } catch (RuntimeException exception) {
            throw exception;
        }
    }

    @Override
    public MeetingCaptureResponse stop(Long meetingId) {
        StopClaim claim = stateService.claimStop(meetingId);
        if (!claim.remoteStopRequired()) {
            return toResponse(claim.session());
        }

        try {
            CaptureServiceSessionResponse response = captureClient.stop(
                meetingId,
                new StopCaptureRequest(meetingId, claim.session().getExternalSessionId())
            );
            return toResponse(stateService.markStopSucceeded(claim.session().getId(), response));
        } catch (RuntimeException exception) {
            throw exception;
        }
    }

    @Override
    public void finalizeForTerminalTransition(Long meetingId) {
        StopClaim claim = stateService.claimStopForTerminalTransition(meetingId);
        if (claim.session() == null || !claim.remoteStopRequired()) {
            return;
        }

        CaptureServiceSessionResponse response = captureClient.stop(
            meetingId,
            new StopCaptureRequest(meetingId, claim.session().getExternalSessionId())
        );
        stateService.markStopSucceeded(claim.session().getId(), response);
    }

    private MeetingCaptureResponse toResponse(MeetingCaptureSessionEntity entity) {
        return new MeetingCaptureResponse(
            entity.getId(),
            entity.getExternalSessionId(),
            entity.getStatus(),
            entity.getStartedAt(),
            entity.getCompletedAt(),
            entity.getManifestPath(),
            entity.getErrorMessage()
        );
    }

    private MeetingCaptureResponse notStartedResponse() {
        return new MeetingCaptureResponse(null, null, CaptureStatus.NOT_STARTED, null, null, null, null);
    }

}
