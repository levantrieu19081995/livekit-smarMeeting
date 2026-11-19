import logging
import sys

from app.core.config import Settings


def configure_logging(settings: Settings) -> None:
    logging.basicConfig(
        level=settings.log_level.upper(),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        handlers=[logging.StreamHandler(sys.stdout)],
        force=True,
    )


def log_context(**values: object) -> str:
    safe = {key: value for key, value in values.items() if value is not None}
    return " ".join(f"{key}={value}" for key, value in safe.items())

