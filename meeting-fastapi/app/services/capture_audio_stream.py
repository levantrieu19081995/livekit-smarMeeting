import asyncio
from collections import deque

from livekit import rtc


class CaptureBufferOverflow(RuntimeError):
    pass


class CaptureFrameQueue:
    """Bounded frames with visible overflow, rather than silent SDK frame eviction."""

    def __init__(self, capacity: int) -> None:
        self.capacity = capacity
        self._frames = deque()
        self._ready = asyncio.Event()
        self._ended = False
        self._overflow = False

    def put(self, frame) -> None:
        if frame is None:
            self._ended = True
        elif not self._ended:
            if len(self._frames) >= self.capacity:
                self._overflow = True
                self._ended = True
            else:
                self._frames.append(frame)
        self._ready.set()

    async def get(self):
        while not self._frames and not self._ended:
            self._ready.clear()
            await self._ready.wait()
        if self._overflow:
            raise CaptureBufferOverflow("CAPTURE_AUDIO_BUFFER_OVERFLOW")
        if self._frames:
            return self._frames.popleft()
        return None


class CaptureAudioStream(rtc.AudioStream):
    """Adapter for pinned livekit==1.1.13 queue protocol; upgrade tests protect it."""

    def __init__(self, **kwargs) -> None:
        capacity = kwargs["capacity"]
        super().__init__(**kwargs)
        self._capture_queue = CaptureFrameQueue(capacity)
        self._queue = self._capture_queue

    async def __anext__(self):
        frame = await self._capture_queue.get()
        if frame is None:
            raise StopAsyncIteration
        return frame
