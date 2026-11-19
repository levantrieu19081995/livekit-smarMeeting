package vn.cusc.meetingservice.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import vn.cusc.meetingservice.dto.request.AddMeetingParticipantRequest;
import vn.cusc.meetingservice.dto.request.UpdateMeetingParticipantRequest;
import vn.cusc.meetingservice.dto.request.UpdateMeetingParticipantRoleRequest;
import vn.cusc.meetingservice.dto.response.MeetingParticipantResponse;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantConnectionEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.exception.MeetingNotFoundException;
import vn.cusc.meetingservice.exception.ParticipantNotFoundException;
import vn.cusc.meetingservice.mapper.MeetingParticipantMapper;
import vn.cusc.meetingservice.model.enums.ParticipantConnectionStatus;
import vn.cusc.meetingservice.model.enums.ParticipantJoinType;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.model.enums.MeetingCreationMode;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.repository.MeetingParticipantConnectionRepository;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.service.LiveKitService;
import vn.cusc.meetingservice.service.MeetingAccessPolicyService;
import vn.cusc.meetingservice.service.MeetingParticipantService;
import vn.cusc.meetingservice.service.MeetingSpeakingService;
import vn.cusc.meetingservice.service.UserAccountService;

@Service
public class MeetingParticipantServiceImpl implements MeetingParticipantService {

    private final MeetingRepository meetingRepository;
    private final MeetingParticipantRepository participantRepository;
    private final MeetingParticipantConnectionRepository connectionRepository;
    private final UserAccountService userAccountService;
    private final LiveKitService liveKitService;
    private final MeetingParticipantMapper participantMapper;
    private final MeetingAccessPolicyService meetingAccessPolicyService;
    private MeetingSpeakingService meetingSpeakingService;
    private vn.cusc.meetingservice.service.MeetingMediaCleanupService mediaCleanup;
    @Autowired public void setMediaCleanup(vn.cusc.meetingservice.service.MeetingMediaCleanupService cleanup) { mediaCleanup = cleanup; }

    @Autowired
    public void setMeetingSpeakingService(MeetingSpeakingService service) { this.meetingSpeakingService = service; }

    public MeetingParticipantServiceImpl(MeetingRepository meetingRepository,
                                         MeetingParticipantRepository participantRepository,
                                         MeetingParticipantConnectionRepository connectionRepository,
                                         UserAccountService userAccountService,
                                         LiveKitService liveKitService,
                                         MeetingParticipantMapper participantMapper,
                                         MeetingAccessPolicyService meetingAccessPolicyService) {
        this.meetingRepository = meetingRepository;
        this.participantRepository = participantRepository;
        this.connectionRepository = connectionRepository;
        this.userAccountService = userAccountService;
        this.liveKitService = liveKitService;
        this.participantMapper = participantMapper;
        this.meetingAccessPolicyService = meetingAccessPolicyService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MeetingParticipantResponse> getParticipants(Long meetingId) {
        MeetingEntity meeting = findMeeting(meetingId);
        meetingAccessPolicyService.requireCanViewMeeting(meeting);
        return participantRepository.findByMeetingId(meetingId).stream().map(participantMapper::toResponse).toList();
    }

    @Override
    @Transactional
    public MeetingParticipantResponse addParticipant(Long meetingId, AddMeetingParticipantRequest request) {
        MeetingEntity meeting = findMeetingForUpdate(meetingId);
        meetingAccessPolicyService.requireCanManageMeeting(meeting);
        requirePointActiveAccount(meeting);
        validatePointRole(meeting, null, request.role());
        MeetingParticipantEntity participant = new MeetingParticipantEntity();
        participant.setMeeting(meeting);
        participant.setParticipantKey(UUID.randomUUID().toString());
        participant.setRole(request.role());
        participant.setStatus(ParticipantStatus.INVITED);
        participant.setJoinType(ParticipantJoinType.AUTHENTICATED);
        if (request.userPublicId() != null && !request.userPublicId().isBlank()) {
            UserAccountEntity account = userAccountService.findByPublicIdOrThrow(request.userPublicId());
            if (participantRepository.findByMeetingIdAndUserAccountId(meetingId, account.getId()).isPresent()) {
                throw new BusinessValidationException("Participant da ton tai");
            }
            participant.setUserAccount(account);
            participant.setUserId("account-" + account.getPublicId());
            participant.setInvitedEmailNormalized(account.getNormalizedEmail());
            participant.setFullName(request.fullName() == null || request.fullName().isBlank() ? account.getFullName() : request.fullName().trim());
            participant.setEmail(account.getNormalizedEmail());
        } else {
            String normalizedEmail = userAccountService.normalizeEmail(request.invitedEmail());
            participant.setUserId("invite-" + UUID.randomUUID());
            participant.setInvitedEmailNormalized(normalizedEmail);
            participant.setFullName(request.fullName() == null || request.fullName().isBlank() ? normalizedEmail : request.fullName().trim());
            participant.setEmail(normalizedEmail);
        }
        return participantMapper.toResponse(participantRepository.save(participant));
    }

    @Override
    @Transactional
    public MeetingParticipantResponse updateParticipant(Long meetingId, String participantKey, UpdateMeetingParticipantRequest request) {
        MeetingEntity meeting = findMeetingForUpdate(meetingId);
        meetingAccessPolicyService.requireCanManageMeeting(meeting);
        requirePointActiveAccount(meeting);
        MeetingParticipantEntity participant = findParticipant(meetingId, participantKey);
        validatePointRole(meeting, participant.getId(), request.role());
        participant.setFullName(request.fullName());
        participant.setEmail(request.email());
        participant.setInvitedEmailNormalized(userAccountService.normalizeEmail(request.email()));
        participant.setRole(request.role());
        return participantMapper.toResponse(participantRepository.save(participant));
    }

    @Override
    @Transactional
    public MeetingParticipantResponse updateParticipantRole(Long meetingId, String participantKey, UpdateMeetingParticipantRoleRequest request) {
        MeetingEntity meeting = findMeetingForUpdate(meetingId);
        meetingAccessPolicyService.requireCanManageMeeting(meeting);
        requirePointActiveAccount(meeting);
        MeetingParticipantEntity participant = findParticipant(meetingId, participantKey);
        validatePointRole(meeting, participant.getId(), request.role());
        participant.setRole(request.role());
        return participantMapper.toResponse(participantRepository.save(participant));
    }

    @Override
    @Transactional
    public void deleteParticipant(Long meetingId, String participantKey) {
        MeetingEntity meeting = findMeetingForUpdate(meetingId);
        meetingAccessPolicyService.requireCanManageMeeting(meeting);
        requirePointActiveAccount(meeting);
        MeetingParticipantEntity participant = findParticipant(meetingId, participantKey);
        participant.setStatus(ParticipantStatus.REMOVED);
        participantRepository.save(participant);
        if (meetingSpeakingService != null) meetingSpeakingService.finalizeForParticipantRemoval(meetingId, participant.getId());
        LocalDateTime now = LocalDateTime.now();
        List<MeetingParticipantConnectionEntity> connections = connectionRepository.findByParticipantId(participant.getId());
        for (MeetingParticipantConnectionEntity connection : connections) {
            if (connection.getStatus() != ParticipantConnectionStatus.REMOVED) {
                connection.setStatus(ParticipantConnectionStatus.REMOVED);
            }
            connection.setLeftAt(now);
        }
        connectionRepository.saveAll(connections);
        String attendeeId = participant.getUserAccount() != null
            ? "account:" + participant.getUserAccount().getPublicId()
            : participant.getUserId() != null && participant.getUserId().startsWith("guest-")
                ? "guest:" + participant.getUserId().substring("guest-".length())
                : null;
        if (attendeeId != null) {
            if (mediaCleanup != null) mediaCleanup.enqueue(meetingId, "REMOVE_ATTENDEE", meeting.getRoomName(), attendeeId);
            else liveKitService.removeParticipantsByAttendeeId(participant.getMeeting().getRoomName(), attendeeId);
        }
    }

    private MeetingEntity findMeeting(Long meetingId) {
        return meetingRepository.findById(meetingId).orElseThrow(() -> new MeetingNotFoundException(meetingId));
    }

    private void validatePointRole(MeetingEntity meeting, Long excluding, MeetingRole role) {
        if (role != MeetingRole.CHAIRMAN && role != MeetingRole.SECRETARY) return;
        boolean duplicate = participantRepository.findByMeetingId(meeting.getId()).stream()
            .anyMatch(p -> p.getStatus() != ParticipantStatus.REMOVED && p.getRole() == role
                && !java.util.Objects.equals(p.getId(), excluding));
        if (duplicate) throw new BusinessValidationException("Chỉ được có một chủ tọa và một thư ký trong cuộc họp.");
    }
    private void requirePointActiveAccount(MeetingEntity meeting) {
        if (meeting.getCreationMode() == MeetingCreationMode.POINT && meetingSpeakingService != null)
            meetingSpeakingService.requireEnabledViewer(meeting);
    }

    private MeetingEntity findMeetingForUpdate(Long meetingId) {
        return meetingRepository.findByIdForUpdate(meetingId).orElseThrow(() -> new MeetingNotFoundException(meetingId));
    }

    private MeetingParticipantEntity findParticipant(Long meetingId, String participantKey) {
        return participantRepository.findByMeetingId(meetingId).stream()
            .filter(item -> participantKey.equals(item.getParticipantKey()))
            .findFirst()
            .orElseThrow(() -> new ParticipantNotFoundException(meetingId, participantKey));
    }
}
