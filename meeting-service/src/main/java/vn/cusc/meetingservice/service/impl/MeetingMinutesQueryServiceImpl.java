package vn.cusc.meetingservice.service.impl;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.cusc.meetingservice.dto.response.AccountSummaryResponse;
import vn.cusc.meetingservice.dto.response.MinutesListItemResponse;
import vn.cusc.meetingservice.dto.response.MinutesParticipantPreviewResponse;
import vn.cusc.meetingservice.dto.response.MinutesSummaryResponse;
import vn.cusc.meetingservice.dto.response.PageResponse;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingMinutesEntity;
import vn.cusc.meetingservice.entity.MeetingMinutesJobEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.model.enums.MinutesJobStatus;
import vn.cusc.meetingservice.model.enums.MinutesStatus;
import vn.cusc.meetingservice.repository.MeetingMinutesJobRepository;
import vn.cusc.meetingservice.repository.MeetingMinutesRepository;
import vn.cusc.meetingservice.repository.MeetingParticipantRepository;
import vn.cusc.meetingservice.repository.MeetingRepository;
import vn.cusc.meetingservice.security.MeetingPrincipal;
import vn.cusc.meetingservice.service.MeetingMinutesAuthorizationService;
import vn.cusc.meetingservice.service.MeetingMinutesQueryService;
import vn.cusc.meetingservice.util.MeetingVisibility;
import vn.cusc.meetingservice.util.MeetingPermissions;

@Service
public class MeetingMinutesQueryServiceImpl implements MeetingMinutesQueryService {

    private final MeetingRepository meetingRepository;
    private final MeetingMinutesRepository minutesRepository;
    private final MeetingMinutesJobRepository jobRepository;
    private final MeetingParticipantRepository participantRepository;
    private final MeetingMinutesAuthorizationService authorizationService;

    public MeetingMinutesQueryServiceImpl(
        MeetingRepository meetingRepository,
        MeetingMinutesRepository minutesRepository,
        MeetingMinutesJobRepository jobRepository,
        MeetingParticipantRepository participantRepository,
        MeetingMinutesAuthorizationService authorizationService
    ) {
        this.meetingRepository = meetingRepository;
        this.minutesRepository = minutesRepository;
        this.jobRepository = jobRepository;
        this.participantRepository = participantRepository;
        this.authorizationService = authorizationService;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<MinutesListItemResponse> getMinutes(
        String query,
        MinutesStatus status,
        MeetingRole role,
        LocalDate from,
        LocalDate to,
        Pageable pageable
    ) {
        List<MinutesListItemResponse> filtered = loadAuthorized(query, role, from, to).stream()
            .filter(item -> status == null || item.minutesStatus() == status)
            .toList();
        int start = Math.min((int) pageable.getOffset(), filtered.size());
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        int totalPages = filtered.isEmpty() ? 0 : (int) Math.ceil((double) filtered.size() / pageable.getPageSize());
        return new PageResponse<>(
            filtered.subList(start, end),
            pageable.getPageNumber(),
            pageable.getPageSize(),
            filtered.size(),
            totalPages,
            pageable.getPageNumber() == 0,
            pageable.getPageNumber() >= Math.max(0, totalPages - 1)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public MinutesSummaryResponse getSummary() {
        List<MinutesListItemResponse> items = loadAuthorized(null, null, null, null);
        long notGenerated = items.stream()
            .filter(item -> item.minutesStatus() == MinutesStatus.NOT_GENERATED
                || item.minutesStatus() == MinutesStatus.FAILED)
            .count();
        long processing = items.stream()
            .filter(item -> item.minutesStatus() == MinutesStatus.PROCESSING)
            .count();
        long available = items.stream()
            .filter(item -> item.minutesStatus() == MinutesStatus.DRAFT
                || item.minutesStatus() == MinutesStatus.FINAL)
            .count();
        return new MinutesSummaryResponse(items.size(), notGenerated, processing, available);
    }

    private List<MinutesListItemResponse> loadAuthorized(
        String query,
        MeetingRole role,
        LocalDate from,
        LocalDate to
    ) {
        authorizationService.requireListAccess();
        MeetingPrincipal principal = authorizationService.requirePrincipal();
        List<MeetingEntity> meetings = meetingRepository.findAll(
            buildSpecification(principal, query, role, from, to)
                .and(MeetingVisibility.visibleTo(principal)),
            org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "startTime")
        );
        if (meetings.isEmpty()) {
            return List.of();
        }
        List<Long> meetingIds = meetings.stream().map(MeetingEntity::getId).toList();
        QueryContext context = loadContext(meetingIds);
        return meetings.stream().map(meeting -> toListItem(meeting, principal, context)).toList();
    }

    private Specification<MeetingEntity> buildSpecification(
        MeetingPrincipal principal,
        String queryText,
        MeetingRole role,
        LocalDate from,
        LocalDate to
    ) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();
            predicates.add(criteriaBuilder.notEqual(root.get("status"), MeetingStatus.CANCELLED));
            Predicate explicitlyCompleted = criteriaBuilder.equal(
                root.get("status"),
                MeetingStatus.COMPLETED
            );
            Predicate endedBySchedule = criteriaBuilder.lessThanOrEqualTo(
                root.get("endTime"),
                now
            );
            predicates.add(criteriaBuilder.or(explicitlyCompleted, endedBySchedule));
            if (queryText != null && !queryText.isBlank()) {
                String like = "%" + queryText.trim().toLowerCase() + "%";
                predicates.add(criteriaBuilder.or(
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("title")), like),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("meetingCode")), like)
                ));
            }
            if (from != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("startTime"), from.atStartOfDay()));
            }
            if (to != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("endTime"), to.atTime(23, 59, 59)));
            }
            if (role != null) {
                Join<MeetingEntity, MeetingParticipantEntity> participant = root.join("participants", JoinType.INNER);
                predicates.add(criteriaBuilder.equal(participant.get("userAccount").get("id"), principal.accountId()));
                predicates.add(criteriaBuilder.equal(participant.get("role"), role));
                predicates.add(criteriaBuilder.notEqual(participant.get("status"), ParticipantStatus.REMOVED));
                query.distinct(true);
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private QueryContext loadContext(List<Long> meetingIds) {
        Map<Long, MeetingMinutesEntity> latestMinutes = new HashMap<>();
        minutesRepository.findByMeetingIdInOrderByMeetingIdAscVersionDesc(meetingIds)
            .forEach(minutes -> latestMinutes.putIfAbsent(minutes.getMeeting().getId(), minutes));
        Map<Long, MeetingMinutesJobEntity> latestJobs = new HashMap<>();
        jobRepository.findByMeetingIdInOrderByMeetingIdAscAttemptDesc(meetingIds)
            .forEach(job -> latestJobs.putIfAbsent(job.getMeeting().getId(), job));
        Map<Long, List<MeetingParticipantEntity>> participants = new LinkedHashMap<>();
        participantRepository.findByMeetingIdIn(meetingIds)
            .forEach(participant -> participants
                .computeIfAbsent(participant.getMeeting().getId(), ignored -> new ArrayList<>())
                .add(participant));
        return new QueryContext(latestMinutes, latestJobs, participants);
    }

    private MinutesListItemResponse toListItem(
        MeetingEntity meeting,
        MeetingPrincipal principal,
        QueryContext context
    ) {
        List<MeetingParticipantEntity> participants = context.participants()
            .getOrDefault(meeting.getId(), List.of());
        MeetingParticipantEntity chairman = participants.stream()
            .filter(item -> item.getRole() == MeetingRole.CHAIRMAN)
            .findFirst()
            .orElse(null);
        MeetingRole myRole = participants.stream()
            .filter(item -> item.getStatus() != ParticipantStatus.REMOVED)
            .filter(item -> item.getUserAccount() != null
                && item.getUserAccount().getId().equals(principal.accountId()))
            .map(MeetingParticipantEntity::getRole)
            .findFirst()
            .orElse(null);
        MeetingMinutesEntity latestMinutes = context.latestMinutes().get(meeting.getId());
        MeetingMinutesJobEntity latestJob = context.latestJobs().get(meeting.getId());
        LocalDateTime updatedAt = latestMinutes != null
            ? latestMinutes.getUpdatedAt()
            : latestJob != null ? latestJob.getUpdatedAt() : meeting.getUpdatedAt();
        return new MinutesListItemResponse(
            meeting.getId(),
            meeting.getMeetingCode(),
            meeting.getTitle(),
            meeting.getStartTime() == null ? null : meeting.getStartTime().toLocalDate(),
            meeting.getStartTime() == null ? null : meeting.getStartTime().toLocalTime(),
            meeting.getEndTime() == null ? null : meeting.getEndTime().toLocalTime(),
            chairman == null ? null : new AccountSummaryResponse(
                chairman.getUserAccount() == null ? null : chairman.getUserAccount().getPublicId(),
                chairman.getFullName()
            ),
            participants.size(),
            participants.stream().limit(5).map(item -> new MinutesParticipantPreviewResponse(
                item.getUserAccount() == null ? item.getParticipantKey() : item.getUserAccount().getPublicId(),
                item.getFullName()
            )).toList(),
            resolveStatus(latestMinutes, latestJob),
            latestMinutes == null ? null : latestMinutes.getVersion(),
            updatedAt,
            myRole,
            MeetingPermissions.canManage(principal, meeting, participants.stream()
                .filter(item -> item.getUserAccount() != null && item.getUserAccount().getId().equals(principal.accountId()))
                .findFirst().orElse(null))
        );
    }

    public static MinutesStatus resolveStatus(
        MeetingMinutesEntity latestMinutes,
        MeetingMinutesJobEntity latestJob
    ) {
        if (latestMinutes != null) {
            return latestMinutes.getStatus() == MinutesStatus.FINAL
                ? MinutesStatus.FINAL
                : MinutesStatus.DRAFT;
        }
        if (latestJob != null && (latestJob.getStatus() == MinutesJobStatus.QUEUED
            || latestJob.getStatus() == MinutesJobStatus.PROCESSING)) {
            return MinutesStatus.PROCESSING;
        }
        if (latestJob != null && latestJob.getStatus() == MinutesJobStatus.FAILED) {
            return MinutesStatus.FAILED;
        }
        return MinutesStatus.NOT_GENERATED;
    }

    private record QueryContext(
        Map<Long, MeetingMinutesEntity> latestMinutes,
        Map<Long, MeetingMinutesJobEntity> latestJobs,
        Map<Long, List<MeetingParticipantEntity>> participants
    ) {
    }
}
