package vn.cusc.meetingservice.exception;

public class ParticipantAlreadyExistsException extends RuntimeException {
    public ParticipantAlreadyExistsException(String userId) {
        super("Người tham gia với userId " + userId + " đã tồn tại trong cuộc họp.");
    }
}
