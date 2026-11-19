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

/** PostgreSQL-only migration, cross-meeting integrity and concurrent duplicate tests. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "qna.postgres.url", matches = "jdbc:postgresql:.+")
class MeetingSpeakingPostgresTest {
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 8, 9, 0);
    private final String schema = "qna_p2_point_db_" + UUID.randomUUID().toString().replace("-", "");
    private DriverManagerDataSource dataSource;
    private LocalContainerEntityManagerFactoryBean factory;
    private Connection db;
    private boolean schemaCreated;
    private long meeting, otherMeeting, participant, otherParticipant, point, otherPoint, connection, otherConnection;
    private int upgraded, repeated;
    private Map<String, String> beforeUpgrade, afterUpgrade;

    @BeforeAll void upgradeOwnedLegacySchemaAndValidateMappings() throws Exception {
        dataSource = new DriverManagerDataSource(System.getProperty("qna.postgres.url"), System.getProperty("qna.postgres.username", "qna_test"), password());
        try (var admin = dataSource.getConnection()) { execute(admin, "CREATE SCHEMA " + schema); schemaCreated = true; }
        var properties = new Properties(); properties.setProperty("currentSchema", schema); dataSource.setConnectionProperties(properties);
        flyway("18").migrate();
        try (var seed = dataSource.getConnection()) {
            long legacyMeeting = meeting(seed, "SCHEDULED"), legacyParticipant = participant(seed, legacyMeeting);
            String key = UUID.randomUUID().toString();
            long legacyConnection = insert(seed, """
                INSERT INTO meeting_participant_connections(meeting_id,participant_id,attendee_id,connection_id,live_kit_identity,status,created_at,updated_at)
                VALUES (?,?,'guest:legacy',?,?,'CONNECTED',?,?) RETURNING id
                """, legacyMeeting, legacyParticipant, key, key, TIME, TIME);
            insert(seed, """
                INSERT INTO meeting_chat_messages(meeting_id,participant_id,participant_connection_id,client_message_id,message_type,content,sender_display_name,sent_at,created_at,updated_at)
                VALUES (?,?,?,?,'TEXT','Legacy chat kept','Legacy attendee',?,?,?) RETURNING id
                """, legacyMeeting, legacyParticipant, legacyConnection, key, TIME, TIME, TIME);
            insert(seed, """
                INSERT INTO meeting_qa_questions(meeting_id,participant_id,client_question_id,content,anonymous,author_display_name,status,created_at,updated_at)
                VALUES (?,?,?,'Legacy Q&A kept',false,'Legacy attendee','OPEN',?,?) RETURNING id
                """, legacyMeeting, legacyParticipant, UUID.randomUUID().toString(), TIME, TIME);
            beforeUpgrade = snapshotLegacy(seed, null);
        }
        upgraded = flyway(null).migrate().migrationsExecuted; repeated = flyway(null).migrate().migrationsExecuted;
        try (var seed = dataSource.getConnection()) {
            afterUpgrade = snapshotLegacy(seed, beforeUpgrade.keySet());
            meeting = meeting(seed, "POINT"); otherMeeting = meeting(seed, "POINT");
            participant = participant(seed, meeting); otherParticipant = participant(seed, otherMeeting);
            point = point(seed, meeting, "School A", 0); otherPoint = point(seed, otherMeeting, "Other school", 0);
            connection = connection(seed, meeting, participant, point); otherConnection = connection(seed, otherMeeting, otherParticipant, otherPoint);
        }
        factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(dataSource); factory.setPackagesToScan("vn.cusc.meetingservice.entity");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.default_schema", schema,
            "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy", "hibernate.show_sql", "false"));
        factory.afterPropertiesSet();
    }

    @BeforeEach void begin() throws SQLException { db = dataSource.getConnection(); db.setAutoCommit(false); }
    @AfterEach void rollback() throws SQLException { if (db != null) { try { db.rollback(); } finally { db.close(); db = null; } } }
    @AfterAll void dropOnlyOwnedSchema() throws SQLException {
        if (factory != null) factory.destroy();
        if (schemaCreated) {
            assertTrue(schema.matches("qna_p2_point_db_[0-9a-f]{32}"));
            try (var owned = dataSource.getConnection()) { execute(owned, "DROP SCHEMA " + schema + " CASCADE"); }
        }
    }

    @Test void v18UpgradeAddsPointAndCreationReceiptMigrationsPreservesLegacyRowsAndRepeatDoesNothing() throws SQLException {
        assertEquals(4, upgraded); assertEquals(0, repeated); assertEquals(beforeUpgrade, afterUpgrade);
        assertEquals(22, scalar(db, "SELECT count(*) FROM flyway_schema_history WHERE success")); assertNotNull(factory.getObject());
        assertEquals(1, scalar(db, "SELECT count(*) FROM meeting_participant_connections WHERE attendee_id='guest:legacy' AND point_id IS NULL AND last_webhook_at IS NULL AND account_security_version IS NULL"));
    }

    @Test void pointMeetingMustBePublicAndHaveValidSchedule() throws SQLException {
        constraint("23514", "ck_meetings_schedule", () -> execute(db, "UPDATE meetings SET access_type='PRIVATE' WHERE id=?", meeting));
        constraint("23514", "ck_meetings_schedule", () -> execute(db, "UPDATE meetings SET end_time=NULL WHERE id=?", meeting));
        constraint("23514", "ck_meetings_schedule", () -> execute(db, "UPDATE meetings SET end_time=start_time WHERE id=?", meeting));
    }

    @Test void pointNameKeyAndOrderAreUniquePerMeetingAndNamesCannotBeBlank() throws SQLException {
        constraint("23505", "uk_meeting_point_name", () -> point(db, meeting, "School A", 1));
        constraint("23505", "uk_meeting_point_order", () -> point(db, meeting, "School B", 0));
        constraint("23514", null, () -> point(db, meeting, " ", 1));
        point(db, otherMeeting, "School A", 1);
    }

    @Test void connectionCannotReferenceAnotherMeetingsPoint() throws SQLException {
        constraint("23503", "fk_meeting_connection_point", () -> execute(db, "UPDATE meeting_participant_connections SET point_id=? WHERE id=?", otherPoint, connection));
    }

    @Test void requestCannotMixParticipantPointOrConnectionAcrossMeetings() throws SQLException {
        constraint("23503", "fk_meeting_speaking_participant", () -> request(db, meeting, otherParticipant, otherConnection, point, UUID.randomUUID().toString(), "Speaker"));
        constraint("23503", "fk_meeting_speaking_point", () -> request(db, meeting, participant, connection, otherPoint, UUID.randomUUID().toString(), "Speaker"));
        constraint("23503", "fk_meeting_speaking_connection", () -> request(db, meeting, participant, otherConnection, point, UUID.randomUUID().toString(), "Speaker"));
    }

    @Test void connectionPointMustMatchRequestPointEvenWithinSameMeeting() throws SQLException {
        long alternate = point(db, meeting, "School B", 1);
        constraint("23503", "fk_meeting_speaking_connection", () -> request(db, meeting, participant, connection, alternate, UUID.randomUUID().toString(), "Speaker"));
    }

    @Test void retryKeyCannotCreateAnotherReceiptAfterTheFirstEntryIsFinished() throws SQLException {
        String id = UUID.randomUUID().toString(); long original = request(db, meeting, participant, connection, point, id, "Speaker");
        execute(db, "UPDATE meeting_speaking_requests SET status='FINISHED',approved_at=?,approved_by_display_name='Owner',finished_at=?,finished_by_display_name='Owner',version=2 WHERE id=?", TIME.plusMinutes(1), TIME.plusMinutes(2), original);
        constraint("23505", "uk_meeting_speaking_retry", () -> request(db, meeting, participant, connection, point, id, "Changed speaker"));
        long another = participant(db, meeting), anotherConnection = connection(db, meeting, another, point);
        request(db, meeting, another, anotherConnection, point, id, "Speaker");
    }

    @Test void sameSpeakerHasOnlyOneActiveEntryButDifferentSharedSpeakersCanQueue() throws SQLException {
        long original = request(db, meeting, participant, connection, point, UUID.randomUUID().toString(), "Speaker");
        constraint("23505", "uk_meeting_speaking_active", () -> request(db, meeting, participant, connection, point, UUID.randomUUID().toString(), "Speaker"));
        request(db, meeting, participant, connection, point, UUID.randomUUID().toString(), "Other speaker");
        execute(db, "UPDATE meeting_speaking_requests SET status='CANCELLED',finished_at=?,finished_by_display_name='Owner',version=1 WHERE id=?", TIME.plusMinutes(1), original);
        request(db, meeting, participant, connection, point, UUID.randomUUID().toString(), "Speaker");
    }

    @ParameterizedTest @ValueSource(strings = {
        "status='SPEAKING'", "status='FINISHED'", "status='CANCELLED'", "status='UNKNOWN'", "version=-1",
        "approved_at=requested_at - INTERVAL '1 second'", "speaker_name=' '", "point_name=' '",
        "status='FINISHED',approved_at=requested_at,approved_by_display_name='Owner',finished_at=requested_at - INTERVAL '1 second',finished_by_display_name='Owner'"
    })
    void invalidStateTimelineAndBlankSnapshotsCannotPersist(String change) throws SQLException {
        long original = request(db, meeting, participant, connection, point, UUID.randomUUID().toString(), "Speaker");
        constraint("23514", null, () -> execute(db, "UPDATE meeting_speaking_requests SET " + change + " WHERE id=?", original));
    }

    @Test void standaloneParticipantOrConnectionDeletionCannotOrphanHistoryButMeetingCascadeDeletesAggregate() throws SQLException {
        request(db, meeting, participant, connection, point, UUID.randomUUID().toString(), "Speaker");
        constraint("23503", null, () -> execute(db, "DELETE FROM meeting_participants WHERE id=?", participant));
        constraint("23503", "fk_meeting_speaking_connection", () -> execute(db, "DELETE FROM meeting_participant_connections WHERE id=?", connection));
        execute(db, "DELETE FROM meetings WHERE id=?", meeting); execute(db, "SET CONSTRAINTS ALL IMMEDIATE");
        for (String table : List.of("meeting_points", "meeting_speaking_requests", "meeting_participant_connections")) assertEquals(0, scalar(db, "SELECT count(*) FROM " + table + " WHERE meeting_id=?", meeting));
    }

    @Test void concurrentSameRetryKeyCommitsOneRow() throws Exception { concurrentUnique(true); }
    @Test void concurrentDifferentRetryKeysForSameSpeakerCommitOneActiveRow() throws Exception { concurrentUnique(false); }

    @Test void creationReceiptRetryKeyIsUniquePerCreatorAndEachLiveMeetingHasOneReceipt() throws SQLException {
        long owner = account(db), anotherOwner = account(db); String key = UUID.randomUUID().toString();
        creationReceipt(db, owner, meeting, key);
        constraint("23505", "uk_point_creation_retry", () -> creationReceipt(db, owner, otherMeeting, key));
        constraint("23505", "uk_point_creation_meeting", () -> creationReceipt(db, anotherOwner, meeting, UUID.randomUUID().toString()));
        creationReceipt(db, anotherOwner, otherMeeting, key);
        assertEquals(2, scalar(db, "SELECT count(*) FROM meeting_point_creation_receipts WHERE request_id=?", key));
    }

    @Test void meetingDeletionRetainsCreationTombstoneAndDoesNotReleaseOriginalRetryKey() throws SQLException {
        long owner = account(db); String key = UUID.randomUUID().toString();
        long receipt = creationReceipt(db, owner, meeting, key);
        execute(db, "DELETE FROM meetings WHERE id=?", meeting); execute(db, "SET CONSTRAINTS ALL IMMEDIATE");
        assertEquals(1, scalar(db, "SELECT count(*) FROM meeting_point_creation_receipts WHERE id=? AND meeting_id IS NULL AND request_id=?", receipt, key));
        constraint("23505", "uk_point_creation_retry", () -> creationReceipt(db, owner, otherMeeting, key));
    }

    @Test void concurrentDuplicateCreationReceiptsCommitExactlyOneDatabaseLedgerRow() throws Exception {
        long owner, ownMeeting;
        try (var seed = dataSource.getConnection()) { owner = account(seed); ownMeeting = meeting(seed, "POINT"); }
        String key = UUID.randomUUID().toString(); var barrier = new CyclicBarrier(2); var pool = Executors.newFixedThreadPool(2);
        Callable<String> write = () -> {
            try (var concurrent = dataSource.getConnection()) {
                concurrent.setAutoCommit(false); execute(concurrent, "SET LOCAL lock_timeout='5s'"); barrier.await(5, TimeUnit.SECONDS);
                try { creationReceipt(concurrent, owner, ownMeeting, key); concurrent.commit(); return "COMMITTED"; }
                catch (SQLException failure) { concurrent.rollback(); return failure.getSQLState(); }
            }
        };
        try {
            var first = pool.submit(write); var second = pool.submit(write);
            var outcomes = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter("COMMITTED"::equals).count()); assertEquals(1, outcomes.stream().filter("23505"::equals).count());
            assertEquals(1, scalar(db, "SELECT count(*) FROM meeting_point_creation_receipts WHERE created_by_account_id=? AND request_id=?", owner, key));
        } finally {
            pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            try (var cleanup = dataSource.getConnection()) {
                execute(cleanup, "DELETE FROM meeting_point_creation_receipts WHERE created_by_account_id=? AND request_id=?", owner, key);
                execute(cleanup, "DELETE FROM meetings WHERE id=?", ownMeeting); execute(cleanup, "DELETE FROM user_accounts WHERE id=?", owner);
            }
        }
    }
    private void concurrentUnique(boolean sameRetry) throws Exception {
        long ownMeeting, ownParticipant, ownPoint, ownConnection;
        try (var seed = dataSource.getConnection()) {
            ownMeeting = meeting(seed, "POINT"); ownParticipant = participant(seed, ownMeeting); ownPoint = point(seed, ownMeeting, "Race school", 0); ownConnection = connection(seed, ownMeeting, ownParticipant, ownPoint);
        }
        String retry = UUID.randomUUID().toString(); var barrier = new CyclicBarrier(2); var pool = Executors.newFixedThreadPool(2);
        Callable<String> write = () -> {
            try (var concurrent = dataSource.getConnection()) {
                concurrent.setAutoCommit(false); execute(concurrent, "SET LOCAL lock_timeout='5s'"); barrier.await(5, TimeUnit.SECONDS);
                try {
                    request(concurrent, ownMeeting, ownParticipant, ownConnection, ownPoint, sameRetry ? retry : UUID.randomUUID().toString(), "Concurrent speaker");
                    concurrent.commit(); return "COMMITTED";
                } catch (SQLException failure) { concurrent.rollback(); return failure.getSQLState(); }
            }
        };
        try {
            var first = pool.submit(write); var second = pool.submit(write); var outcomes = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter("COMMITTED"::equals).count()); assertEquals(1, outcomes.stream().filter("23505"::equals).count());
            assertEquals(1, scalar(db, "SELECT count(*) FROM meeting_speaking_requests WHERE meeting_id=?", ownMeeting));
        } finally {
            pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            try (var cleanup = dataSource.getConnection()) { execute(cleanup, "DELETE FROM meetings WHERE id=?", ownMeeting); }
        }
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration");
        if (target != null) config.target(target); return config.load();
    }
    private Map<String, String> snapshotLegacy(Connection connection, Set<String> tables) throws SQLException {
        if (tables == null) {
            tables = new LinkedHashSet<>();
            try (var query = prepare(connection, "SELECT tablename FROM pg_tables WHERE schemaname=? AND tablename<>'flyway_schema_history' ORDER BY tablename", schema); var rows = query.executeQuery()) { while (rows.next()) tables.add(rows.getString(1)); }
        }
        var result = new LinkedHashMap<String, String>();
        for (String table : tables) {
            assertTrue(table.matches("[a-z_]+"));
            // Compare every original field; new additive columns have separate default/mapping assertions.
            try (var query = prepare(connection, "SELECT COALESCE(json_agg(to_jsonb(t) - 'point_id' - 'revision' - 'last_webhook_at' - 'account_security_version' - 'security_version' - 'row_version' - 'content_sha256' ORDER BY t::text)::text,'[]') FROM " + table + " t"); var rows = query.executeQuery()) {
                assertTrue(rows.next()); result.put(table, rows.getString(1));
            }
        }
        return result;
    }
    private long meeting(Connection connection, String mode) throws SQLException {
        String key = UUID.randomUUID().toString().replace("-", "");
        return insert(connection, "INSERT INTO meetings(meeting_code,room_name,title,start_time,end_time,status,access_type,creation_mode,created_at,updated_at) VALUES (?,?,'Isolated point verification',?,?,'IN_PROGRESS','PUBLIC',?,?,?) RETURNING id", key, key, TIME, TIME.plusHours(2), mode, TIME, TIME);
    }
    private long participant(Connection connection, long meetingId) throws SQLException {
        String key = UUID.randomUUID().toString();
        return insert(connection, "INSERT INTO meeting_participants(meeting_id,user_id,full_name,email,role,status,participant_key,join_type,created_at,updated_at) VALUES (?,?,'Participant',?,'MEMBER','JOINED',?,'AUTHENTICATED',?,?) RETURNING id", meetingId, key, key + "@example.test", key, TIME, TIME);
    }
    private long point(Connection connection, long meetingId, String name, int order) throws SQLException {
        return insert(connection, "INSERT INTO meeting_points(meeting_id,name,name_key,sort_order,created_at,updated_at) VALUES (?,?,?,?,?,?) RETURNING id", meetingId, name, name.strip().toLowerCase(Locale.ROOT), order, TIME, TIME);
    }
    private long account(Connection connection) throws SQLException {
        String key = UUID.randomUUID().toString();
        return insert(connection, "INSERT INTO user_accounts(public_id,normalized_email,full_name,account_status,system_role,created_at,updated_at) VALUES (?,?,'Receipt owner','ACTIVE','USER',?,?) RETURNING id", key, key + "@example.test", TIME, TIME);
    }
    private long creationReceipt(Connection connection, long owner, long meetingId, String key) throws SQLException {
        return insert(connection, "INSERT INTO meeting_point_creation_receipts(created_by_account_id,meeting_id,request_id,input_hash,receipt_json,created_at,updated_at) VALUES (?,?,?,?,'{}',?,?) RETURNING id", owner, meetingId, key, "a".repeat(64), TIME, TIME);
    }
    private long connection(Connection connection, long meetingId, long participantId, long pointId) throws SQLException {
        String key = UUID.randomUUID().toString();
        return insert(connection, "INSERT INTO meeting_participant_connections(meeting_id,participant_id,point_id,attendee_id,connection_id,live_kit_identity,status,created_at,updated_at) VALUES (?,?,?,'guest:test',?,?,'CONNECTED',?,?) RETURNING id", meetingId, participantId, pointId, key, key, TIME, TIME);
    }
    private long request(Connection connection, long meetingId, long participantId, long connectionId, long pointId, String retry, String speaker) throws SQLException {
        return insert(connection, """
            INSERT INTO meeting_speaking_requests(meeting_id,participant_id,participant_connection_id,point_id,request_id,submitted_speaker_name,speaker_name,speaker_key,point_name,requested_by_display_name,requested_at,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?,'Point snapshot','Requester snapshot',?,?,?) RETURNING id
            """, meetingId, participantId, connectionId, pointId, retry, speaker, speaker, speaker.strip().toLowerCase(Locale.ROOT), TIME, TIME, TIME);
    }
    private void constraint(String state, String name, SqlWrite action) throws SQLException {
        var savepoint = db.setSavepoint();
        try {
            SQLException error = assertThrows(SQLException.class, () -> { action.run(); execute(db, "SET CONSTRAINTS ALL IMMEDIATE"); });
            assertEquals(state, error.getSQLState()); if (name != null) assertTrue(error.getMessage().contains(name), error.getMessage());
        } finally { db.rollback(savepoint); db.releaseSavepoint(savepoint); }
    }
    @FunctionalInterface private interface SqlWrite { void run() throws SQLException; }
    private static String password() { return Objects.requireNonNull(System.getenv("QNA_TEST_DB_PASSWORD"), "Set QNA_TEST_DB_PASSWORD only for selected isolated database."); }
    private static PreparedStatement prepare(Connection connection, String sql, Object... values) throws SQLException { var statement = connection.prepareStatement(sql); for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]); return statement; }
    private static void execute(Connection connection, String sql, Object... values) throws SQLException { try (var statement = prepare(connection, sql, values)) { statement.execute(); } }
    private static long insert(Connection connection, String sql, Object... values) throws SQLException { return scalar(connection, sql, values); }
    private static long scalar(Connection connection, String sql, Object... values) throws SQLException { try (var statement = prepare(connection, sql, values); var rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); } }
}
