import asyncio
import logging

from starlette.responses import JSONResponse

logger = logging.getLogger(__name__)


class RequestLimitsMiddleware:
    def __init__(self, app, *, max_bytes: int, body_timeout: float) -> None:
        self.app = app
        self.max_bytes = max_bytes
        self.body_timeout = body_timeout

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        headers = dict(scope.get("headers", []))
        try:
            size = int(headers.get(b"content-length", b"0"))
        except ValueError:
            return await JSONResponse({"detail": "Invalid request length"}, status_code=400)(scope, receive, send)
        if size < 0 or size > self.max_bytes:
            return await JSONResponse({"detail": "Request body is too large"}, status_code=413)(scope, receive, send)
        body = bytearray()
        total = 0
        try:
            async with asyncio.timeout(self.body_timeout):
                while True:
                    message = await receive()
                    if message["type"] == "http.disconnect":
                        return
                    chunk = message.get("body", b"")
                    total += len(chunk)
                    if total > self.max_bytes:
                        return await JSONResponse({"detail": "Request body is too large"}, status_code=413)(scope, receive, send)
                    body.extend(chunk)
                    if not message.get("more_body", False):
                        break
        except TimeoutError:
            return await JSONResponse({"detail": "Request body timed out"}, status_code=408)(scope, receive, send)
        delivered = False
        async def bounded_receive():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": bytes(body), "more_body": False}
            return await receive()
        await self.app(scope, bounded_receive, send)
