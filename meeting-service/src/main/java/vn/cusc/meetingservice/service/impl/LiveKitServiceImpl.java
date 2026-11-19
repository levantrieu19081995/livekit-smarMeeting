package vn.cusc.meetingservice.service.impl;

import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanSubscribe;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import io.livekit.server.RoomServiceClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Duration;
import java.util.Date;
import org.springframework.stereotype.Service;
import retrofit2.Response;
import livekit.LivekitModels;
import vn.cusc.meetingservice.config.LiveKitProperties;
import vn.cusc.meetingservice.dto.response.LiveKitRoomResponse;
import vn.cusc.meetingservice.exception.LiveKitOperationException;
import vn.cusc.meetingservice.service.LiveKitService;

@Service
public class LiveKitServiceImpl implements LiveKitService {

    private final LiveKitProperties liveKitProperties;
    private final RoomServiceClient roomServiceClient;

    public LiveKitServiceImpl(LiveKitProperties liveKitProperties) {
        this.liveKitProperties = liveKitProperties;
        this.roomServiceClient = RoomServiceClient.createClient(
            liveKitProperties.getUrl(),
            liveKitProperties.getApiKey(),
            liveKitProperties.getApiSecret(),
            () -> new okhttp3.OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(liveKitProperties.getRequestTimeoutSeconds()))
                .readTimeout(Duration.ofSeconds(liveKitProperties.getRequestTimeoutSeconds()))
                .callTimeout(Duration.ofSeconds(liveKitProperties.getRequestTimeoutSeconds())).build()
        );
    }

    @Override
    public LiveKitRoomResponse createRoom(String roomName) {
        try {
            Response<LivekitModels.Room> response = roomServiceClient.createRoom(
                roomName,
                liveKitProperties.getEmptyTimeout(),
                liveKitProperties.getDepartureTimeout()
            ).execute();
            LivekitModels.Room room = requireBody(response, "Khong nhan duoc room sau khi tao");
            return toResponse(room);
        } catch (Exception exception) {
            throw new LiveKitOperationException("Khong the tao LiveKit room " + roomName, exception);
        }
    }

    @Override
    public List<LiveKitRoomResponse> listRooms() {
        try {
            Response<List<LivekitModels.Room>> response = roomServiceClient.listRooms().execute();
            return requireBody(response, "Khong nhan duoc danh sach room").stream().map(this::toResponse).toList();
        } catch (Exception exception) {
            throw new LiveKitOperationException("Khong the lay danh sach LiveKit rooms", exception);
        }
    }

    @Override
    public Optional<LiveKitRoomResponse> getRoom(String roomName) {
        try {
            return requireBody(roomServiceClient.listRooms(List.of(roomName)).execute(), "Khong nhan duoc room")
                .stream().filter(room -> roomName.equals(room.getName())).map(this::toResponse).findFirst();
        } catch (Exception error) { throw new LiveKitOperationException("Khong the kiem tra LiveKit room", error); }
    }

    @Override
    public void deleteRoom(String roomName) {
        try {
            requireRemoval(roomServiceClient.deleteRoom(roomName).execute());
        } catch (Exception exception) {
            throw new LiveKitOperationException("Khong the xoa LiveKit room " + roomName, exception);
        }
    }

    @Override
    public LiveKitRoomResponse ensureRoomExists(String roomName) {
        return getRoom(roomName).orElseGet(() -> createRoom(roomName));
    }

    @Override
    public String generateJoinToken(String roomName, String identity, String participantName, Map<String, String> metadata,
                                    boolean canPublish, boolean canSubscribe) {
        try {
            AccessToken accessToken = new AccessToken(liveKitProperties.getApiKey(), liveKitProperties.getApiSecret());
            accessToken.setExpiration(new Date(System.currentTimeMillis() + liveKitProperties.getJoinTokenTtlSeconds() * 1000));
            accessToken.setIdentity(identity);
            accessToken.setName(participantName);
             accessToken.setMetadata(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metadata));
            accessToken.addGrants(
                new RoomJoin(true),
                new RoomName(roomName),
                new CanPublish(canPublish),
                new CanSubscribe(canSubscribe),
                new CanPublishData(true)
            );
            return accessToken.toJwt();
        } catch (Exception exception) {
            throw new LiveKitOperationException("Khong the tao token join cho room " + roomName, exception);
        }
    }

    @Override
    public void removeParticipant(String roomName, String liveKitIdentity) {
        try {
            requireRemoval(roomServiceClient.removeParticipant(roomName, liveKitIdentity).execute());
        } catch (Exception exception) {
            throw new LiveKitOperationException("Khong the xoa participant khoi LiveKit room", exception);
        }
    }

    @Override
    public void removeParticipantsByAttendeeId(String roomName, String attendeeIdPrefix) {
        try {
            Response<List<livekit.LivekitModels.ParticipantInfo>> response = roomServiceClient.listParticipants(roomName).execute();
            if (notFound(response)) return;
            List<livekit.LivekitModels.ParticipantInfo> participants = requireBody(response, "Khong lay duoc participant list");
            for (livekit.LivekitModels.ParticipantInfo participant : participants) {
                if (participant.getIdentity().startsWith(attendeeIdPrefix + ":")) {
                    requireRemoval(roomServiceClient.removeParticipant(roomName, participant.getIdentity()).execute());
                }
            }
        } catch (Exception exception) {
            throw new LiveKitOperationException("Khong the xoa cac ket noi cua attendee", exception);
        }
    }

    private LiveKitRoomResponse toResponse(LivekitModels.Room room) {
        return new LiveKitRoomResponse(
            room.getName(),
            room.getSid(),
            room.getNumParticipants(),
            room.getCreationTime()
        );
    }

    private void requireRemoval(Response<?> response) throws java.io.IOException {
        if (response.isSuccessful() || notFound(response)) return;
        throw new LiveKitOperationException("LiveKit khong xac nhan thao tac (HTTP " + response.code() + ")");
    }
    private boolean notFound(Response<?> response) throws java.io.IOException {
        return response.code() == 404 && response.errorBody() != null
            && "not_found".equals(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response.errorBody().string()).path("code").asText());
    }

    private <T> T requireBody(Response<T> response, String message) {
        if (!response.isSuccessful() || response.body() == null) {
            throw new LiveKitOperationException(message + " (HTTP " + response.code() + ")");
        }
        return response.body();
    }
}
