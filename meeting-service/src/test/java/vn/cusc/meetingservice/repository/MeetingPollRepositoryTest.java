package vn.cusc.meetingservice.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.entity.MeetingPollBallotEntity;
import vn.cusc.meetingservice.entity.MeetingPollBallotSelectionEntity;
import vn.cusc.meetingservice.entity.MeetingPollEligibleVoterEntity;
import vn.cusc.meetingservice.entity.MeetingPollEntity;
import vn.cusc.meetingservice.entity.MeetingPollOptionEntity;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.model.enums.AccountStatus;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.MeetingPollOptionCode;
import vn.cusc.meetingservice.model.enums.MeetingPollResultVisibility;
import vn.cusc.meetingservice.model.enums.MeetingPollStatus;
import vn.cusc.meetingservice.model.enums.MeetingPollType;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.model.enums.ParticipantJoinType;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;
import vn.cusc.meetingservice.model.enums.SystemRole;

@DataJpaTest
@ActiveProfiles("test")
class MeetingPollRepositoryTest {

    @Autowired private EntityManager entityManager;
    @Autowired private MeetingPollRepository polls;
    @Autowired private MeetingPollOptionRepository options;
    @Autowired private MeetingPollEligibleVoterRepository eligibleVoters;
    @Autowired private MeetingPollBallotRepository ballots;
    @Autowired private MeetingPollBallotSelectionRepository selections;

    private MeetingEntity meeting;
    private UserAccountEntity creator;
    private MeetingParticipantEntity participant;

    @BeforeEach
    void setUp() {
        creator = new UserAccountEntity();
        creator.setPublicId(UUID.randomUUID().toString());
        creator.setNormalizedEmail(UUID.randomUUID() + "@example.test");
        creator.setFullName("Poll manager");
        creator.setAccountStatus(AccountStatus.ACTIVE);
        creator.setSystemRole(SystemRole.USER);
        entityManager.persist(creator);

        meeting = meeting();
        participant = new MeetingParticipantEntity();
        participant.setMeeting(meeting);
        participant.setParticipantKey(UUID.randomUUID().toString());
        participant.setUserAccount(creator);
        participant.setUserId("account-" + creator.getPublicId());
        participant.setFullName("Original voter");
        participant.setEmail(creator.getNormalizedEmail());
        participant.setRole(MeetingRole.MEMBER);
        participant.setStatus(ParticipantStatus.INVITED);
        participant.setJoinType(ParticipantJoinType.AUTHENTICATED);
        entityManager.persist(participant);
    }

    @Test
    void restoresPersistedBallotSelectionsAndVoterSnapshotAfterReconnect() {
        MeetingPollEntity poll = poll(meeting);
        MeetingPollOptionEntity no = option(poll, 1, MeetingPollOptionCode.NO);
        MeetingPollOptionEntity yes = option(poll, 0, MeetingPollOptionCode.YES);
        MeetingPollEligibleVoterEntity eligible = eligible(poll);
        assertFalse(eligibleVoters.existsByPollIdAndParticipantIdAndSnapshotAtIsNotNull(poll.getId(), participant.getId()));
        snapshot(eligible);
        eligibleVoters.saveAndFlush(eligible);
        poll.setType(MeetingPollType.MULTIPLE_CHOICE);
        poll.setMaxSelections(2);
        poll.setStatus(MeetingPollStatus.OPEN);
        poll.setOpenedAt(LocalDateTime.of(2026, 10, 5, 8, 59));
        polls.saveAndFlush(poll);
        MeetingPollBallotEntity ballot = ballot(poll);
        selection(poll, ballot, no);
        selection(poll, ballot, yes);
        participant.setFullName("Changed name");
        participant.setRole(MeetingRole.GUEST);
        participant.setStatus(ParticipantStatus.REMOVED);
        entityManager.flush();
        entityManager.clear();

        MeetingPollBallotEntity restored = ballots.findByPollIdAndVoterId(poll.getId(), participant.getId()).orElseThrow();
        assertEquals(ballot.getId(), restored.getId());
        assertEquals(ballot.getSubmittedAt(), restored.getSubmittedAt());
        assertEquals(1, ballots.countByPollId(poll.getId()));
        assertEquals(1, eligibleVoters.countByPollIdAndSnapshotAtIsNotNull(poll.getId()));
        MeetingPollEligibleVoterEntity restoredVoter = eligibleVoters
            .findByPollIdAndParticipantId(poll.getId(), participant.getId()).orElseThrow();
        assertEquals("Original voter", restoredVoter.getFullNameSnapshot());
        assertEquals(MeetingRole.MEMBER, restoredVoter.getRoleSnapshot());
        assertEquals(ParticipantStatus.REMOVED, restoredVoter.getParticipant().getStatus());
        assertEquals(java.util.List.of(yes.getId(), no.getId()), selections
            .findByBallotIdOrderByOptionSortOrderAscOptionIdAsc(ballot.getId())
            .stream().map(value -> value.getOption().getId()).toList());
    }

    @Test
    void historyAndLockedLookupAreScopedToMeeting() {
        MeetingPollEntity first = poll(meeting);
        MeetingPollEntity second = poll(meeting);
        MeetingEntity otherMeeting = meeting();
        poll(otherMeeting);
        // Equal creation times exercise the ID tie-breaker in pagination.
        LocalDateTime time = LocalDateTime.of(2026, 10, 5, 9, 0);
        entityManager.createQuery("update MeetingPollEntity p set p.createdAt = :time where p.meeting.id = :id")
            .setParameter("time", time).setParameter("id", meeting.getId()).executeUpdate();
        entityManager.clear();

        assertEquals(second.getId(), polls.findByMeetingIdOrderByCreatedAtDescIdDesc(
            meeting.getId(), PageRequest.of(0, 1)).getContent().get(0).getId());
        assertEquals(first.getId(), polls.findByMeetingIdOrderByCreatedAtDescIdDesc(
            meeting.getId(), PageRequest.of(1, 1)).getContent().get(0).getId());
        assertTrue(polls.findByIdAndMeetingId(first.getId(), otherMeeting.getId()).isEmpty());
        assertTrue(polls.findByIdAndMeetingIdForUpdate(first.getId(), otherMeeting.getId()).isEmpty());
        assertEquals(first.getId(), polls.findByIdAndMeetingIdForUpdate(first.getId(), meeting.getId()).orElseThrow().getId());
    }

    @Test
    void findsOnlyOpenPollWhileMultipleDraftsExist() {
        poll(meeting);
        poll(meeting);
        assertTrue(polls.findOpenByMeetingId(meeting.getId()).isEmpty());
        MeetingPollEntity open = poll(meeting);
        open.setStatus(MeetingPollStatus.OPEN);
        open.setOpenedAt(LocalDateTime.now());
        polls.saveAndFlush(open);
        assertEquals(open.getId(), polls.findOpenByMeetingId(meeting.getId()).orElseThrow().getId());
    }

    @Test
    void supportsMultipleChoiceLimitsAndGenericOptionsWithoutCodes() {
        MeetingPollEntity poll = poll(meeting);
        poll.setType(MeetingPollType.MULTIPLE_CHOICE);
        poll.setMinSelections(2);
        poll.setMaxSelections(3);
        poll.setAllowChangeVote(true);
        poll.setResultVisibility(MeetingPollResultVisibility.AUTHORIZED_ONLY);
        polls.saveAndFlush(poll);
        option(poll, 2, null);
        option(poll, 0, null);
        option(poll, 1, null);
        entityManager.clear();
        MeetingPollEntity restored = polls.findByIdAndMeetingId(poll.getId(), meeting.getId()).orElseThrow();
        assertEquals(2, restored.getMinSelections());
        assertEquals(3, restored.getMaxSelections());
        assertTrue(restored.isAllowChangeVote());
        assertEquals(java.util.List.of(0, 1, 2), options.findByPollIdOrderBySortOrderAscIdAsc(poll.getId())
            .stream().map(MeetingPollOptionEntity::getSortOrder).toList());
    }

    enum Duplicate { OPTION_ORDER, OPTION_CODE, ELIGIBLE_VOTER, BALLOT, SELECTION }

    @ParameterizedTest
    @EnumSource(Duplicate.class)
    void rejectsDuplicateRows(Duplicate duplicate) {
        MeetingPollEntity poll = poll(meeting);
        MeetingPollOptionEntity option = option(poll, 0, MeetingPollOptionCode.YES);
        eligible(poll);
        MeetingPollBallotEntity ballot = ballot(poll);
        selection(poll, ballot, option);
        assertThrows(DataIntegrityViolationException.class, () -> {
            switch (duplicate) {
                case OPTION_ORDER -> option(poll, 0, MeetingPollOptionCode.NO);
                case OPTION_CODE -> option(poll, 1, MeetingPollOptionCode.YES);
                case ELIGIBLE_VOTER -> eligible(poll);
                case BALLOT -> ballot(poll);
                case SELECTION -> selection(poll, ballot, option);
            }
        });
    }

    private MeetingEntity meeting() {
        MeetingEntity value = new MeetingEntity();
        String key = UUID.randomUUID().toString();
        value.setMeetingCode(key.substring(0, 32));
        value.setRoomName(key);
        value.setTitle("Poll repository test");
        value.setStatus(MeetingStatus.UPCOMING);
        value.setAccessType(MeetingAccessType.PRIVATE);
        value.setStartTime(LocalDateTime.now().plusHours(1));
        value.setEndTime(LocalDateTime.now().plusHours(2));
        value.setCreatedByAccount(creator);
        entityManager.persist(value);
        return value;
    }

    private MeetingPollEntity poll(MeetingEntity owner) {
        MeetingPollEntity value = new MeetingPollEntity();
        value.setMeeting(owner);
        value.setCreatedByAccount(creator);
        value.setQuestion("Approve proposal?");
        value.setType(MeetingPollType.YES_NO_ABSTAIN);
        return polls.saveAndFlush(value);
    }

    private MeetingPollOptionEntity option(MeetingPollEntity poll, int order, MeetingPollOptionCode code) {
        MeetingPollOptionEntity value = new MeetingPollOptionEntity();
        value.setPoll(poll);
        value.setSortOrder(order);
        value.setCode(code);
        value.setLabel(code == null ? "Option " + order : code.name());
        return options.saveAndFlush(value);
    }

    private MeetingPollEligibleVoterEntity eligible(MeetingPollEntity poll) {
        MeetingPollEligibleVoterEntity value = new MeetingPollEligibleVoterEntity();
        value.setPoll(poll);
        value.setMeeting(meeting);
        value.setParticipant(participant);
        return eligibleVoters.saveAndFlush(value);
    }

    private void snapshot(MeetingPollEligibleVoterEntity value) {
        value.setParticipantKeySnapshot(participant.getParticipantKey());
        value.setFullNameSnapshot(participant.getFullName());
        value.setRoleSnapshot(participant.getRole());
        value.setSnapshotAt(LocalDateTime.now());
    }

    private MeetingPollBallotEntity ballot(MeetingPollEntity poll) {
        MeetingPollBallotEntity value = new MeetingPollBallotEntity();
        value.setPoll(poll);
        value.setVoter(participant);
        value.setSubmittedAt(LocalDateTime.of(2026, 10, 5, 9, 0));
        return ballots.saveAndFlush(value);
    }

    private MeetingPollBallotSelectionEntity selection(
        MeetingPollEntity poll, MeetingPollBallotEntity ballot, MeetingPollOptionEntity option
    ) {
        MeetingPollBallotSelectionEntity value = new MeetingPollBallotSelectionEntity();
        value.setPoll(poll);
        value.setBallot(ballot);
        value.setOption(option);
        return selections.saveAndFlush(value);
    }
}
