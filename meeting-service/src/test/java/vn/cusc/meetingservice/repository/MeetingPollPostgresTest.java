package vn.cusc.meetingservice.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import vn.cusc.meetingservice.entity.MeetingEntity;
import vn.cusc.meetingservice.entity.MeetingParticipantEntity;
import vn.cusc.meetingservice.entity.MeetingPollBallotEntity;
import vn.cusc.meetingservice.entity.MeetingPollBallotSelectionEntity;
import vn.cusc.meetingservice.entity.MeetingPollEligibleVoterEntity;
import vn.cusc.meetingservice.entity.MeetingPollEntity;
import vn.cusc.meetingservice.entity.MeetingPollOptionEntity;
import vn.cusc.meetingservice.entity.UserAccountEntity;
import vn.cusc.meetingservice.model.enums.MeetingPollStatus;
import vn.cusc.meetingservice.model.enums.MeetingPollType;
import vn.cusc.meetingservice.model.enums.MeetingRole;
import vn.cusc.meetingservice.model.enums.MeetingAccessType;
import vn.cusc.meetingservice.model.enums.MeetingStatus;
import vn.cusc.meetingservice.model.enums.ParticipantJoinType;
import vn.cusc.meetingservice.model.enums.ParticipantStatus;

/**
 * Opt-in tests against real PostgreSQL: H2 cannot prove partial indexes or deferred FKs.
 * Only a fresh, randomly named schema is migrated; application schemas are never touched.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "poll.postgres.url", matches = "jdbc:postgresql:.+")
class MeetingPollPostgresTest {

    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 5, 9, 0);
    private static final List<String> LEGACY_TABLES = List.of(
        "user_accounts", "meetings", "meeting_participants", "meeting_capture_sessions",
        "meeting_minutes", "meeting_minutes_jobs", "meeting_documents", "meeting_chat_messages",
        "meeting_participant_connections", "meeting_transcript_chunks", "meeting_transcript_failures"
    );
    private final String schema = "poll_p1_test_" + UUID.randomUUID().toString().replace("-", "");
    private DriverManagerDataSource dataSource;
    private LocalContainerEntityManagerFactoryBean factory;
    private Connection connection;
    private boolean schemaCreated;
    private long accountId;
    private long meetingId;
    private long voterId;
    private Map<String, String> legacyRows;
    private int migrationsApplied;
    private int repeatedMigrations;

    @BeforeAll
    void migrateIsolatedSchemaAndValidateMappings() throws Exception {
        String password = System.getenv("POLL_TEST_DB_PASSWORD");
        assertNotNull(password, "Set POLL_TEST_DB_PASSWORD for the explicitly selected test database.");
        dataSource = new DriverManagerDataSource(
            System.getProperty("poll.postgres.url"),
            System.getProperty("poll.postgres.username", "meeting_user"), password
        );
        try (Connection admin = dataSource.getConnection()) {
            execute(admin, "CREATE SCHEMA " + schema);
            schemaCreated = true;
        }
        var properties = new java.util.Properties();
        properties.setProperty("currentSchema", schema);
        dataSource.setConnectionProperties(properties);
        flyway("13").migrate();
        try (Connection seed = dataSource.getConnection()) {
            accountId = insert(seed, """
                INSERT INTO user_accounts
                (public_id, normalized_email, full_name, account_status, system_role, created_at, updated_at)
                VALUES (?, 'poll-legacy@example.test', 'Legacy account', 'ACTIVE', 'USER', ?, ?) RETURNING id
                """, UUID.randomUUID().toString(), TIME, TIME);
            meetingId = meeting(seed);
            voterId = participant(seed, meetingId);
            insert(seed, """
                INSERT INTO meeting_capture_sessions
                (meeting_id, status, started_at, completed_at, manifest_path, created_at, updated_at)
                VALUES (?, 'COMPLETED', ?, ?, 'legacy-manifest.json', ?, ?) RETURNING id
                """, meetingId, TIME, TIME.plusMinutes(1), TIME, TIME);
            insert(seed, """
                INSERT INTO meeting_minutes
                (meeting_id, version, minutes_json, generated_at, status, source, created_at, updated_at)
                VALUES (?, 1, '{}', ?, 'DRAFT', 'AI_GENERATED', ?, ?) RETURNING id
                """, meetingId, TIME, TIME, TIME);
            legacyRows = legacyRows(seed);
        }
        migrationsApplied = flyway(null).migrate().migrationsExecuted;
        repeatedMigrations = flyway(null).migrate().migrationsExecuted;

        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("vn.cusc.meetingservice.entity");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
            "hibernate.hbm2ddl.auto", "validate",
            "hibernate.default_schema", schema,
            "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
            "hibernate.show_sql", "false"
        ));
        factory.afterPropertiesSet();
    }

    @BeforeEach
    void beginTransaction() throws SQLException {
        connection = dataSource.getConnection();
        connection.setAutoCommit(false);
    }

    @AfterEach
    void rollbackTransaction() throws SQLException {
        if (connection != null) {
            try {
                connection.rollback();
            } finally {
                connection.close();
                connection = null;
            }
        }
    }

    @AfterAll
    void removeOnlyOwnedSchema() throws SQLException {
        if (factory != null) {
            factory.destroy();
        }
        if (schemaCreated) {
            assertTrue(schema.matches("poll_p1_test_[0-9a-f]{32}"));
            try (Connection admin = dataSource.getConnection()) {
                execute(admin, "DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void upgradesV13WithoutChangingLegacyRowsAndDoesNotReplayCurrentMigrations() throws SQLException {
        assertEquals(9, migrationsApplied);
        assertEquals(0, repeatedMigrations);
        assertEquals(legacyRows, legacyRows(connection));
        assertEquals(22, scalar(connection, "SELECT count(*) FROM flyway_schema_history WHERE success"));
        assertEquals(1, scalar(connection, "SELECT count(*) FROM user_accounts WHERE security_version=0 AND row_version=0"));
        assertEquals(1, scalar(connection, "SELECT count(*) FROM meeting_minutes WHERE revision=0"));
    }

    @Test
    void validatesMappingsAndRestoresAggregateWithoutBreakingExistingMeetingDeletion() {
        EntityManager em = factory.getObject().createEntityManager();
        em.getTransaction().begin();
        try {
            String key = UUID.randomUUID().toString().replace("-", "");
            MeetingEntity owner = new MeetingEntity();
            owner.setMeetingCode(key);
            owner.setRoomName(key);
            owner.setTitle("ORM Poll meeting");
            owner.setStartTime(TIME);
            owner.setEndTime(TIME.plusHours(1));
            owner.setStatus(MeetingStatus.UPCOMING);
            owner.setAccessType(MeetingAccessType.PRIVATE);
            em.persist(owner);
            MeetingParticipantEntity participant = new MeetingParticipantEntity();
            participant.setMeeting(owner);
            participant.setUserAccount(em.getReference(UserAccountEntity.class, accountId));
            participant.setUserId(key);
            participant.setParticipantKey(UUID.randomUUID().toString());
            participant.setFullName("ORM voter");
            participant.setEmail("orm@example.test");
            participant.setRole(MeetingRole.MEMBER);
            participant.setStatus(ParticipantStatus.INVITED);
            participant.setJoinType(ParticipantJoinType.AUTHENTICATED);
            em.persist(participant);
            MeetingPollEntity poll = new MeetingPollEntity();
            poll.setMeeting(owner);
            poll.setCreatedByAccount(em.getReference(UserAccountEntity.class, accountId));
            poll.setQuestion("Approve proposal?");
            poll.setType(MeetingPollType.SINGLE_CHOICE);
            em.persist(poll);

            MeetingPollOptionEntity option = new MeetingPollOptionEntity();
            option.setPoll(poll);
            option.setSortOrder(0);
            option.setLabel("Approve");
            em.persist(option);

            MeetingPollEligibleVoterEntity eligible = new MeetingPollEligibleVoterEntity();
            eligible.setPoll(poll);
            eligible.setMeeting(poll.getMeeting());
            eligible.setParticipant(participant);
            eligible.setParticipantKeySnapshot(participant.getParticipantKey());
            eligible.setFullNameSnapshot(participant.getFullName());
            eligible.setRoleSnapshot(participant.getRole());
            eligible.setSnapshotAt(TIME);
            em.persist(eligible);
            poll.setStatus(MeetingPollStatus.OPEN);
            poll.setOpenedAt(TIME);
            // Opening and roster persistence precede the future vote operation.
            em.flush();

            MeetingPollBallotEntity ballot = new MeetingPollBallotEntity();
            ballot.setPoll(poll);
            ballot.setVoter(participant);
            ballot.setSubmittedAt(TIME.plusSeconds(1));
            em.persist(ballot);
            MeetingPollBallotSelectionEntity selection = new MeetingPollBallotSelectionEntity();
            selection.setPoll(poll);
            selection.setBallot(ballot);
            selection.setOption(option);
            em.persist(selection);
            em.flush();
            em.createNativeQuery("SET CONSTRAINTS ALL IMMEDIATE").executeUpdate();
            em.clear();

            assertEquals(MeetingPollStatus.OPEN, em.find(MeetingPollEntity.class, poll.getId()).getStatus());
            assertEquals("Approve", em.find(MeetingPollOptionEntity.class, option.getId()).getLabel());
            assertEquals(MeetingRole.MEMBER, em.find(MeetingPollEligibleVoterEntity.class, eligible.getId()).getRoleSnapshot());
            assertEquals(participant.getId(), em.find(MeetingPollBallotEntity.class, ballot.getId()).getVoter().getId());
            assertEquals(option.getId(), em.find(MeetingPollBallotSelectionEntity.class, selection.getId()).getOption().getId());
            em.clear();
            // Use the existing participant ORM cascade without changing MeetingEntity.
            em.createNativeQuery("SET CONSTRAINTS ALL DEFERRED").executeUpdate();
            em.remove(em.find(MeetingEntity.class, owner.getId()));
            em.flush();
            em.createNativeQuery("SET CONSTRAINTS ALL IMMEDIATE").executeUpdate();
            assertEquals(0L, em.createQuery("select count(p) from MeetingPollEntity p", Long.class).getSingleResult());
            assertEquals(0L, em.createQuery("select count(s) from MeetingPollBallotSelectionEntity s", Long.class).getSingleResult());
        } finally {
            em.getTransaction().rollback();
            em.close();
        }
    }

    @Test
    void allowsManyDraftsAndTerminalPollsButOnlyOneOpenPerMeeting() throws SQLException {
        poll(connection, meetingId, "DRAFT");
        poll(connection, meetingId, "DRAFT");
        long open = poll(connection, meetingId, "OPEN");
        poll(connection, meetingId, "CLOSED");
        poll(connection, meetingId, "CANCELLED");
        assertConstraint("23505", "uk_meeting_polls_one_open", () -> poll(connection, meetingId, "OPEN"));
        poll(connection, meeting(connection), "OPEN");
        execute(connection, "UPDATE meeting_polls SET status = 'CLOSED', closed_at = ? WHERE id = ?", TIME.plusMinutes(1), open);
        poll(connection, meetingId, "OPEN");
    }

    enum Duplicate { OPTION_ORDER, OPTION_CODE, ELIGIBLE_VOTER, BALLOT, SELECTION }

    @ParameterizedTest
    @EnumSource(Duplicate.class)
    void rejectsDuplicatesOnRealDatabase(Duplicate duplicate) throws SQLException {
        long poll = poll(connection, meetingId, "OPEN");
        long option = option(poll, 0, "YES");
        eligible(connection, poll, meetingId, voterId);
        long ballot = ballot(connection, poll, voterId);
        selection(poll, ballot, option);
        String constraint = switch (duplicate) {
            case OPTION_ORDER -> "uk_meeting_poll_options_order";
            case OPTION_CODE -> "uk_meeting_poll_options_code";
            case ELIGIBLE_VOTER -> "uk_meeting_poll_eligible_voter";
            case BALLOT -> "uk_meeting_poll_ballots_voter";
            case SELECTION -> "uk_meeting_poll_ballot_selection";
        };
        assertConstraint("23505", constraint, () -> {
            switch (duplicate) {
                case OPTION_ORDER -> option(poll, 0, "NO");
                case OPTION_CODE -> option(poll, 1, "YES");
                case ELIGIBLE_VOTER -> eligible(connection, poll, meetingId, voterId);
                case BALLOT -> ballot(connection, poll, voterId);
                case SELECTION -> selection(poll, ballot, option);
            }
        });
    }

    @Test
    void rejectsVoterFromAnotherMeetingAndForgedMeetingOnRoster() throws SQLException {
        long otherMeeting = meeting(connection);
        long otherVoter = participant(connection, otherMeeting);
        long poll = poll(connection, meetingId, "DRAFT");
        assertConstraint("23503", "fk_meeting_poll_eligible_participant",
            () -> eligible(connection, poll, meetingId, otherVoter));
        assertConstraint("23503", "fk_meeting_poll_eligible_poll",
            () -> eligible(connection, poll, otherMeeting, otherVoter));
    }

    @Test
    void rejectsBallotWithoutEligibleVoter() throws SQLException {
        long poll = poll(connection, meetingId, "OPEN");
        assertConstraint("23503", "fk_meeting_poll_ballot_eligible", () -> ballot(connection, poll, voterId));
    }

    @Test
    void rejectsSelectionFromAnotherPollForEitherOptionOrBallot() throws SQLException {
        long first = poll(connection, meetingId, "DRAFT");
        long second = poll(connection, meetingId, "DRAFT");
        eligible(connection, first, meetingId, voterId);
        long ballot = ballot(connection, first, voterId);
        long firstOption = option(first, 0, null);
        long secondOption = option(second, 0, null);
        assertConstraint("23503", "fk_meeting_poll_selection_option", () -> selection(first, ballot, secondOption));
        assertConstraint("23503", "fk_meeting_poll_selection_ballot", () -> selection(second, ballot, firstOption));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "question = '  '", "type = 'UNKNOWN'", "status = 'UNKNOWN'", "result_visibility = 'UNKNOWN'",
        "min_selections = 0", "max_selections = 0", "max_selections = 2",
        "status = 'OPEN'", "status = 'CLOSED'", "status = 'CANCELLED'"
    })
    void rejectsInvalidPollConfiguration(String change) throws SQLException {
        long poll = poll(connection, meetingId, "DRAFT");
        assertConstraint("23514", "ck_meeting_polls_", () ->
            execute(connection, "UPDATE meeting_polls SET " + change + " WHERE id = ?", poll));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "status = 'CLOSED', closed_at = NULL",
        "status = 'CLOSED', closed_at = TIMESTAMP '2026-10-05 08:59:59'",
        "status = 'CANCELLED', cancelled_at = TIMESTAMP '2026-10-05 08:59:59'",
        "status = 'CANCELLED', cancelled_at = TIMESTAMP '2026-10-05 09:00:00', closed_at = TIMESTAMP '2026-10-05 09:00:00'",
        "cancelled_at = TIMESTAMP '2026-10-05 09:00:00'",
        "opened_at = NULL"
    })
    void rejectsInvalidTerminalTimestamps(String change) throws SQLException {
        long poll = poll(connection, meetingId, "OPEN");
        assertConstraint("23514", "ck_meeting_polls_", () ->
            execute(connection, "UPDATE meeting_polls SET " + change + " WHERE id = ?", poll));
    }

    @Test
    void rejectsPartialSnapshotsAndInvalidOptions() throws SQLException {
        long poll = poll(connection, meetingId, "DRAFT");
        long option = option(poll, 0, null);
        eligible(connection, poll, meetingId, voterId);
        assertConstraint("23514", "ck_meeting_poll_eligible_snapshot", () -> execute(connection,
            "UPDATE meeting_poll_eligible_voters SET snapshot_at = NULL WHERE poll_id = ?", poll));
        assertConstraint("23514", "ck_meeting_poll_options_label", () -> execute(connection,
            "UPDATE meeting_poll_options SET label = ' ' WHERE id = ?", option));
        assertConstraint("23514", "ck_meeting_poll_options_order", () -> execute(connection,
            "UPDATE meeting_poll_options SET sort_order = -1 WHERE id = ?", option));
        assertConstraint("23514", "ck_meeting_poll_options_code", () -> execute(connection,
            "UPDATE meeting_poll_options SET code = 'UNKNOWN' WHERE id = ?", option));
    }

    @Test
    void softRemovalPreservesSnapshotAndHardRemovalCannotEraseVotes() throws SQLException {
        long poll = poll(connection, meetingId, "OPEN");
        eligible(connection, poll, meetingId, voterId);
        ballot(connection, poll, voterId);
        execute(connection, "UPDATE meeting_participants SET status = 'REMOVED', role = 'GUEST', full_name = 'Changed' WHERE id = ?", voterId);
        assertEquals("Legacy voter", string(connection,
            "SELECT full_name_snapshot FROM meeting_poll_eligible_voters WHERE poll_id = ?", poll));
        assertEquals(1, scalar(connection, "SELECT count(*) FROM meeting_poll_ballots WHERE poll_id = ?", poll));
        assertConstraint("23503", "fk_meeting_poll_", () ->
            execute(connection, "DELETE FROM meeting_participants WHERE id = ?", voterId));
    }

    @Test
    void meetingDeletionStillWorksWithParticipantFirstOrderAndCascadesPollAggregate() throws SQLException {
        long poll = poll(connection, meetingId, "OPEN");
        long option = option(poll, 0, null);
        eligible(connection, poll, meetingId, voterId);
        long ballot = ballot(connection, poll, voterId);
        selection(poll, ballot, option);
        // Existing MeetingEntity cascades delete participants before deleting the meeting.
        execute(connection, "DELETE FROM meeting_participants WHERE meeting_id = ?", meetingId);
        execute(connection, "DELETE FROM meetings WHERE id = ?", meetingId);
        execute(connection, "SET CONSTRAINTS ALL IMMEDIATE");
        for (String table : List.of("meeting_polls", "meeting_poll_options",
            "meeting_poll_eligible_voters", "meeting_poll_ballots", "meeting_poll_ballot_selections")) {
            assertEquals(0, scalar(connection, "SELECT count(*) FROM " + table));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentTransactionsHaveExactlyOneWinnerForOpenPollOrBallot(boolean voting) throws Exception {
        long ownedMeeting;
        long ownedVoter;
        long ownedPoll;
        try (Connection setup = dataSource.getConnection()) {
            ownedMeeting = meeting(setup);
            ownedVoter = participant(setup, ownedMeeting);
            ownedPoll = poll(setup, ownedMeeting, voting ? "OPEN" : "DRAFT");
            eligible(setup, ownedPoll, ownedMeeting, ownedVoter);
        }
        var executor = Executors.newFixedThreadPool(2);
        var start = new CyclicBarrier(2);
        Callable<String> write = () -> {
            try (Connection concurrent = dataSource.getConnection()) {
                concurrent.setAutoCommit(false);
                execute(concurrent, "SET LOCAL lock_timeout = '5s'");
                try {
                    start.await(5, TimeUnit.SECONDS);
                    if (voting) {
                        ballot(concurrent, ownedPoll, ownedVoter);
                    } else {
                        poll(concurrent, ownedMeeting, "OPEN");
                    }
                    concurrent.commit();
                    return "COMMITTED";
                } catch (SQLException failure) {
                    concurrent.rollback();
                    assertTrue(failure.getMessage().contains(voting ? "uk_meeting_poll_ballots_voter" : "uk_meeting_polls_one_open"));
                    return failure.getSQLState();
                }
            }
        };
        try {
            var one = executor.submit(write);
            var two = executor.submit(write);
            var outcomes = List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter("COMMITTED"::equals).count());
            assertEquals(1, outcomes.stream().filter("23505"::equals).count());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            try (Connection cleanup = dataSource.getConnection()) {
                execute(cleanup, "DELETE FROM meetings WHERE id = ?", ownedMeeting);
            }
        }
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private Map<String, String> legacyRows(Connection db) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : LEGACY_TABLES) {
            // Compare every original field; new additive columns have separate default/mapping assertions.
            result.put(table, string(db, "SELECT COALESCE(json_agg(to_jsonb(t) - 'creation_mode' - 'created_by_participant_id' - 'point_id' - 'revision' - 'last_webhook_at' - 'account_security_version' - 'security_version' - 'row_version' - 'content_sha256' ORDER BY id)::text, '[]') FROM " + table + " t"));
        }
        return result;
    }

    private long meeting(Connection db) throws SQLException {
        String key = UUID.randomUUID().toString().replace("-", "");
        return insert(db, """
            INSERT INTO meetings
            (meeting_code, room_name, title, start_time, end_time, status, access_type, created_at, updated_at)
            VALUES (?, ?, 'Legacy meeting', ?, ?, 'UPCOMING', 'PRIVATE', ?, ?) RETURNING id
            """, key, key, TIME, TIME.plusHours(1), TIME, TIME);
    }

    private long participant(Connection db, long meeting) throws SQLException {
        String key = UUID.randomUUID().toString();
        return insert(db, """
            INSERT INTO meeting_participants
            (meeting_id, user_id, user_account_id, participant_key, full_name, email, role, status, join_type, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'Legacy voter', 'legacy@example.test', 'MEMBER', 'INVITED', 'AUTHENTICATED', ?, ?) RETURNING id
            """, meeting, key, accountId, key, TIME, TIME);
    }

    private long poll(Connection db, long meeting, String status) throws SQLException {
        return insert(db, """
            INSERT INTO meeting_polls
            (meeting_id, question, type, status, created_by_account_id, opened_at, closed_at, cancelled_at, created_at, updated_at)
            VALUES (?, 'Approve?', 'SINGLE_CHOICE', ?, ?, ?, ?, ?, ?, ?) RETURNING id
            """, meeting, status, accountId,
            status.equals("OPEN") || status.equals("CLOSED") ? TIME : null,
            status.equals("CLOSED") ? TIME.plusMinutes(1) : null,
            status.equals("CANCELLED") ? TIME : null, TIME, TIME);
    }

    private long option(long poll, int order, String code) throws SQLException {
        return insert(connection, """
            INSERT INTO meeting_poll_options (poll_id, label, sort_order, code, created_at, updated_at)
            VALUES (?, 'Option', ?, ?, ?, ?) RETURNING id
            """, poll, order, code, TIME, TIME);
    }

    private long eligible(Connection db, long poll, long meeting, long voter) throws SQLException {
        return insert(db, """
            INSERT INTO meeting_poll_eligible_voters
            (poll_id, meeting_id, participant_id, participant_key_snapshot, full_name_snapshot, role_snapshot, snapshot_at, created_at, updated_at)
            SELECT ?, ?, id, participant_key, full_name, role, ?, ?, ? FROM meeting_participants WHERE id = ? RETURNING id
            """, poll, meeting, TIME, TIME, TIME, voter);
    }

    private long ballot(Connection db, long poll, long voter) throws SQLException {
        return insert(db, """
            INSERT INTO meeting_poll_ballots (poll_id, voter_id, submitted_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?) RETURNING id
            """, poll, voter, TIME.plusSeconds(1), TIME, TIME);
    }

    private long selection(long poll, long ballot, long option) throws SQLException {
        return insert(connection, """
            INSERT INTO meeting_poll_ballot_selections (poll_id, ballot_id, option_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?) RETURNING id
            """, poll, ballot, option, TIME, TIME);
    }

    @FunctionalInterface
    interface SqlOperation {
        void execute() throws SQLException;
    }

    private void assertConstraint(String sqlState, String constraint, SqlOperation operation) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try {
            SQLException error = assertThrows(SQLException.class, () -> {
                operation.execute();
                execute(connection, "SET CONSTRAINTS ALL IMMEDIATE");
            });
            assertEquals(sqlState, error.getSQLState());
            assertTrue(error.getMessage().contains(constraint), error.getMessage());
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }

    private static PreparedStatement statement(Connection db, String sql, Object... parameters) throws SQLException {
        PreparedStatement statement = db.prepareStatement(sql);
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
        return statement;
    }

    private static void execute(Connection db, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = statement(db, sql, parameters)) {
            statement.execute();
        }
    }

    private static long insert(Connection db, String sql, Object... parameters) throws SQLException {
        return scalar(db, sql, parameters);
    }

    private static long scalar(Connection db, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = statement(db, sql, parameters); ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static String string(Connection db, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = statement(db, sql, parameters); ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}
