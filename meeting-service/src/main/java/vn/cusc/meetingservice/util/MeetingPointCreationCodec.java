package vn.cusc.meetingservice.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import vn.cusc.meetingservice.dto.request.AddMeetingParticipantRequest;
import vn.cusc.meetingservice.dto.request.CreateMeetingRequest;
import vn.cusc.meetingservice.dto.response.MeetingResponse;

/** Stable version-one input fingerprint and token-free creation receipt. */
public final class MeetingPointCreationCodec {
    private static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private MeetingPointCreationCodec() { }

    private record ParticipantInput(String identity, String fullName, String role) { }
    private record CreationInput(int version, String title, String description, String startTime, String endTime,
        String accessType, String creationMode, List<String> points, List<ParticipantInput> participants) { }

    public static List<AddMeetingParticipantRequest> participants(List<AddMeetingParticipantRequest> rows) {
        if (rows == null) return List.of();
        return rows.stream().map(row -> new AddMeetingParticipantRequest(trim(row.userPublicId()),
            trim(row.invitedEmail()) == null ? null : trim(row.invitedEmail()).toLowerCase(Locale.ROOT),
            trim(row.fullName()), row.role())).toList();
    }
    public static String fingerprint(CreateMeetingRequest request) {
        var roster = participants(request.participants()).stream().map(row -> new ParticipantInput(
            row.userPublicId() != null ? "account:" + row.userPublicId() : "email:" + row.invitedEmail(),
            row.fullName(), row.role().name())).sorted(Comparator.comparing(ParticipantInput::identity)
                .thenComparing(ParticipantInput::role).thenComparing(ParticipantInput::fullName, Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
        var mode = request.creationMode() == null ? vn.cusc.meetingservice.model.enums.MeetingCreationMode.SCHEDULED : request.creationMode();
        var input = new CreationInput(1, trim(request.title()), request.description(),
            request.startTime() == null ? null : request.startTime().toString(),
            request.endTime() == null ? null : request.endTime().toString(), request.accessType().name(), mode.name(),
            request.pointNames() == null ? List.of() : request.pointNames().stream().map(String::trim).toList(), roster);
        try { return hash(JSON.writeValueAsBytes(input)); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Không thể xác nhận thông tin tạo cuộc họp.", error); }
    }
    public static String roomName(String creatorPublicId, String requestId) {
        return "meeting-point-" + hash((creatorPublicId + "|" + requestId).getBytes(StandardCharsets.UTF_8));
    }
    public static String encode(MeetingResponse receipt) {
        try { return JSON.writeValueAsString(receipt); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Không thể lưu kết quả tạo cuộc họp.", error); }
    }
    public static MeetingResponse decode(String receipt) {
        try { return JSON.readValue(receipt, MeetingResponse.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Không thể đọc kết quả tạo cuộc họp đã lưu.", error); }
    }
    private static String hash(byte[] input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is unavailable.", error); }
    }
    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
