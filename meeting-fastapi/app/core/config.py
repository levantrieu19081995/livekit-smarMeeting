from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field, field_validator
from urllib.parse import urlsplit
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "meeting-fastapi"
    app_host: str = "0.0.0.0"
    app_port: int = 8000
    log_level: str = "INFO"
    app_environment: Literal["production", "development", "test"] = "production"

    livekit_url: str = "ws://127.0.0.1:7880"
    livekit_api_key: str = ""
    livekit_api_secret: str = ""

    capture_root_dir: Path = Path("./data/meetings")
    capture_sample_rate: int = Field(default=48000, ge=8000, le=48000)
    capture_num_channels: int = Field(default=1, ge=1, le=2)
    capture_sample_width_bytes: int = Field(default=2, ge=2, le=2)
    capture_audio_stream_capacity: int = Field(default=200, ge=10, le=2000)
    capture_write_queue_capacity: int = Field(default=8, ge=1, le=64)
    capture_write_batch_ms: int = Field(default=250, ge=20, le=1000)
    capture_connect_timeout_seconds: float = Field(default=15, ge=1, le=60)
    capture_close_timeout_seconds: float = Field(default=5, ge=1, le=30)
    capture_max_active_sessions: int = Field(default=20, ge=1, le=200)
    capture_max_audio_tracks: int = Field(default=200, ge=1, le=1000)
    capture_terminal_cache_size: int = Field(default=64, ge=0, le=1000)
    capture_max_events: int = Field(default=10000, ge=100, le=100000)
    internal_api_key: str = ""
    require_internal_api_key: bool = Field(default=True)
    max_request_body_bytes: int = Field(default=2097152, ge=65536, le=8388608)
    request_body_timeout_seconds: float = Field(default=15, ge=1, le=60)
    OPENAI_API_KEY: str = ""
    conclusion_root_dir: Path = Path("./data/ai-conclusions")
    CONCLUSION_MODEL: str = "gpt-4o-mini"
    CONCLUSION_TIMEOUT_SECONDS: float = Field(default=90, ge=1, le=180)
    CONCLUSION_MAX_INPUT_CHARS: int = Field(default=150000, ge=1000, le=400000)
    CONCLUSION_MAX_OUTPUT_TOKENS: int = Field(default=12000, ge=1000, le=32000)
    STT_PROVIDER: str = "openai"
    STT_MODEL: str = "gpt-transcribe"
    STT_CHUNK_SECONDS: int = Field(default=180, ge=1, le=300)
    STT_SPEECH_REGION_ENABLED: bool = True
    STT_TRANSCRIPTION_PROMPT: str = ""
    STT_CONCURRENCY: int = Field(default=4, ge=1, le=32)
    STT_MAX_ACTIVE_JOBS: int = Field(default=8, ge=1, le=64)
    STT_MAX_PENDING_JOBS: int = Field(default=200, ge=1, le=2000)
    STT_TERMINAL_CACHE_SIZE: int = Field(default=64, ge=0, le=1000)
    STT_MAX_CALLBACK_TASKS: int = Field(default=256, ge=16, le=4096)
    STT_MAX_RETRIES: int = Field(default=2, ge=0, le=5)
    STT_RETRY_BASE_SECONDS: float = Field(default=1.0, ge=0, le=30)
    STT_TIMEOUT_SECONDS: float = Field(default=90, ge=1, le=300)
    STT_JOB_TIMEOUT_SECONDS: float = Field(default=14400, ge=60, le=86400)
    STT_MAX_AUDIO_BYTES: int = Field(default=24000000, ge=1000000, le=25000000)
    STT_MAX_MANIFEST_BYTES: int = Field(default=10485760, ge=65536, le=67108864)
    STT_MAX_CHUNKS_PER_JOB: int = Field(default=10000, ge=1, le=50000)
    STT_MAX_SEGMENT_SECONDS: int = Field(default=28800, ge=600, le=86400)
    STT_MAX_TEXT_CHARS: int = Field(default=12000, ge=1000, le=100000)
    STT_PREPROCESS_CONCURRENCY: int = Field(default=2, ge=1, le=16)
    MEETING_SERVICE_BASE_URL: str = "http://localhost:8080"
    STT_PROGRESS_CALLBACK_ENABLED: bool = True
    STT_PROGRESS_CALLBACK_TIMEOUT_SECONDS: float = Field(default=5.0, ge=0.1, le=60)
    STT_PROGRESS_CALLBACK_MIN_PERCENT_STEP: float = Field(default=5.0, ge=1, le=100)
    STT_COMPLETION_CALLBACK_ENABLED: bool = True
    STT_COMPLETION_CALLBACK_TIMEOUT_SECONDS: float = Field(default=5.0, ge=0.1, le=60)
    STT_COMPLETION_CALLBACK_MAX_ATTEMPTS: int = Field(default=5, ge=1, le=10)
    STT_COMPLETION_CALLBACK_RETRY_BASE_SECONDS: float = Field(default=0.5, ge=0, le=30)

    @field_validator("livekit_url", "MEETING_SERVICE_BASE_URL")
    @classmethod
    def service_url(cls, value: str, info) -> str:
        parsed = urlsplit(value)
        schemes = {"ws", "wss"} if info.field_name == "livekit_url" else {"http", "https"}
        if parsed.scheme not in schemes or not parsed.hostname or parsed.username or parsed.password or parsed.fragment or any(ord(c) < 32 for c in value):
            raise ValueError("Service URL must have a supported scheme, host and no embedded credentials")
        return value.rstrip("/")

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")


@lru_cache
def get_settings() -> Settings:
    return Settings()
