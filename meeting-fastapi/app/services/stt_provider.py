from pathlib import Path
from typing import Protocol


class SttProvider(Protocol):
    async def transcribe(self, wav_path: Path) -> str:
        ...
