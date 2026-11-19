import type { ReactNode } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ManagementConferenceLayout, sameConferenceTrack } from './ManagementConferenceLayout';
function wrapper({ children }: { children?: ReactNode }) {
  return <div>{children}</div>;
}
vi.mock('@livekit/components-react', () => ({
  CarouselLayout: wrapper,
  ConnectionStateToast: () => null,
  ControlBar: () => (
    <div className="lk-control-bar">
      <button>Microphone</button>
      <button>Camera</button>
      <button>Share screen</button>
      <button>Chat</button>
      <button className="lk-disconnect-button">Leave</button>
    </div>
  ),
  FocusLayout: wrapper,
  FocusLayoutContainer: wrapper,
  GridLayout: wrapper,
  LayoutContextProvider: wrapper,
  ParticipantTile: () => <div className="lk-participant-tile" />,
  RoomAudioRenderer: () => <audio data-room-audio="true" />,
  isTrackReference: (value: { publication?: { trackSid?: string } } | undefined) =>
    Boolean(value?.publication?.trackSid),
  useCreateLayoutContext: () => ({ pin: { dispatch: vi.fn() }, widget: {} }),
  usePinnedTracks: () => [],
  useTracks: () => [],
}));
vi.mock('./ManagementRoomChat', () => ({
  ManagementRoomChat: () => (
    <div className="lk-chat">
      <form className="lk-chat-form">
        <input className="lk-chat-form-input" />
        <button>Send</button>
      </form>
    </div>
  ),
}));

describe('LiveKit room layout contract', () => {
  it('does not hide a newly published camera when a pinned old publication has the same participant/source', () => {
    type Ref = Parameters<typeof sameConferenceTrack>[0];
    const old = {
      participant: { identity: 'user' },
      source: 'camera',
      publication: { trackSid: 'old' },
    } as unknown as Ref;
    const replacement = {
      participant: { identity: 'user' },
      source: 'camera',
      publication: { trackSid: 'new' },
    } as unknown as Ref;
    expect(sameConferenceTrack(old, replacement)).toBe(false);
    expect(sameConferenceTrack(replacement, replacement)).toBe(true);
    const placeholder = { participant: { identity: 'user' }, source: 'camera' } as unknown as Ref;
    expect(sameConferenceTrack(placeholder, replacement)).toBe(true);
  });
  it('keeps one audio renderer and the system toolbar/chat DOM used by existing room bridges', () => {
    const markup = renderToStaticMarkup(
      <ManagementConferenceLayout meetingId="7" chatWriteToken="proof" connectionId="connection" />,
    );
    expect(markup.match(/data-room-audio=/g)).toHaveLength(1);
    expect(markup).toContain('lk-video-conference-inner');
    expect(markup).toContain('lk-control-bar');
    expect(markup).toContain('lk-disconnect-button');
    expect(markup).toContain('lk-chat-form');
    expect(markup).toContain('Share screen');
    expect(markup).toContain('Camera');
  });
});
