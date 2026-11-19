import uuid
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from app.core.config import Settings
from app.services.openai_stt_provider import OpenAISttProvider


PROMPT_OMITTED = object()
MODEL_OMITTED = object()


class FakeTranscriptions:
    def __init__(self, result=None, error: Exception | None = None) -> None:
        self.result = result or SimpleNamespace(text="  noi dung mock  ")
        self.error = error
        self.calls = []

    async def create(self, *, model: str, file, language: str, prompt: str | None | object = PROMPT_OMITTED):
        assert file[1] == b"wav"
        assert file[2] == "audio/wav"
        call = {"model": model, "filePath": Path(file[0]), "language": language}
        if prompt is not PROMPT_OMITTED:
            call["prompt"] = prompt
        self.calls.append(call)
        if self.error:
            raise self.error
        return self.result


class FakeOpenAIClient:
    def __init__(self, transcriptions: FakeTranscriptions) -> None:
        self.audio = SimpleNamespace(transcriptions=transcriptions)


def make_settings(
    api_key: str = "test-key",
    *,
    stt_model: str | object = MODEL_OMITTED,
    transcription_prompt: str | object = PROMPT_OMITTED,
) -> Settings:
    kwargs = {"OPENAI_API_KEY": api_key}
    if stt_model is not MODEL_OMITTED:
        kwargs["STT_MODEL"] = stt_model
    if transcription_prompt is not PROMPT_OMITTED:
        kwargs["STT_TRANSCRIPTION_PROMPT"] = transcription_prompt
    return Settings(**kwargs)


def work_dir() -> Path:
    path = Path("test_output") / uuid.uuid4().hex
    path.mkdir(parents=True, exist_ok=True)
    return path


@pytest.mark.asyncio
async def test_transcribe_returns_stripped_text() -> None:
    wav_path = work_dir() / "one.wav"
    wav_path.write_bytes(b"wav")
    transcriptions = FakeTranscriptions()
    provider = OpenAISttProvider(
        make_settings(stt_model="mock-stt-model", transcription_prompt="mock prompt"),
        FakeOpenAIClient(transcriptions),
    )

    text = await provider.transcribe(wav_path)

    assert text == "noi dung mock"
    assert transcriptions.calls == [
        {
            "model": "mock-stt-model",
            "filePath": Path(wav_path.name),
            "language": "vi",
            "prompt": "mock prompt",
        }
    ]


@pytest.mark.asyncio
async def test_default_request_uses_gpt_transcribe_language_vi_and_omits_prompt() -> None:
    wav_path = work_dir() / "one.wav"
    wav_path.write_bytes(b"wav")
    transcriptions = FakeTranscriptions()
    settings = make_settings()
    provider = OpenAISttProvider(settings, FakeOpenAIClient(transcriptions))

    await provider.transcribe(wav_path)

    assert settings.STT_MODEL == "gpt-transcribe"
    assert settings.STT_TRANSCRIPTION_PROMPT == ""
    assert transcriptions.calls == [{"model": "gpt-transcribe", "filePath": Path(wav_path.name), "language": "vi"}]


@pytest.mark.asyncio
async def test_transcribe_uses_custom_model_without_hardcoding_default() -> None:
    wav_path = work_dir() / "one.wav"
    wav_path.write_bytes(b"wav")
    transcriptions = FakeTranscriptions()
    provider = OpenAISttProvider(
        make_settings(stt_model="mock-stt-model"),
        FakeOpenAIClient(transcriptions),
    )

    await provider.transcribe(wav_path)

    assert transcriptions.calls == [{"model": "mock-stt-model", "filePath": Path(wav_path.name), "language": "vi"}]


@pytest.mark.asyncio
async def test_transcribe_sends_custom_prompt_override() -> None:
    wav_path = work_dir() / "one.wav"
    wav_path.write_bytes(b"wav")
    transcriptions = FakeTranscriptions()
    settings = make_settings(transcription_prompt="Ngữ cảnh kiểm thử tiếng Việt.")
    provider = OpenAISttProvider(settings, FakeOpenAIClient(transcriptions))

    await provider.transcribe(wav_path)

    assert transcriptions.calls[0]["prompt"] == "Ngữ cảnh kiểm thử tiếng Việt."


@pytest.mark.asyncio
async def test_transcribe_omits_prompt_for_empty_prompt_rollback() -> None:
    wav_path = work_dir() / "one.wav"
    wav_path.write_bytes(b"wav")
    transcriptions = FakeTranscriptions()
    provider = OpenAISttProvider(
        make_settings(transcription_prompt=""),
        FakeOpenAIClient(transcriptions),
    )

    await provider.transcribe(wav_path)

    assert transcriptions.calls == [{"model": "gpt-transcribe", "filePath": Path(wav_path.name), "language": "vi"}]


@pytest.mark.asyncio
async def test_transcribe_omits_prompt_for_whitespace_prompt_rollback() -> None:
    wav_path = work_dir() / "one.wav"
    wav_path.write_bytes(b"wav")
    transcriptions = FakeTranscriptions()
    provider = OpenAISttProvider(
        make_settings(transcription_prompt="   "),
        FakeOpenAIClient(transcriptions),
    )

    await provider.transcribe(wav_path)

    assert transcriptions.calls == [{"model": "gpt-transcribe", "filePath": Path(wav_path.name), "language": "vi"}]


@pytest.mark.asyncio
async def test_transcribe_trims_prompt_without_changing_vietnamese_text() -> None:
    wav_path = work_dir() / "one.wav"
    wav_path.write_bytes(b"wav")
    transcriptions = FakeTranscriptions()
    provider = OpenAISttProvider(
        make_settings(transcription_prompt="  Ngữ cảnh tiếng Việt.  "),
        FakeOpenAIClient(transcriptions),
    )

    await provider.transcribe(wav_path)

    assert transcriptions.calls[0]["prompt"] == "Ngữ cảnh tiếng Việt."


def test_default_settings_use_final_model_and_empty_prompt() -> None:
    settings = Settings(OPENAI_API_KEY="test-key")

    assert settings.STT_MODEL == "gpt-transcribe"
    assert settings.STT_TRANSCRIPTION_PROMPT == ""
    assert settings.STT_SPEECH_REGION_ENABLED is True


@pytest.mark.asyncio
async def test_provider_exception_returns_502() -> None:
    wav_path = work_dir() / "one.wav"
    wav_path.write_bytes(b"wav")
    provider = OpenAISttProvider(
        make_settings(),
        FakeOpenAIClient(FakeTranscriptions(error=RuntimeError("provider down"))),
    )

    with pytest.raises(HTTPException) as exc_info:
        await provider.transcribe(wav_path)

    assert exc_info.value.status_code == 502
    assert exc_info.value.detail == "STT provider is unavailable"


@pytest.mark.asyncio
async def test_missing_api_key_returns_502() -> None:
    wav_path = work_dir() / "one.wav"
    wav_path.write_bytes(b"wav")
    provider = OpenAISttProvider(make_settings(api_key=""))

    with pytest.raises(HTTPException) as exc_info:
        await provider.transcribe(wav_path)

    assert exc_info.value.status_code == 502
    assert exc_info.value.detail == "OPENAI_API_KEY is not configured"
