from livekit import api

from app.core.config import Settings


class LiveKitTokenService:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    def create_subscribe_only_token(self, room_name: str, identity: str) -> str:
        return (
            api.AccessToken(self._settings.livekit_api_key, self._settings.livekit_api_secret)
            .with_identity(identity)
            .with_name(identity)
            .with_grants(
                api.VideoGrants(
                    room_join=True,
                    room=room_name,
                    can_subscribe=True,
                    can_publish=False,
                    can_publish_data=False,
                    hidden=True,
                )
            )
            .to_jwt()
        )

