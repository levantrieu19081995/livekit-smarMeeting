package vn.cusc.meetingservice.service.impl;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Root;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.Supplier;
import vn.cusc.meetingservice.config.LiveKitProperties;
import vn.cusc.meetingservice.dto.request.AddMeetingParticipantRequest;
import vn.cusc.meetingservice.dto.request.CreateMeetingRequest;
import vn.cusc.meetingservice.dto.request.JoinMeetingRequest;
import vn.cusc.meetingservice.dto.request.UpdateMeetingRequest;
import vn.cusc.meetingservice.dto.response.JoinMeetingResponse;
import vn.cusc.meetingservice.dto.response.LiveKitRoomResponse;
import vn.cusc.meetingservice.dto.response.MeetingDetailResponse;
import vn.cusc.meetingservice.dto.response.MeetingJoinInfoResponse;
import vn.cusc.meetingservice.dto.response.MeetingResponse;
import vn.cusc.meetingservice.dto.response.MeetingPointResponse;
import vn.cusc.meetingservice.dto.response.PageResponse;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantConnectionEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.entity.MeetingPointEntity;
import vn.cusc.meetingservice.entity.MeetingPointCreationReceiptEntity;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.exception.BusinessValidationException;
import vn.cusc.meetingservice.exception.LiveKitOperationException;
import vn.cusc.meetingservice.exception.MeetingAccessDeniedException;
import vn.cusc.meetingservice.exception.MeetingNotFoundException;
import vn.cusc.meetingservice.exception.MeetingSpeakingException;
import vn.cusc.meetingservice.mapper.MeetingMapper;
import vn.cusc.meetingservice.model.enums.AuthProvider;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.MeetingCreationMode;
import vn.cusc.meetingservice.model.enums.AccountStatus;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.model.enums.ParticipantConnectionStatus;
import vn.cusc.meetingservice.model.enums.ParticipantJoinType;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.repository.MeetingParticipantConnectionRepository;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.repository.MeetingPointCreationReceiptRepository;
import vn.cusc.meetingservice.repository.UserAccountRepository;
import vn.cusc.meetingservice.security.CurrentUserService;
import vn.cusc.meetingservice.security.MeetingPrincipal;
import vn.cusc.meetingservice.service.LiveKitService;
import vn.cusc.meetingservice.service.MeetingAccessPolicyService;
import vn.cusc.meetingservice.service.MeetingCaptureService;
import vn.cusc.meetingservice.service.MeetingPollTerminalService;
import vn.cusc.meetingservice.service.MeetingService;
import vn.cusc.meetingservice.service.MeetingSpeakingService;
import vn.cusc.meetingservice.service.UserAccountService;
import vn.cusc.meetingservice.util.MeetingChatTokenUtils;
import vn.cusc.meetingservice.util.MeetingCodeGenerator;
import vn.cusc.meetingservice.util.MeetingStatusResolver;
import vn.cusc.meetingservice.util.MeetingVisibility;
import vn.cusc.meetingservice.util.RoomNameGenerator;
import vn.cusc.meetingservice.util.MeetingPointCreationCodec;

@Service
public class MeetingServiceImpl implements MeetingService {

    private final MeetingRepository meetingRepository;
    private final MeetingParticipantRepository meetingParticipantRepository;
    private final MeetingParticipantConnectionRepository connectionRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserAccountService userAccountService;
    private final CurrentUserService currentUserService;
    private final MeetingCodeGenerator meetingCodeGenerator;
    private final RoomNameGenerator roomNameGenerator;
    private final LiveKitService liveKitService;
    private final MeetingMapper meetingMapper;
    private final MeetingStatusResolver statusResolver;
    private final LiveKitProperties liveKitProperties;
    private final MeetingAccessPolicyService meetingAccessPolicyService;
    private final MeetingCaptureService meetingCaptureService;
    private final MeetingPollTerminalService meetingPollTerminalService;
    private MeetingSpeakingService meetingSpeakingService;
    private MeetingPointCreationReceiptRepository pointCreationReceipts;
    private TransactionTemplate transactions;
    private vn.cusc.meetingservice.service.MeetingMediaCleanupService mediaCleanup;
    private vn.cusc.meetingservice.service.MeetingGuestIdentityService guestIdentities;
    private vn.cusc.meetingservice.service.MeetingFileCleanupService fileCleanup;

    @Autowired public void setTransactionManager(PlatformTransactionManager manager) { transactions = new TransactionTemplate(manager); }
    @Autowired public void setMediaCleanup(vn.cusc.meetingservice.service.MeetingMediaCleanupService cleanup) { mediaCleanup = cleanup; }
    @Autowired public void setGuestIdentities(vn.cusc.meetingservice.service.MeetingGuestIdentityService guests) { guestIdentities = guests; }
    @Autowired public void setFileCleanup(vn.cusc.meetingservice.service.MeetingFileCleanupService cleanup) { fileCleanup = cleanup; }

    @Autowired
    public void setMeetingSpeakingService(MeetingSpeakingService service) { this.meetingSpeakingService = service; }
    @Autowired
    public void setPointCreationReceipts(MeetingPointCreationReceiptRepository repository) { this.pointCreationReceipts = repository; }

    public MeetingServiceImpl(MeetingRepository meetingRepository,
                              MeetingParticipantRepository meetingParticipantRepository,
                              MeetingParticipantConnectionRepository connectionRepository,
                              UserAccountRepository userAccountRepository,
                              UserAccountService userAccountService,
                              CurrentUserService currentUserService,
                              MeetingCodeGenerator meetingCodeGenerator,
                              RoomNameGenerator roomNameGenerator,
                              LiveKitService liveKitService,
                              MeetingMapper meetingMapper,
                              MeetingStatusResolver statusResolver,
                              LiveKitProperties liveKitProperties,
                              MeetingAccessPolicyService meetingAccessPolicyService,
                              MeetingCaptureService meetingCaptureService,
                              MeetingPollTerminalService meetingPollTerminalService) {
        this.meetingRepository = meetingRepository;
        this.meetingParticipantRepository = meetingParticipantRepository;
        this.connectionRepository = connectionRepository;
        this.userAccountRepository = userAccountRepository;
        this.userAccountService = userAccountService;
        this.currentUserService = currentUserService;
        this.meetingCodeGenerator = meetingCodeGenerator;
        this.roomNameGenerator = roomNameGenerator;
        this.liveKitService = liveKitService;
        this.meetingMapper = meetingMapper;
        this.statusResolver = statusResolver;
        this.liveKitProperties = liveKitProperties;
        this.meetingAccessPolicyService = meetingAccessPolicyService;
        this.meetingCaptureService = meetingCaptureService;
        this.meetingPollTerminalService = meetingPollTerminalService;
    }

    @Override
    @Transactional
    public MeetingResponse createMeeting(CreateMeetingRequest request) {
        validateCreateMeetingRequest(request);
        MeetingPrincipal principal = currentUserService.getCurrentPrincipal().filter(MeetingPrincipal::isEnabled)
            .orElseThrow(() -> new MeetingAccessDeniedException("AUTHENTICATION_REQUIRED"));
        MeetingCreationMode mode = request.creationMode() == null ? MeetingCreationMode.SCHEDULED : request.creationMode();
        // Lock the actual creator before external room creation: concurrent retries share a single receipt/room.
        boolean retryable = request.clientRequestId() != null;
        UserAccountEntity creator = (retryable
            ? userAccountRepository.findByIdForUpdate(principal.accountId()) : userAccountRepository.findById(principal.accountId()))
            .filter(account -> account.getAccountStatus() == AccountStatus.ACTIVE)
            .orElseThrow(() -> new MeetingAccessDeniedException("ACCOUNT_NOT_FOUND"));
        String pointInputHash = null;
        if (retryable) {
            pointInputHash = MeetingPointCreationCodec.fingerprint(request);
            var existing = pointCreationReceipts.findByCreatedByAccountIdAndRequestId(creator.getId(), request.clientRequestId()).orElse(null);
            if (existing != null) {
                if (!pointInputHash.equals(existing.getInputHash())) throw new MeetingSpeakingException(HttpStatus.CONFLICT,
                    "MEETING_CREATION_RETRY_CONFLICT", "Mã tạo cuộc họp đã được dùng cho nội dung khác.");
                if (existing.getMeeting() == null) throw new MeetingSpeakingException(HttpStatus.GONE,
                    "MEETING_CREATION_RECEIPT_DELETED", "Cuộc họp của lần tạo trước đã bị xóa. Vui lòng bắt đầu lần tạo mới.");
                return MeetingPointCreationCodec.decode(existing.getReceiptJson());
            }
        }
        LocalDateTime createdTime = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String meetingCode = generateUniqueMeetingCode();
        String roomName = retryable
            ? MeetingPointCreationCodec.roomName(creator.getPublicId(), request.clientRequestId()) : generateUniqueRoomName();
        boolean rollbackCompensationRegistered = mediaCleanup != null
            && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive();
        if (rollbackCompensationRegistered) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) mediaCleanup.compensateCreation(creator.getId(), roomName);
                    }
                });
        }
        try {
            LiveKitRoomResponse roomResponse = liveKitService.createRoom(roomName);
            MeetingEntity meeting = new MeetingEntity();
            meeting.setMeetingCode(meetingCode);
            meeting.setTitle(request.title() == null || request.title().isBlank()
                ? "Cuộc họp ngày " + createdTime.toLocalDate() : request.title().trim());
            meeting.setDescription(request.description());
            meeting.setRoomName(roomName);
            meeting.setRoomSid(roomResponse.roomSid());
            meeting.setCreationMode(mode);
            meeting.setStartTime(mode == MeetingCreationMode.INSTANT ? createdTime
                : mode == MeetingCreationMode.LATER ? null : request.startTime());
            meeting.setEndTime(scheduled(mode) ? request.endTime() : null);
            meeting.setAccessType(request.accessType());
            meeting.setStatus(mode == MeetingCreationMode.INSTANT ? MeetingStatus.IN_PROGRESS : MeetingStatus.UPCOMING);
            meeting.setCreatedByAccount(creator);
            attachParticipants(meeting, mode == MeetingCreationMode.POINT
                ? MeetingPointCreationCodec.participants(request.participants()) : request.participants());
            if (mode == MeetingCreationMode.POINT) attachPoints(meeting, validatedPointNames(request.pointNames()));
            invitePrivateMeetingCreator(meeting);
            MeetingEntity saved = meetingRepository.save(meeting);
            MeetingStatus effectiveStatus = statusResolver.resolve(saved);
            var response = meetingMapper.toResponse(saved, effectiveStatus).withCanManage(canManage(saved, principal));
            if (retryable) {
                var receipt = new MeetingPointCreationReceiptEntity(); receipt.setCreatedByAccount(creator);
                receipt.setMeeting(saved); receipt.setRequestId(request.clientRequestId()); receipt.setInputHash(pointInputHash);
                receipt.setReceiptJson(MeetingPointCreationCodec.encode(response)); pointCreationReceipts.saveAndFlush(receipt);
            }
            return response;
        } catch (RuntimeException exception) {
            if (!rollbackCompensationRegistered) {
                if (mediaCleanup != null) mediaCleanup.compensateCreation(creator.getId(), roomName);
                else try { liveKitService.deleteRoom(roomName); }
                    catch (LiveKitOperationException cleanupFailure) { exception.addSuppressed(cleanupFailure); }
            }
            throw exception;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<MeetingResponse> getMeetings(String search, MeetingStatus status, MeetingAccessType accessType,
                                                     String fromDate, String toDate, Pageable pageable) {
        MeetingPrincipal principal = currentUserService.getCurrentPrincipal()
            .filter(MeetingPrincipal::isEnabled)
            .orElseThrow(() -> new MeetingAccessDeniedException("AUTHENTICATION_REQUIRED"));
        LocalDateTime now = LocalDateTime.now();
        Specification<MeetingEntity> specification = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (search != null && !search.isBlank()) {
                String likeValue = "%" + search.toLowerCase() + "%";
                predicates.add(criteriaBuilder.or(
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("title")), likeValue),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("meetingCode")), likeValue),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("roomName")), likeValue)
                ));
            }
            if (status != null) {
                predicates.add(effectiveStatusPredicate(root, criteriaBuilder, status, now));
            }
            if (accessType != null) {
                predicates.add(criteriaBuilder.equal(root.get("accessType"), accessType));
            }
            if (fromDate != null && !fromDate.isBlank()) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("startTime"), LocalDate.parse(fromDate).atStartOfDay()));
            }
            if (toDate != null && !toDate.isBlank()) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(
                    criteriaBuilder.<LocalDateTime>coalesce().value(root.get("endTime")).value(root.get("startTime")),
                    LocalDate.parse(toDate).atTime(23, 59, 59)));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };

        Page<MeetingResponse> page = meetingRepository.findAll(
            specification.and(MeetingVisibility.visibleTo(principal)), pageable)
            .map(entity -> meetingMapper.toResponse(entity, statusResolver.resolve(entity, now))
                .withCanManage(canManage(entity, principal)));

        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
            page.getTotalPages(), page.isFirst(), page.isLast());
    }

    private Predicate effectiveStatusPredicate(Root<MeetingEntity> root,
                                               CriteriaBuilder criteriaBuilder,
                                               MeetingStatus requestedStatus,
                                               LocalDateTime now) {
        Predicate cancelled = criteriaBuilder.equal(root.get("status"), MeetingStatus.CANCELLED);
        Predicate completed = criteriaBuilder.equal(root.get("status"), MeetingStatus.COMPLETED);
        Predicate nonTerminal = root.get("status").in(MeetingStatus.UPCOMING, MeetingStatus.IN_PROGRESS);
        Predicate explicitlyStartedOpenMeeting = criteriaBuilder.and(
            criteriaBuilder.equal(root.get("status"), MeetingStatus.IN_PROGRESS),
            root.get("creationMode").in(MeetingCreationMode.INSTANT, MeetingCreationMode.LATER),
            criteriaBuilder.isNotNull(root.get("startTime")), criteriaBuilder.isNull(root.get("endTime")));

        return switch (requestedStatus) {
            case CANCELLED -> cancelled;
            case COMPLETED -> criteriaBuilder.or(
                completed,
                criteriaBuilder.and(nonTerminal, criteriaBuilder.lessThan(root.get("endTime"), now))
            );
            case UPCOMING -> criteriaBuilder.and(
                nonTerminal,
                criteriaBuilder.not(explicitlyStartedOpenMeeting),
                criteriaBuilder.or(criteriaBuilder.isNull(root.get("startTime")), criteriaBuilder.greaterThan(root.get("startTime"), now))
            );
            case IN_PROGRESS -> criteriaBuilder.and(
                nonTerminal,
                criteriaBuilder.or(explicitlyStartedOpenMeeting, criteriaBuilder.lessThanOrEqualTo(root.get("startTime"), now)),
                criteriaBuilder.or(criteriaBuilder.isNull(root.get("endTime")), criteriaBuilder.greaterThanOrEqualTo(root.get("endTime"), now))
            );
        };
    }

    @Override
    @Transactional(readOnly = true)
    public MeetingDetailResponse getMeeting(Long meetingId) {
        MeetingEntity meeting = findMeeting(meetingId);
        meetingAccessPolicyService.requireCanViewMeeting(meeting);
        meeting.setStatus(statusResolver.resolve(meeting));
        return meetingMapper.toDetailResponse(meeting).withCanManage(
            canManage(meeting, currentUserService.getCurrentPrincipal().orElse(null)))
            .withPointsEditable(meeting.getCreationMode() == MeetingCreationMode.POINT
                && !connectionRepository.existsByMeetingId(meetingId));
    }

    @Override
    @Transactional(readOnly = true)
    public MeetingJoinInfoResponse getJoinInfo(Long meetingId) {
        MeetingEntity meeting = findMeeting(meetingId);
        MeetingStatus effectiveStatus = statusResolver.resolve(meeting);
        return new MeetingJoinInfoResponse(
            meeting.getId(),
            meeting.getAccessType() == MeetingAccessType.PUBLIC ? meeting.getTitle() : null,
            meeting.getStartTime(),
            meeting.getEndTime(),
            effectiveStatus,
            meeting.getAccessType(),
            meeting.getCreationMode(),
            meeting.getCreationMode() == MeetingCreationMode.POINT
                ? meeting.getPoints().stream().map(p -> new MeetingPointResponse(p.getId(), p.getName())).toList() : List.of()
        );
    }

    @Override
    @Transactional
    public MeetingResponse updateMeeting(Long meetingId, UpdateMeetingRequest request) {
        MeetingEntity meeting = findMeetingForUpdate(meetingId);
        meetingAccessPolicyService.requireCanManageMeeting(meeting);
        requirePointActiveAccount(meeting);
        if (scheduled(meeting.getCreationMode())) {
            validateMeetingSchedule(request.startTime(), request.endTime());
        }
        if (meeting.getCreationMode() != MeetingCreationMode.SCHEDULED && request.accessType() != MeetingAccessType.PUBLIC) {
            throw new BusinessValidationException("Cuộc họp tức thì/sử dụng sau là cuộc họp tự do.");
        }
        if (request.pointNames() != null) {
            if (meeting.getCreationMode() != MeetingCreationMode.POINT)
                throw new BusinessValidationException("Danh sách điểm cầu chỉ dùng cho cuộc họp điểm cầu.");
            var names = validatedPointNames(request.pointNames());
            var currentNames = meeting.getPoints().stream().map(MeetingPointEntity::getName).toList();
            if (!names.equals(currentNames)) {
                if (connectionRepository.existsByMeetingId(meetingId))
                    throw new MeetingSpeakingException(HttpStatus.CONFLICT, "MEETING_POINTS_LOCKED", "Đã có người vào họp. Không thể thay đổi danh sách điểm cầu.");
                meeting.getPoints().clear();
                // Delete old unique names/orders before inserting the new ordered list.
                meetingRepository.saveAndFlush(meeting);
                attachPoints(meeting, names);
            }
        }
        if (request.participants() != null) {
            applyPointRoster(meeting, request.participants(), request.rosterBaseline());
        }
        if ((meeting.getStatus() == MeetingStatus.COMPLETED || meeting.getStatus() == MeetingStatus.CANCELLED)
            && request.status() != null
            && request.status() != meeting.getStatus()) {
            throw new BusinessValidationException("Khong the thay doi trang thai cuoc hop da ket thuc bang API cap nhat");
        }
        if (request.status() == MeetingStatus.COMPLETED && meeting.getStatus() != MeetingStatus.COMPLETED) {
            throw new BusinessValidationException("Su dung API ket thuc cuoc hop de chuyen sang COMPLETED");
        }
        meeting.setTitle(request.title());
        meeting.setDescription(request.description());
        if (scheduled(meeting.getCreationMode())) {
            meeting.setStartTime(request.startTime());
            meeting.setEndTime(request.endTime());
        }
        boolean becomingPrivate = meeting.getAccessType() == MeetingAccessType.PUBLIC
            && request.accessType() == MeetingAccessType.PRIVATE;
        meeting.setAccessType(request.accessType());
        if (becomingPrivate) invitePrivateMeetingCreator(meeting);
        if (request.status() != null) {
            meeting.setStatus(request.status());
        }
        if (meeting.getStatus() == MeetingStatus.CANCELLED) {
            meetingCaptureService.finalizeForTerminalTransition(meetingId);
            meetingPollTerminalService.closeForTerminalTransition(meetingId);
            if (meetingSpeakingService != null) meetingSpeakingService.finalizeForTerminalTransition(meetingId);
            closeMedia(meeting);
        }
        return meetingMapper.toResponse(meetingRepository.save(meeting))
            .withCanManage(canManage(meeting, currentUserService.getCurrentPrincipal().orElse(null)));
    }

    @Override
    @Transactional
    public void deleteMeeting(Long meetingId) {
        MeetingEntity meeting = findMeetingForUpdate(meetingId);
        meetingAccessPolicyService.requireCanManageMeeting(meeting);
        requirePointActiveAccount(meeting);
        meetingCaptureService.finalizeForTerminalTransition(meetingId);
        meetingPollTerminalService.closeForTerminalTransition(meetingId);
        if (meetingSpeakingService != null) meetingSpeakingService.finalizeForTerminalTransition(meetingId);
        if (fileCleanup != null) fileCleanup.enqueueForMeetingDeletion(meetingId);
        closeMedia(meeting);
        meetingRepository.delete(meeting);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public JoinMeetingResponse joinMeeting(Long meetingId, JoinMeetingRequest request) {
        // Validate and provision without holding the shared meeting write lock.
        String roomName = inTransaction(() -> {
            MeetingEntity snapshot = transactions == null ? findMeetingForUpdate(meetingId) : findMeeting(meetingId);
            requireJoinable(snapshot);
            if (snapshot.getAccessType() == MeetingAccessType.PRIVATE) {
                MeetingPrincipal principal = currentUserService.getCurrentPrincipal()
                    .orElseThrow(() -> new MeetingAccessDeniedException("AUTHENTICATION_REQUIRED"));
                meetingAccessPolicyService.requireCanViewMeeting(snapshot);
            }
            validateJoinSelection(snapshot, request);
            validateJoinIdentity(snapshot, request);
            return snapshot.getRoomName();
        });
        LiveKitRoomResponse room = liveKitService.ensureRoomExists(roomName);
        try {
            return inTransaction(() -> joinInTransaction(meetingId, request, room));
        } catch (RuntimeException failure) {
            inTransaction(() -> {
                // Serialize the decision and durable intent with an explicit admin reopen.
                boolean terminal = meetingRepository.findByIdForUpdate(meetingId)
                    .map(m -> { var status = statusResolver.resolve(m); return status == MeetingStatus.COMPLETED || status == MeetingStatus.CANCELLED; })
                    .orElse(true);
                if (terminal) {
                    if (mediaCleanup != null) mediaCleanup.enqueue(meetingId, "DELETE_ROOM", roomName, null);
                    else liveKitService.deleteRoom(roomName);
                }
                return null;
            });
            throw failure;
        }
    }

    private JoinMeetingResponse joinInTransaction(Long meetingId, JoinMeetingRequest request, LiveKitRoomResponse room) {
        MeetingEntity meeting = findMeetingForUpdate(meetingId);
        MeetingStatus effectiveStatus = statusResolver.resolve(meeting);
        if (effectiveStatus == MeetingStatus.COMPLETED || effectiveStatus == MeetingStatus.CANCELLED) {
            throw new BusinessValidationException("Cuộc họp đã kết thúc hoặc đã bị hủy");
        }
        meeting.setStatus(effectiveStatus);
        MeetingPointEntity selectedPoint = null;
        if (meeting.getCreationMode() == MeetingCreationMode.POINT) {
            if (request == null || request.pointId() == null)
                throw new MeetingSpeakingException(HttpStatus.BAD_REQUEST, "MEETING_POINT_REQUIRED", "Vui lòng chọn điểm cầu trước khi vào họp.");
            if (request.displayName() == null || request.displayName().trim().isEmpty() || request.displayName().length() > 255
                || request.displayName().codePoints().anyMatch(Character::isISOControl))
                throw new BusinessValidationException("Tên tham gia điểm cầu cần từ 1 đến 255 ký tự, không có ký tự điều khiển.");
            selectedPoint = meeting.getPoints().stream().filter(point -> point.getId().equals(request.pointId())).findFirst()
                .orElseThrow(() -> new MeetingSpeakingException(HttpStatus.BAD_REQUEST, "MEETING_POINT_INVALID", "Điểm cầu không thuộc cuộc họp hoặc danh sách đã thay đổi."));
        } else if (request != null && request.pointId() != null) {
            throw new MeetingSpeakingException(HttpStatus.BAD_REQUEST, "MEETING_POINT_INVALID", "Cuộc họp này không sử dụng điểm cầu.");
        }
        MeetingPrincipal principal = currentUserService.getCurrentPrincipal().orElse(null);

        MeetingParticipantEntity participant;
        String attendeeId;
        String displayName;
        AuthProvider provider;

        if (principal != null) {
            UserAccountEntity account = userAccountRepository.findById(principal.accountId())
                .orElseThrow(() -> new BusinessValidationException("Khong tim thay account dang nhap"));
            if (!principal.isEnabled() || account.getAccountStatus() != AccountStatus.ACTIVE
                || principal.securityVersion() != account.getSecurityVersion())
                throw new MeetingAccessDeniedException("ACCOUNT_DISABLED");
            participant = resolveAuthenticatedParticipant(meeting, account);
            attendeeId = "account:" + account.getPublicId();
            displayName = account.getFullName();
            provider = principal.provider();
        } else {
            if (meeting.getAccessType() == MeetingAccessType.PRIVATE) {
                throw new MeetingAccessDeniedException("AUTHENTICATION_REQUIRED");
            }
            participant = guestIdentities != null ? guestIdentities.resolve(meeting, request) : meeting.getCreationMode() == MeetingCreationMode.POINT
                ? resolvePointGuestParticipant(meeting, request) : resolveGuestParticipant(meeting, request);
            attendeeId = "guest:" + participant.getUserId().substring("guest-".length());
            displayName = participant.getFullName();
            provider = AuthProvider.GUEST;
        }

        if (participant.getStatus() == ParticipantStatus.REMOVED) {
            throw new MeetingAccessDeniedException("PARTICIPANT_REMOVED");
        }
        if (meeting.getCreationMode() == MeetingCreationMode.LATER && meeting.getStartTime() == null) {
            meeting.setStartTime(LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            meeting.setStatus(MeetingStatus.IN_PROGRESS);
        }

        String connectionId = UUID.randomUUID().toString();
        String liveKitIdentity = attendeeId + ":" + connectionId;
        String chatWriteToken = MeetingChatTokenUtils.generateToken();
        String chatTokenHash = MeetingChatTokenUtils.sha256(chatWriteToken);
        if (meeting.getRoomSid() == null || !meeting.getRoomSid().equals(room.roomSid())) {
            meeting.setRoomSid(room.roomSid());
        }

        String token = liveKitService.generateJoinToken(meeting.getRoomName(), liveKitIdentity, displayName, Map.of(
            "attendeeId", attendeeId,
            "connectionId", connectionId,
            "provider", provider.name(),
            "userId", principal != null ? principal.publicId() : "",
            "displayName", displayName,
            "meetingRole", participant.getRole().name()
        ), true, true);

        meetingParticipantRepository.save(participant);

        MeetingParticipantConnectionEntity connection = new MeetingParticipantConnectionEntity();
        connection.setMeeting(meeting);
        connection.setParticipant(participant);
        connection.setAttendeeId(attendeeId);
        connection.setConnectionId(connectionId);
        connection.setLiveKitIdentity(liveKitIdentity);
        connection.setJoinedAt(null);
        connection.setStatus(ParticipantConnectionStatus.PENDING);
        connection.setChatTokenHash(chatTokenHash);
        connection.setPoint(selectedPoint);
        if (participant.getUserAccount() != null) connection.setAccountSecurityVersion(participant.getUserAccount().getSecurityVersion());
        connectionRepository.save(connection);

        boolean canEnd = meetingAccessPolicyService.canEndMeeting(principal, meeting, participant);
        boolean canManageParticipants = meetingAccessPolicyService.canManageMeeting(principal, meeting, participant);
        return new JoinMeetingResponse(
            meeting.getRoomName(),
            meeting.getRoomSid(),
            liveKitProperties.getUrl(),
            token,
            new JoinMeetingResponse.JoinParticipantResponse(attendeeId, connectionId, liveKitIdentity, displayName, participant.getRole().name(), chatWriteToken,
                selectedPoint == null ? null : selectedPoint.getId(), selectedPoint == null ? null : selectedPoint.getName()),
            new JoinMeetingResponse.JoinPermissionsResponse(canEnd, canManageParticipants)
        );
    }

    @Override
    @Transactional
    public void endMeeting(Long meetingId) {
        MeetingEntity meeting = findMeetingForUpdate(meetingId);
        meetingAccessPolicyService.requireCanEndMeeting(meeting);
        requirePointActiveAccount(meeting);
        meetingCaptureService.finalizeForTerminalTransition(meetingId);
        meetingPollTerminalService.closeForTerminalTransition(meetingId);
        if (meetingSpeakingService != null) meetingSpeakingService.finalizeForTerminalTransition(meetingId);
        if (meeting.getStatus() == MeetingStatus.CANCELLED) {
            closeMedia(meeting);
            return;
        }
        if (meeting.getStatus() != MeetingStatus.COMPLETED) {
            meeting.setStatus(MeetingStatus.COMPLETED);
            if (!scheduled(meeting.getCreationMode())) {
                LocalDateTime endedTime = LocalDateTime.now();
                if (meeting.getStartTime() == null) meeting.setStartTime(endedTime);
                meeting.setEndTime(endedTime);
            }
            meetingRepository.save(meeting);
        }
        closeMedia(meeting);
    }

    private void closeMedia(MeetingEntity meeting) {
        if (mediaCleanup != null) mediaCleanup.enqueue(meeting.getId(), "DELETE_ROOM", meeting.getRoomName(), null);
        else liveKitService.deleteRoom(meeting.getRoomName());
    }
    private <T> T inTransaction(Supplier<T> work) {
        return transactions == null ? work.get() : transactions.execute(status -> work.get());
    }
    private void requireJoinable(MeetingEntity meeting) {
        var status = statusResolver.resolve(meeting);
        if (status == MeetingStatus.COMPLETED || status == MeetingStatus.CANCELLED)
            throw new BusinessValidationException("Cuộc họp đã kết thúc hoặc đã bị hủy");
    }
    private void validateJoinSelection(MeetingEntity meeting, JoinMeetingRequest request) {
        if (request == null || request.displayName() == null || request.displayName().isBlank()
            || request.displayName().length() > 255 || request.displayName().codePoints().anyMatch(Character::isISOControl))
            throw new BusinessValidationException("Tên tham gia cần từ 1 đến 255 ký tự, không có ký tự điều khiển.");
        if (meeting.getCreationMode() == MeetingCreationMode.POINT) {
            if (request.pointId() == null) throw new MeetingSpeakingException(HttpStatus.BAD_REQUEST, "MEETING_POINT_REQUIRED", "Vui lòng chọn điểm cầu trước khi vào họp.");
            if (meeting.getPoints().stream().noneMatch(p -> p.getId().equals(request.pointId())))
                throw new MeetingSpeakingException(HttpStatus.BAD_REQUEST, "MEETING_POINT_INVALID", "Điểm cầu không thuộc cuộc họp hoặc danh sách đã thay đổi.");
        } else if (request.pointId() != null)
            throw new MeetingSpeakingException(HttpStatus.BAD_REQUEST, "MEETING_POINT_INVALID", "Cuộc họp này không sử dụng điểm cầu.");
    }

    private void validateJoinIdentity(MeetingEntity meeting, JoinMeetingRequest request) {
        MeetingPrincipal principal = currentUserService.getCurrentPrincipal().orElse(null);
        if (principal != null) {
            UserAccountEntity account = userAccountRepository.findById(principal.accountId())
                .orElseThrow(() -> new MeetingAccessDeniedException("ACCOUNT_DISABLED"));
            if (!principal.isEnabled() || account.getAccountStatus() != AccountStatus.ACTIVE
                || principal.securityVersion() != account.getSecurityVersion())
                throw new MeetingAccessDeniedException("ACCOUNT_DISABLED");
            var participant = meetingParticipantRepository.findByMeetingIdAndUserAccountId(meeting.getId(), account.getId())
                .orElseGet(() -> meeting.getParticipants().stream()
                    .filter(p -> account.getNormalizedEmail().equals(p.getInvitedEmailNormalized())).findFirst().orElse(null));
            if (participant != null && participant.getStatus() == ParticipantStatus.REMOVED)
                throw new MeetingAccessDeniedException("PARTICIPANT_REMOVED");
            if (meeting.getAccessType() == MeetingAccessType.PRIVATE && participant == null)
                throw new MeetingAccessDeniedException("MEETING_INVITATION_REQUIRED");
        } else if (guestIdentities != null) {
            guestIdentities.validateResume(meeting, request);
        } else if (request.guestId() != null) {
            // Compatibility for direct, non-Spring unit construction only. Runtime uses proven ownership above.
            meetingParticipantRepository.findByMeetingIdAndUserId(meeting.getId(), request.guestId())
                .filter(p -> p.getStatus() == ParticipantStatus.REMOVED)
                .ifPresent(p -> { throw new MeetingAccessDeniedException("PARTICIPANT_REMOVED"); });
        }
    }

    private MeetingParticipantEntity resolveAuthenticatedParticipant(MeetingEntity meeting, UserAccountEntity account) {
        MeetingParticipantEntity participant = meetingParticipantRepository.findByMeetingIdAndUserAccountId(meeting.getId(), account.getId()).orElse(null);
        if (participant == null) {
            participant = meeting.getParticipants().stream()
                .filter(item -> account.getNormalizedEmail().equals(item.getInvitedEmailNormalized()))
                .findFirst()
                .orElse(null);
            if (participant != null) {
                participant.setUserAccount(account);
                participant.setUserId("account-" + account.getPublicId());
            }
        }
        if (meeting.getAccessType() == MeetingAccessType.PRIVATE && participant == null) {
            throw new MeetingAccessDeniedException("MEETING_INVITATION_REQUIRED");
        }
        if (participant == null) {
            participant = new MeetingParticipantEntity();
            participant.setMeeting(meeting);
            participant.setParticipantKey(UUID.randomUUID().toString());
            participant.setUserAccount(account);
            participant.setUserId("account-" + account.getPublicId());
            participant.setInvitedEmailNormalized(account.getNormalizedEmail());
            participant.setFullName(account.getFullName());
            participant.setEmail(account.getNormalizedEmail());
            participant.setRole(MeetingRole.GUEST);
            participant.setStatus(ParticipantStatus.INVITED);
            participant.setJoinType(ParticipantJoinType.AUTHENTICATED);
            participant = meetingParticipantRepository.save(participant);
        }
        return participant;
    }

    private MeetingParticipantEntity resolveGuestParticipant(MeetingEntity meeting, JoinMeetingRequest request) {
        String guestId = request.guestId();
        if (guestId == null || guestId.isBlank()) {
            guestId = "guest-" + UUID.randomUUID();
        }
        if (!guestId.startsWith("guest-")) {
            throw new BusinessValidationException("Guest identity khong hop le");
        }
        MeetingParticipantEntity participant = meetingParticipantRepository.findByMeetingIdAndUserId(meeting.getId(), guestId).orElse(null);
        if (participant == null) {
            participant = new MeetingParticipantEntity();
            participant.setMeeting(meeting);
            participant.setParticipantKey(UUID.randomUUID().toString());
            participant.setUserId(guestId);
            participant.setInvitedEmailNormalized(null);
            participant.setFullName(request.displayName().trim());
            participant.setEmail(guestId + "@guest.local");
            participant.setRole(MeetingRole.GUEST);
            participant.setStatus(ParticipantStatus.INVITED);
            participant.setJoinType(ParticipantJoinType.GUEST);
            participant = meetingParticipantRepository.save(participant);
        } else {
            participant.setFullName(request.displayName().trim());
        }
        return participant;
    }

    private MeetingParticipantEntity resolvePointGuestParticipant(MeetingEntity meeting, JoinMeetingRequest request) {
        String resume = request.guestResumeToken();
        if (resume == null) {
            // Public guest identifiers cannot authorize resuming someone else's actor.
            return resolveGuestParticipant(meeting,
                new JoinMeetingRequest(request.participantType(), null, request.displayName(), request.pointId()));
        }
        if (resume.isBlank() || resume.length() > 255) throw invalidPointResume();
        var previous = connectionRepository.findByMeetingIdAndChatTokenHash(meeting.getId(), MeetingChatTokenUtils.sha256(resume))
            .orElseThrow(this::invalidPointResume);
        var participant = previous.getParticipant();
        if (participant == null || participant.getUserAccount() != null || participant.getStatus() == ParticipantStatus.REMOVED
            || !meeting.getId().equals(participant.getMeeting().getId()) || !participant.getUserId().equals(request.guestId())
            || previous.getStatus() == ParticipantConnectionStatus.REMOVED || previous.getPoint() == null) throw invalidPointResume();
        participant.setFullName(request.displayName().trim());
        return participant;
    }

    private MeetingSpeakingException invalidPointResume() {
        return new MeetingSpeakingException(HttpStatus.FORBIDDEN, "SPEAKING_INVALID_RESUME_TOKEN", "Không thể khôi phục phiên khách. Vui lòng tham gia bằng phiên mới.");
    }

    private MeetingEntity findMeeting(Long meetingId) {
        return meetingRepository.findById(meetingId).orElseThrow(() -> new MeetingNotFoundException(meetingId));
    }

    private MeetingEntity findMeetingForUpdate(Long meetingId) {
        return meetingRepository.findByIdForUpdate(meetingId).orElseThrow(() -> new MeetingNotFoundException(meetingId));
    }

    private void invitePrivateMeetingCreator(MeetingEntity meeting) {
        UserAccountEntity creator = meeting.getCreatedByAccount();
        if (meeting.getAccessType() != MeetingAccessType.PRIVATE || creator == null) return;
        boolean invited = meeting.getParticipants().stream().anyMatch(participant ->
            participant.getUserAccount() != null
                ? creator.getId().equals(participant.getUserAccount().getId())
                : creator.getNormalizedEmail().equals(participant.getInvitedEmailNormalized()));
        if (invited) return;
        MeetingParticipantEntity participant = new MeetingParticipantEntity();
        participant.setMeeting(meeting);
        participant.setParticipantKey(UUID.randomUUID().toString());
        participant.setUserAccount(creator);
        participant.setUserId("account-" + creator.getPublicId());
        participant.setInvitedEmailNormalized(creator.getNormalizedEmail());
        participant.setFullName(creator.getFullName());
        participant.setEmail(creator.getNormalizedEmail());
        participant.setRole(MeetingRole.MEMBER);
        participant.setStatus(ParticipantStatus.INVITED);
        participant.setJoinType(ParticipantJoinType.AUTHENTICATED);
        meeting.getParticipants().add(participant);
    }

    private void attachParticipants(MeetingEntity meeting, List<AddMeetingParticipantRequest> participants) {
        if (participants == null) {
            return;
        }
        for (AddMeetingParticipantRequest request : participants) {
            MeetingParticipantEntity participant = new MeetingParticipantEntity();
            participant.setMeeting(meeting);
            participant.setParticipantKey(UUID.randomUUID().toString());
            participant.setFullName(request.fullName() == null || request.fullName().isBlank() ? request.invitedEmail() : request.fullName().trim());
            participant.setRole(request.role());
            participant.setStatus(ParticipantStatus.INVITED);
            participant.setJoinType(ParticipantJoinType.AUTHENTICATED);
            if (request.userPublicId() != null && !request.userPublicId().isBlank()) {
                UserAccountEntity account = userAccountService.findByPublicIdOrThrow(request.userPublicId());
                participant.setUserAccount(account);
                participant.setUserId("account-" + account.getPublicId());
                participant.setInvitedEmailNormalized(account.getNormalizedEmail());
                participant.setEmail(account.getNormalizedEmail());
                if (request.fullName() == null || request.fullName().isBlank()) {
                    participant.setFullName(account.getFullName());
                }
            } else {
                String normalizedEmail = userAccountService.normalizeEmail(request.invitedEmail());
                participant.setInvitedEmailNormalized(normalizedEmail);
                participant.setEmail(normalizedEmail);
                participant.setUserId("invite-" + UUID.randomUUID());
            }
            meeting.getParticipants().add(participant);
        }
    }

    private void validateCreateMeetingRequest(CreateMeetingRequest request) {
        if (request == null) throw new BusinessValidationException("Thông tin cuộc họp là bắt buộc.");
        if (request.title() != null && request.title().length() > 255) {
            throw new BusinessValidationException("Tiêu đề tối đa 255 ký tự.");
        }
        MeetingCreationMode mode = request.creationMode() == null ? MeetingCreationMode.SCHEDULED : request.creationMode();
        if (request.clientRequestId() != null) validatePointCreationRequestId(request.clientRequestId());
        if (request.participants() != null && (request.participants().size() > 500
            || request.participants().stream().anyMatch(p -> p == null || p.role() == null)))
            throw new BusinessValidationException("Danh sách tối đa 500 người và mỗi người phải có vai trò.");
        if (request.description() != null && request.description().length() > 100_000)
            throw new BusinessValidationException("Mô tả cuộc họp tối đa 100.000 ký tự.");
        if (scheduled(mode)) {
            if (request.title() == null || request.title().isBlank()) throw new BusinessValidationException("title is required");
            validateMeetingSchedule(request.startTime(), request.endTime());
        }
        if (mode != MeetingCreationMode.SCHEDULED && request.accessType() != MeetingAccessType.PUBLIC) {
            throw new BusinessValidationException("Cuộc họp tức thì/sử dụng sau là cuộc họp tự do.");
        }
        if (mode == MeetingCreationMode.POINT) {
            validatedPointNames(request.pointNames());
            validatePointCreationRequestId(request.clientRequestId());
            if (request.participants() != null && request.participants().size() > 500)
                throw new BusinessValidationException("Danh sách người tham gia tối đa 500 người.");
            if (request.participants() != null && request.participants().stream().anyMatch(p -> p == null || p.role() == null))
                throw new BusinessValidationException("Vai trò người tham gia là bắt buộc.");
            for (var participant : MeetingPointCreationCodec.participants(request.participants())) {
                if (participant.fullName() != null && (participant.fullName().length() > 255
                    || participant.fullName().codePoints().anyMatch(Character::isISOControl)))
                    throw new BusinessValidationException("Tên người tham gia tối đa 255 ký tự, không có ký tự điều khiển.");
                if (participant.userPublicId() != null) {
                    if (participant.userPublicId().length() > 100 || participant.userPublicId().codePoints().anyMatch(Character::isISOControl))
                        throw new BusinessValidationException("Mã tài khoản người tham gia không hợp lệ.");
                } else if (participant.invitedEmail() == null || participant.invitedEmail().length() > 255
                    || !participant.invitedEmail().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                    || participant.invitedEmail().codePoints().anyMatch(Character::isISOControl)) {
                    throw new BusinessValidationException("Cần chọn tài khoản hoặc email hợp lệ cho người tham gia.");
                }
            }
        }
        else if (request.pointNames() != null && !request.pointNames().isEmpty())
            throw new BusinessValidationException("Danh sách điểm cầu chỉ dùng cho cuộc họp điểm cầu.");
        validateCreateParticipants(request.accessType(), mode == MeetingCreationMode.POINT
            ? MeetingPointCreationCodec.participants(request.participants()) : request.participants());
    }

    private void validatePointCreationRequestId(String value) {
        try { if (value != null && UUID.fromString(value).toString().equals(value)) return; }
        catch (IllegalArgumentException ignored) { }
        throw new MeetingSpeakingException(HttpStatus.BAD_REQUEST, "MEETING_CREATION_REQUEST_ID_INVALID", "Mã tạo cuộc họp điểm cầu phải là UUID hợp lệ.");
    }

    private static boolean scheduled(MeetingCreationMode mode) {
        return mode == MeetingCreationMode.SCHEDULED || mode == MeetingCreationMode.POINT;
    }
    private void requirePointActiveAccount(MeetingEntity meeting) {
        if (meeting.getCreationMode() == MeetingCreationMode.POINT && meetingSpeakingService != null)
            meetingSpeakingService.requireEnabledViewer(meeting);
    }

    private List<String> validatedPointNames(List<String> names) {
        if (names == null || names.isEmpty() || names.size() > 100)
            throw new BusinessValidationException("Cuộc họp cần từ 1 đến 100 điểm cầu.");
        var normalized = new ArrayList<String>(); var keys = new HashSet<String>();
        for (String value : names) {
            String name = value == null ? "" : value.trim();
            if (name.isBlank() || name.length() > 150 || name.codePoints().anyMatch(Character::isISOControl))
                throw new BusinessValidationException("Tên điểm cầu cần từ 1 đến 150 ký tự.");
            if (!keys.add(name.toLowerCase(Locale.ROOT))) throw new BusinessValidationException("Tên điểm cầu không được trùng.");
            normalized.add(name);
        }
        return normalized;
    }

    private void attachPoints(MeetingEntity meeting, List<String> names) {
        for (int index = 0; index < names.size(); index++) {
            var point = new MeetingPointEntity(); point.setMeeting(meeting); point.setName(names.get(index));
            point.setNameKey(names.get(index).toLowerCase(Locale.ROOT)); point.setSortOrder(index);
            meeting.getPoints().add(point);
        }
    }

    private void applyPointRoster(MeetingEntity meeting, List<AddMeetingParticipantRequest> inputs,
        List<vn.cusc.meetingservice.dto.request.MeetingRosterBaseline> baseline) {
        if (inputs.size() > 500) throw new BusinessValidationException("Danh sách người tham gia tối đa 500 người.");
        Set<String> originalKeys = null;
        if (baseline != null) {
            if (baseline.size() > 500) throw new BusinessValidationException("Danh sách người tham gia tối đa 500 người.");
            originalKeys = new HashSet<>();
            for (var original : baseline) {
                if (original == null || !originalKeys.add(original.participantKey())) throw new BusinessValidationException("Danh sách đối chiếu không hợp lệ.");
                var current = meeting.getParticipants().stream().filter(p -> original.participantKey().equals(p.getParticipantKey())).findFirst().orElse(null);
                if (current == null || current.getStatus() == ParticipantStatus.REMOVED || current.getRole() != original.role())
                    throw new MeetingSpeakingException(HttpStatus.CONFLICT, "MEETING_ROSTER_CHANGED", "Danh sách người tham gia đã thay đổi. Vui lòng tải lại trước khi lưu.");
            }
        }
        var assignments = new LinkedHashMap<MeetingParticipantEntity, MeetingRole>();
        long chairs = 0, secretaries = 0;
        for (var input : inputs) {
            if (input == null || input.role() == null) throw new BusinessValidationException("Vai trò người tham gia là bắt buộc.");
            MeetingParticipantEntity target = null;
            if (input.participantKey() != null) {
                target = meeting.getParticipants().stream().filter(p -> input.participantKey().equals(p.getParticipantKey())).findFirst()
                    .orElseThrow(() -> new BusinessValidationException("Người tham gia không thuộc cuộc họp."));
                if (target.getStatus() == ParticipantStatus.REMOVED)
                    throw new BusinessValidationException("Người tham gia đã bị loại khỏi cuộc họp.");
            } else if (input.userPublicId() != null && !input.userPublicId().isBlank()) {
                target = meeting.getParticipants().stream().filter(p -> p.getUserAccount() != null
                    && input.userPublicId().equals(p.getUserAccount().getPublicId())).findFirst().orElse(null);
            } else {
                String email = userAccountService.normalizeEmail(input.invitedEmail());
                if (email == null || email.isBlank()) throw new BusinessValidationException("Cần chọn tài khoản hoặc email người tham gia.");
                target = meeting.getParticipants().stream().filter(p -> email.equals(p.getInvitedEmailNormalized())).findFirst().orElse(null);
            }
            if (target == null) {
                attachParticipants(meeting, List.of(input));
                target = meeting.getParticipants().get(meeting.getParticipants().size() - 1);
            } else if (target.getStatus() == ParticipantStatus.REMOVED) {
                throw new BusinessValidationException("Người tham gia đã bị loại khỏi cuộc họp.");
            }
            if (assignments.putIfAbsent(target, input.role()) != null)
                throw new BusinessValidationException("Người tham gia bị trùng.");
            if (input.fullName() != null && !input.fullName().isBlank()) target.setFullName(input.fullName().trim());
            if (input.role() == MeetingRole.CHAIRMAN) chairs++;
            if (input.role() == MeetingRole.SECRETARY) secretaries++;
        }
        if (chairs > 1 || secretaries > 1) throw new BusinessValidationException("Chỉ được có một chủ tọa và một thư ký.");
        // PUBLIC roster edits never remove arbitrary attendees; PRIVATE omissions revoke invitations.
        for (var participant : meeting.getParticipants()) {
            if (assignments.containsKey(participant)) participant.setRole(assignments.get(participant));
            else if (originalKeys != null && !originalKeys.contains(participant.getParticipantKey())) continue;
            else if (meeting.getAccessType() == MeetingAccessType.PRIVATE && participant.getStatus() != ParticipantStatus.REMOVED
                && !(participant.getUserAccount() != null && meeting.getCreatedByAccount() != null
                    && participant.getUserAccount().getId().equals(meeting.getCreatedByAccount().getId()))) {
                participant.setStatus(ParticipantStatus.REMOVED);
                if (meetingSpeakingService != null) meetingSpeakingService.finalizeForParticipantRemoval(meeting.getId(), participant.getId());
                var oldConnections = connectionRepository.findByParticipantId(participant.getId());
                oldConnections.forEach(c -> { c.setStatus(ParticipantConnectionStatus.REMOVED); c.setLeftAt(LocalDateTime.now()); });
                connectionRepository.saveAll(oldConnections);
                if (mediaCleanup != null && participant.getUserAccount() != null)
                    mediaCleanup.enqueue(meeting.getId(), "REMOVE_ATTENDEE", meeting.getRoomName(), "account:" + participant.getUserAccount().getPublicId());
            }
            else if (participant.getRole() == MeetingRole.CHAIRMAN || participant.getRole() == MeetingRole.SECRETARY)
                participant.setRole(MeetingRole.MEMBER);
        }
        for (var role : List.of(MeetingRole.CHAIRMAN, MeetingRole.SECRETARY)) {
            if (meeting.getParticipants().stream().filter(p -> p.getStatus() != ParticipantStatus.REMOVED && p.getRole() == role).count() > 1)
                throw new MeetingSpeakingException(HttpStatus.CONFLICT, "MEETING_ROSTER_CHANGED", "Chỉ được có một chủ tọa và một thư ký. Vui lòng tải lại danh sách.");
        }
    }

    private boolean canManage(MeetingEntity meeting, MeetingPrincipal principal) {
        if (principal == null) return false;
        MeetingParticipantEntity participant = meetingParticipantRepository
            .findByMeetingIdAndUserAccountId(meeting.getId(), principal.accountId()).orElse(null);
        return meetingAccessPolicyService.canManageMeeting(principal, meeting, participant);
    }

    private void validateMeetingSchedule(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime == null || endTime == null || !endTime.isAfter(startTime)) {
            throw new BusinessValidationException("endTime phai lon hon startTime");
        }
    }

    private void validateCreateParticipants(MeetingAccessType accessType, List<AddMeetingParticipantRequest> participants) {
        if (accessType == MeetingAccessType.PRIVATE && (participants == null || participants.isEmpty())) {
            throw new BusinessValidationException("Meeting private phai co participant");
        }
        if (participants == null) {
            return;
        }
        Set<String> uniqueInviteKeys = new HashSet<>();
        long chairmen = 0;
        long secretaries = 0;
        for (AddMeetingParticipantRequest participant : participants) {
            String uniqueKey = participant.userPublicId() != null && !participant.userPublicId().isBlank()
                ? "user:" + participant.userPublicId()
                : "email:" + userAccountService.normalizeEmail(participant.invitedEmail());
            if (!uniqueInviteKeys.add(uniqueKey)) {
                throw new BusinessValidationException("Participant bi trung");
            }
            if (participant.role() == MeetingRole.CHAIRMAN) {
                chairmen++;
            }
            if (participant.role() == MeetingRole.SECRETARY) {
                secretaries++;
            }
        }
        if (chairmen > 1) {
            throw new BusinessValidationException("Chi duoc co 1 chairman");
        }
        if (secretaries > 1) {
            throw new BusinessValidationException("Chỉ được có 1 thư ký trong cuộc họp.");
        }
    }

    private String generateUniqueMeetingCode() {
        String meetingCode;
        do {
            meetingCode = meetingCodeGenerator.generate();
        } while (meetingRepository.existsByMeetingCode(meetingCode));
        return meetingCode;
    }

    private String generateUniqueRoomName() {
        String roomName;
        do {
            roomName = roomNameGenerator.generate();
        } while (meetingRepository.existsByRoomName(roomName));
        return roomName;
    }
}
