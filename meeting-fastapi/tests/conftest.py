"""Tests never resume real deployment jobs or use real service/provider credentials."""
import os
import uuid
from pathlib import Path

_root = Path.cwd() / "test_output" / f"audit-hardening-{uuid.uuid4().hex}"
os.environ["APP_ENVIRONMENT"] = "test"
os.environ["REQUIRE_INTERNAL_API_KEY"] = "true"
os.environ["INTERNAL_API_KEY"] = "test-internal-key"
os.environ["OPENAI_API_KEY"] = ""
os.environ["CAPTURE_ROOT_DIR"] = str(_root)
os.environ["CONCLUSION_ROOT_DIR"] = str(_root / "_conclusions")
os.environ["LIVEKIT_API_KEY"] = "test-livekit-key"
os.environ["LIVEKIT_API_SECRET"] = "test-livekit-secret"
os.environ["STT_PROGRESS_CALLBACK_ENABLED"] = "false"
os.environ["STT_COMPLETION_CALLBACK_ENABLED"] = "false"
