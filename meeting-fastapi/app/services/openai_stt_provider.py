from pathlib import Path
from typing import Any
import asyncio
import logging

from fastapi import HTTPException, status

from app.core.config import Settings
from app.core.runtime_limits import stt_execution_slot


class SttProviderUnavailable(HTTPException):
    def __init__(self, *, retryable: bool) -> None:
        super().__init__(status_code=502, detail="STT provider is unavailable")
        self.retryable = retryable


class OpenAISttProvider:
    def __init__(self, settings: Settings, openai_client: Any | None = None) -> None:
        self.settings = settings
        self._openai_client = openai_client

    async def transcribe(self, wav_path: Path) -> str:
        try:
            if self._openai_client is None:
                self._openai_client = self._build_openai_client()
                self._owns_client = True
            client = self._openai_client
            async with stt_execution_slot(self.settings):
                if await asyncio.to_thread(lambda: wav_path.stat().st_size) > self.settings.STT_MAX_AUDIO_BYTES:
                    raise HTTPException(413, detail="STT audio chunk is too large")
                audio = await asyncio.to_thread(wav_path.read_bytes)
                request_kwargs = {
                    "model": self.settings.STT_MODEL,
                    "file": (wav_path.name, audio, "audio/wav"),
                    "language": "vi",
                }
                prompt = self.settings.STT_TRANSCRIPTION_PROMPT.strip()
                if prompt:
                    request_kwargs["prompt"] = prompt
                async with asyncio.timeout(self.settings.STT_TIMEOUT_SECONDS):
                    transcription = await client.audio.transcriptions.create(**request_kwargs)
        except HTTPException:
            raise
        except Exception as exc:
            logging.getLogger(__name__).error("STT provider operation failed (%s)", type(exc).__name__)
            # A transport timeout may already have produced a paid result. Retry
            # only explicit provider refusals; unknown outcomes fail truthfully.
            code = getattr(exc, "status_code", None)
            raise SttProviderUnavailable(retryable=code == 429) from exc

        if isinstance(transcription, dict):
            text = str(transcription.get("text", ""))
        else:
            text = str(getattr(transcription, "text", ""))
        if len(text) > self.settings.STT_MAX_TEXT_CHARS:
            raise HTTPException(502, detail="STT provider returned excessive text")
        return text.strip()

    async def close(self) -> None:
        if getattr(self, "_owns_client", False) and self._openai_client is not None:
            client, self._openai_client = self._openai_client, None
            if hasattr(client, "close"):
                await client.close()

    def _build_openai_client(self) -> Any:
        if not self.settings.OPENAI_API_KEY:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail="OPENAI_API_KEY is not configured",
            )

        try:
            from openai import AsyncOpenAI
        except ImportError as exc:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail="OpenAI SDK is not installed",
            ) from exc

        return AsyncOpenAI(api_key=self.settings.OPENAI_API_KEY, timeout=self.settings.STT_TIMEOUT_SECONDS, max_retries=0)
