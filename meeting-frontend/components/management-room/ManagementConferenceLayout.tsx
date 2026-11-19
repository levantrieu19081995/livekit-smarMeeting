'use client';

// Keep LiveKit's prefab layout and controls; only chat uses the durable meeting API.
import {
  CarouselLayout,
  ConnectionStateToast,
  ControlBar,
  FocusLayout,
  FocusLayoutContainer,
  GridLayout,
  LayoutContextProvider,
  ParticipantTile,
  RoomAudioRenderer,
  isTrackReference,
  useCreateLayoutContext,
  usePinnedTracks,
  useTracks,
  type TrackReferenceOrPlaceholder,
  type WidgetState,
} from '@livekit/components-react';
import { RoomEvent, Track } from 'livekit-client';
import { useEffect, useRef, useState } from 'react';
import { ManagementRoomChat } from './ManagementRoomChat';

export function sameConferenceTrack(
  a: TrackReferenceOrPlaceholder,
  b: TrackReferenceOrPlaceholder,
) {
  if (isTrackReference(a) && isTrackReference(b))
    return a.publication.trackSid === b.publication.trackSid;
  return a.participant.identity === b.participant.identity && a.source === b.source;
}

export function ManagementConferenceLayout({
  meetingId,
  chatWriteToken,
  connectionId,
}: {
  meetingId: string;
  chatWriteToken: string;
  connectionId: string;
}) {
  const [widgetState, setWidgetState] = useState<WidgetState>({
    showChat: false,
    unreadMessages: 0,
    showSettings: false,
  });
  const lastAutoFocusedScreenShareTrack = useRef<TrackReferenceOrPlaceholder | null>(null);
  const tracks = useTracks(
    [
      { source: Track.Source.Camera, withPlaceholder: true },
      { source: Track.Source.ScreenShare, withPlaceholder: false },
    ],
    { updateOnlyOn: [RoomEvent.ActiveSpeakersChanged], onlySubscribed: false },
  );
  const layoutContext = useCreateLayoutContext();
  const screenShareTracks = tracks
    .filter(isTrackReference)
    .filter((track) => track.publication.source === Track.Source.ScreenShare);
  const focusTrack = usePinnedTracks(layoutContext)?.[0];
  const carouselTracks = tracks.filter(
    (track) => !focusTrack || !sameConferenceTrack(track, focusTrack),
  );

  useEffect(() => {
    const shared = screenShareTracks.find((track) => track.publication.isSubscribed);
    if (shared && lastAutoFocusedScreenShareTrack.current === null) {
      layoutContext.pin.dispatch?.({ msg: 'set_pin', trackReference: shared });
      lastAutoFocusedScreenShareTrack.current = shared;
    } else if (
      lastAutoFocusedScreenShareTrack.current &&
      !screenShareTracks.some(
        (track) =>
          track.publication.trackSid ===
          lastAutoFocusedScreenShareTrack.current?.publication?.trackSid,
      )
    ) {
      layoutContext.pin.dispatch?.({ msg: 'clear_pin' });
      lastAutoFocusedScreenShareTrack.current = null;
    }
    if (focusTrack && !isTrackReference(focusTrack)) {
      const refreshed = tracks.find((track) => sameConferenceTrack(track, focusTrack));
      if (refreshed !== focusTrack && isTrackReference(refreshed)) {
        layoutContext.pin.dispatch?.({ msg: 'set_pin', trackReference: refreshed });
      }
    }
    // The SDK's layout context changes identity on render; track/pin events drive this effect.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tracks, focusTrack, screenShareTracks.map((track) => track.publication.trackSid).join(',')]);

  return (
    <div className="lk-video-conference">
      <LayoutContextProvider value={layoutContext} onWidgetChange={setWidgetState}>
        <div className="lk-video-conference-inner">
          {!focusTrack ? (
            <div className="lk-grid-layout-wrapper">
              <GridLayout tracks={tracks}>
                <ParticipantTile />
              </GridLayout>
            </div>
          ) : (
            <div className="lk-focus-layout-wrapper">
              <FocusLayoutContainer>
                <CarouselLayout tracks={carouselTracks}>
                  <ParticipantTile />
                </CarouselLayout>
                <FocusLayout trackRef={focusTrack} />
              </FocusLayoutContainer>
            </div>
          )}
          <ControlBar controls={{ chat: true, settings: false }} />
        </div>
        <ManagementRoomChat
          meetingId={meetingId}
          participantToken={chatWriteToken}
          connectionId={connectionId}
          visible={widgetState.showChat}
        />
      </LayoutContextProvider>
      <RoomAudioRenderer />
      <ConnectionStateToast />
    </div>
  );
}
