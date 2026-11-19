package vn.cusc.meetingservice.model.enums;

public enum MinutesProcessingStage {
    VALIDATING,
    PREPARING_AUDIO,
    TRANSCRIBING,
    BUILDING_TRANSCRIPT,
    GENERATING_MINUTES,
    DRAFT_READY,
    COMPLETED,
    FAILED
}
