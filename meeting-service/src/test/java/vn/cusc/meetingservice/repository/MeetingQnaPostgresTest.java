package vn.cusc.meetingservice.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** Opt-in real PostgreSQL integrity/upgrade checks in a fresh owned schema. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "qna.postgres.url", matches = "jdbc:postgresql:.+")
class MeetingQnaPostgresTest {
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 6, 9, 0);
    private final String schema = "qna_p2_db_" + UUID.randomUUID().toString().replace("-", "");
    private DriverManagerDataSource dataSource;
    private LocalContainerEntityManagerFactoryBean factory;
    private Connection db;
    private boolean schemaCreated;
    private long account;
    private long meeting;
    private long author;
    private long otherMeeting;
    private long otherAuthor;
    private long question;
    private int upgrades;
    private int repeated;
    private Map<String, String> beforeUpgrade;
    private Map<String, String> afterUpgrade;

    @BeforeAll
    void upgradeOwnedV14SchemaAndValidateAllEntityMappings() throws Exception {
        dataSource = new DriverManagerDataSource(System.getProperty("qna.postgres.url"),
            System.getProperty("qna.postgres.username", "qna_test"), password());
        try (Connection admin = dataSource.getConnection()) {
            execute(admin, "CREATE SCHEMA " + schema);
            schemaCreated = true;
        }
        var properties = new Properties(); properties.setProperty("currentSchema", schema);
        dataSource.setConnectionProperties(properties);
        flyway("14").migrate();
        try (Connection seed = dataSource.getConnection()) {
            account = insert(seed, """
                INSERT INTO user_accounts(public_id, normalized_email, full_name, account_status, system_role, created_at, updated_at)
                VALUES (?, ?, 'Legacy attendee', 'ACTIVE', 'USER', ?, ?) RETURNING id
                """, UUID.randomUUID().toString(), UUID.randomUUID() + "@example.test", TIME, TIME);
            meeting = meeting(seed); author = participant(seed, meeting);
            long connection = insert(seed, """
                INSERT INTO meeting_participant_connections(meeting_id, participant_id, attendee_id, connection_id,
                    live_kit_identity, joined_at, status, created_at, updated_at)
                VALUES (?, ?, 'account:legacy', ?, ?, ?, 'CONNECTED', ?, ?) RETURNING id
                """, meeting, author, UUID.randomUUID().toString(), UUID.randomUUID().toString(), TIME, TIME, TIME);
            insert(seed, """
                INSERT INTO meeting_chat_messages(meeting_id, participant_id, participant_connection_id,
                    client_message_id, message_type, content, sender_display_name, sent_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'TEXT', 'Legacy chat retained', 'Legacy attendee', ?, ?, ?) RETURNING id
                """, meeting, author, connection, UUID.randomUUID().toString(), TIME, TIME, TIME);
            long poll = insert(seed, """
                INSERT INTO meeting_polls(meeting_id, question, type, status, created_by_account_id, created_at, updated_at)
                VALUES (?, 'Legacy poll retained', 'SINGLE_CHOICE', 'DRAFT', ?, ?, ?) RETURNING id
                """, meeting, account, TIME, TIME);
            insert(seed, """
                INSERT INTO meeting_poll_options(poll_id, label, sort_order, created_at, updated_at)
                VALUES (?, 'Legacy option', 0, ?, ?) RETURNING id
                """, poll, TIME, TIME);
            insert(seed, """
                INSERT INTO meeting_minutes(meeting_id, version, minutes_json, generated_at, status, source, created_at, updated_at)
                VALUES (?, 1, '{"mainContent":"Legacy minutes retained"}', ?, 'DRAFT', 'AI_GENERATED', ?, ?) RETURNING id
                """, meeting, TIME, TIME, TIME);
            beforeUpgrade = snapshotLegacy(seed, null);
        }
        upgrades = flyway(null).migrate().migrationsExecuted;
        repeated = flyway(null).migrate().migrationsExecuted;
        try (Connection seed = dataSource.getConnection()) {
            afterUpgrade = snapshotLegacy(seed, beforeUpgrade.keySet());
            otherMeeting = meeting(seed); otherAuthor = participant(seed, otherMeeting);
            question = question(seed, meeting, author, UUID.randomUUID().toString(), true);
        }
        factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(dataSource);
        factory.setPackagesToScan("vn.cusc.meetingservice.entity");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.default_schema", schema,
            "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
            "hibernate.show_sql", "false"));
        factory.afterPropertiesSet();
    }

    @BeforeEach void begin() throws SQLException { db = dataSource.getConnection(); db.setAutoCommit(false); }
    @AfterEach void rollback() throws SQLException {
        if (db != null) { try { db.rollback(); } finally { db.close(); db = null; } }
    }
    @AfterAll void dropOnlyOwnedSchema() throws SQLException {
        if (factory != null) factory.destroy();
        if (schemaCreated) {
            assertTrue(schema.matches("qna_p2_db_[0-9a-f]{32}"));
            try (Connection owned = dataSource.getConnection()) { execute(owned, "DROP SCHEMA " + schema + " CASCADE"); }
        }
    }

    @Test void upgradeV14AddsNewMigrationsAndPreservesEveryLegacyTableRow() throws SQLException {
        assertEquals(8, upgrades); assertEquals(0, repeated);
        assertEquals(beforeUpgrade, afterUpgrade);
        assertEquals(22, scalar(db, "SELECT count(*) FROM flyway_schema_history WHERE success"));
        assertEquals(1, scalar(db, "SELECT count(*) FROM user_accounts WHERE security_version=0 AND row_version=0"));
        assertEquals(1, scalar(db, "SELECT count(*) FROM meeting_minutes WHERE revision=0"));
        assertEquals(1, scalar(db, "SELECT count(*) FROM meeting_participant_connections WHERE attendee_id='account:legacy' AND last_webhook_at IS NULL AND account_security_version IS NULL"));
        assertEquals(3, scalar(db, "SELECT count(*) FROM pg_tables WHERE schemaname = ? AND tablename LIKE 'meeting_qa_%'", schema));
        assertNotNull(factory.getObject());
    }

    @Test void defaultSettingsAreEnabledAnonymousAndUnmoderatedAndOnePerMeeting() throws SQLException {
        insert(db, "INSERT INTO meeting_qa_settings(meeting_id, created_at, updated_at) VALUES (?, ?, ?) RETURNING id", meeting, TIME, TIME);
        assertEquals(1, scalar(db, "SELECT count(*) FROM meeting_qa_settings WHERE meeting_id=? AND enabled AND anonymous_allowed AND NOT moderation_enabled AND version=0", meeting));
        constraint("23505", "uk_meeting_qa_settings_meeting", () ->
            insert(db, "INSERT INTO meeting_qa_settings(meeting_id, created_at, updated_at) VALUES (?, ?, ?) RETURNING id", meeting, TIME, TIME));
    }

    @Test void retryKeyIsUniquePerAuthorAndMeetingWithoutConflatingDifferentParticipants() throws SQLException {
        String retry = UUID.randomUUID().toString(); question(db, meeting, author, retry, false);
        constraint("23505", "uk_meeting_qa_question_retry", () -> question(db, meeting, author, retry, false));
        long another = participant(db, meeting);
        question(db, meeting, another, retry, false);
        assertEquals(2, scalar(db, "SELECT count(*) FROM meeting_qa_questions WHERE client_question_id=?", retry));
    }

    @Test void questionCannotReferToParticipantFromAnotherMeetingEvenWithDeferredForeignKey() throws SQLException {
        constraint("23503", "fk_meeting_qa_question_participant", () ->
            question(db, meeting, otherAuthor, UUID.randomUUID().toString(), true));
    }

    @Test void upvoteCannotMixQuestionOrParticipantAcrossMeetings() throws SQLException {
        constraint("23503", "fk_meeting_qa_upvote_question", () -> upvote(db, otherMeeting, question, otherAuthor));
        constraint("23503", "fk_meeting_qa_upvote_participant", () -> upvote(db, meeting, question, otherAuthor));
    }

    @Test void anonymousIdentityIsNotStoredInDisplaySnapshotAndNamedQuestionRequiresName() throws SQLException {
        assertEquals(1, scalar(db, "SELECT count(*) FROM meeting_qa_questions WHERE id=? AND anonymous AND author_display_name IS NULL", question));
        constraint("23514", "ck_meeting_qa_anonymous_author", () -> execute(db,
            "UPDATE meeting_qa_questions SET author_display_name='Secret identity' WHERE id=?", question));
        constraint("23514", "ck_meeting_qa_anonymous_author", () -> execute(db,
            "UPDATE meeting_qa_questions SET anonymous=FALSE WHERE id=?", question));
    }

    @ParameterizedTest
    @ValueSource(strings = {"content='  '", "status='INVALID'", "upvote_count=-1", "version=-1", "status='ANSWERED', answered_at=NULL"})
    void rejectsInvalidQuestionStateOnRealPostgres(String change) throws SQLException {
        constraint("23514", null, () -> execute(db, "UPDATE meeting_qa_questions SET " + change + " WHERE id=?", question));
    }

    @Test void contentLengthIsBoundedByDatabase() throws SQLException {
        constraint("22001", null, () -> execute(db, "UPDATE meeting_qa_questions SET content=? WHERE id=?", "x".repeat(2001), question));
    }

    @Test void writtenAnswersKeepLegacyRowsValidAndRequireResponderAndTimestamp() throws SQLException {
        execute(db, "UPDATE meeting_qa_questions SET status='ANSWERED', answered_at=? WHERE id=?", TIME, question);
        assertEquals(1, scalar(db, "SELECT count(*) FROM meeting_qa_questions WHERE id=? AND answer IS NULL", question));
        constraint("23514", "ck_meeting_qa_written_answer", () -> execute(db,
            "UPDATE meeting_qa_questions SET answer='Text' WHERE id=?", question));
        constraint("23514", "ck_meeting_qa_written_answer", () -> execute(db,
            "UPDATE meeting_qa_questions SET answer='  ', answer_author_display_name='Responder' WHERE id=?", question));
        constraint("22001", null, () -> execute(db,
            "UPDATE meeting_qa_questions SET answer=?, answer_author_display_name='Responder' WHERE id=?", "x".repeat(5001), question));
        execute(db, "UPDATE meeting_qa_questions SET answer='Persisted answer', answer_author_display_name='Responder' WHERE id=?", question);
        assertEquals(1, scalar(db, "SELECT count(*) FROM meeting_qa_questions WHERE id=? AND answer='Persisted answer'", question));
    }

    @Test void oneUpvotePerQuestionAndParticipantIsDatabaseEnforced() throws SQLException {
        upvote(db, meeting, question, author);
        constraint("23505", "uk_meeting_qa_upvote", () -> upvote(db, meeting, question, author));
    }

    @Test void deletingAnAuthorAloneCannotOrphanQuestionsButMeetingCascadeDeletesQaAggregate() throws SQLException {
        constraint("23503", "fk_meeting_qa_question_participant", () -> execute(db, "DELETE FROM meeting_participants WHERE id=?", author));
        insert(db, "INSERT INTO meeting_qa_settings(meeting_id, created_at, updated_at) VALUES (?, ?, ?) RETURNING id", meeting, TIME, TIME);
        upvote(db, meeting, question, author);
        execute(db, "DELETE FROM meetings WHERE id=?", meeting);
        execute(db, "SET CONSTRAINTS ALL IMMEDIATE");
        assertEquals(0, scalar(db, "SELECT count(*) FROM meeting_qa_questions WHERE meeting_id=?", meeting));
        assertEquals(0, scalar(db, "SELECT count(*) FROM meeting_qa_upvotes WHERE meeting_id=?", meeting));
        assertEquals(0, scalar(db, "SELECT count(*) FROM meeting_qa_settings WHERE meeting_id=?", meeting));
    }

    @Test void concurrentSameRetryKeyCommitsExactlyOneQuestion() throws Exception { concurrentUnique(false); }
    @Test void concurrentSameParticipantCommitsExactlyOneUpvote() throws Exception { concurrentUnique(true); }

    private void concurrentUnique(boolean voting) throws Exception {
        long ownMeeting; long ownAuthor; long ownQuestion;
        try (Connection seed = dataSource.getConnection()) {
            ownMeeting = meeting(seed); ownAuthor = participant(seed, ownMeeting);
            ownQuestion = question(seed, ownMeeting, ownAuthor, UUID.randomUUID().toString(), true);
        }
        String retry = UUID.randomUUID().toString(); var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        Callable<String> write = () -> {
            try (Connection concurrent = dataSource.getConnection()) {
                concurrent.setAutoCommit(false); execute(concurrent, "SET LOCAL lock_timeout='5s'");
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    if (voting) upvote(concurrent, ownMeeting, ownQuestion, ownAuthor);
                    else question(concurrent, ownMeeting, ownAuthor, retry, true);
                    concurrent.commit(); return "COMMITTED";
                } catch (SQLException failure) { concurrent.rollback(); return failure.getSQLState(); }
            }
        };
        try {
            var first = pool.submit(write); var second = pool.submit(write);
            var outcomes = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter("COMMITTED"::equals).count());
            assertEquals(1, outcomes.stream().filter("23505"::equals).count());
        } finally {
            pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            try (Connection cleanup = dataSource.getConnection()) { execute(cleanup, "DELETE FROM meetings WHERE id=?", ownMeeting); }
        }
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }
    private Map<String, String> snapshotLegacy(Connection connection, Set<String> tables) throws SQLException {
        if (tables == null) {
            tables = new LinkedHashSet<>();
            try (var statement = prepare(connection, "SELECT tablename FROM pg_tables WHERE schemaname=? AND tablename<>'flyway_schema_history' ORDER BY tablename", schema);
                 var rows = statement.executeQuery()) { while (rows.next()) tables.add(rows.getString(1)); }
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : tables) {
            assertTrue(table.matches("[a-z_]+"));
            // Compare every original field; new additive columns have separate default/mapping assertions.
            try (var statement = prepare(connection, "SELECT COALESCE(json_agg(to_jsonb(t) - 'creation_mode' - 'created_by_participant_id' - 'point_id' - 'revision' - 'last_webhook_at' - 'account_security_version' - 'security_version' - 'row_version' - 'content_sha256' ORDER BY t::text)::text,'[]') FROM " + table + " t"); var rows = statement.executeQuery()) {
                assertTrue(rows.next()); result.put(table, rows.getString(1));
            }
        }
        return result;
    }
    private long meeting(Connection connection) throws SQLException {
        String key = UUID.randomUUID().toString().replace("-", "");
        return insert(connection, """
            INSERT INTO meetings(meeting_code, room_name, title, start_time, end_time, status, access_type, created_at, updated_at)
            VALUES (?, ?, 'Isolated Q&A verification', ?, ?, 'IN_PROGRESS', 'PUBLIC', ?, ?) RETURNING id
            """, key, key, TIME, TIME.plusHours(1), TIME, TIME);
    }
    private long participant(Connection connection, long meetingId) throws SQLException {
        String key = UUID.randomUUID().toString();
        return insert(connection, """
            INSERT INTO meeting_participants(meeting_id, user_id, full_name, email, role, status, participant_key, join_type, created_at, updated_at)
            VALUES (?, ?, 'Participant', ?, 'MEMBER', 'JOINED', ?, 'AUTHENTICATED', ?, ?) RETURNING id
            """, meetingId, key, key + "@example.test", key, TIME, TIME);
    }
    private long question(Connection connection, long meetingId, long participantId, String retry, boolean anonymous) throws SQLException {
        return insert(connection, """
            INSERT INTO meeting_qa_questions(meeting_id, participant_id, client_question_id, content, anonymous, author_display_name, created_at, updated_at)
            VALUES (?, ?, ?, 'A persisted question', ?, ?, ?, ?) RETURNING id
            """, meetingId, participantId, retry, anonymous, anonymous ? null : "Participant", TIME, TIME);
    }
    private long upvote(Connection connection, long meetingId, long questionId, long participantId) throws SQLException {
        return insert(connection, """
            INSERT INTO meeting_qa_upvotes(meeting_id, question_id, participant_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?) RETURNING id
            """, meetingId, questionId, participantId, TIME, TIME);
    }
    private void constraint(String state, String name, SqlWrite action) throws SQLException {
        Savepoint savepoint = db.setSavepoint();
        try {
            SQLException error = assertThrows(SQLException.class, () -> { action.run(); execute(db, "SET CONSTRAINTS ALL IMMEDIATE"); });
            assertEquals(state, error.getSQLState());
            if (name != null) assertTrue(error.getMessage().contains(name), error.getMessage());
        } finally { db.rollback(savepoint); db.releaseSavepoint(savepoint); }
    }
    private static String password() { return Objects.requireNonNull(System.getenv("QNA_TEST_DB_PASSWORD"), "Set QNA_TEST_DB_PASSWORD only for the selected isolated database."); }
    @FunctionalInterface private interface SqlWrite { void run() throws SQLException; }
    private static PreparedStatement prepare(Connection connection, String sql, Object... values) throws SQLException {
        var statement = connection.prepareStatement(sql); for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]); return statement;
    }
    private static void execute(Connection connection, String sql, Object... values) throws SQLException { try (var statement = prepare(connection, sql, values)) { statement.execute(); } }
    private static long insert(Connection connection, String sql, Object... values) throws SQLException { return scalar(connection, sql, values); }
    private static long scalar(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = prepare(connection, sql, values); var rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
    }
}
