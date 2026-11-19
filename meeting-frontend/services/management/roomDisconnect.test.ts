import { DisconnectReason } from 'livekit-client';
import { describe, expect, it } from 'vitest';
import { describeRoomDisconnect } from './roomDisconnect';

describe('final room disconnection recovery', () => {
  it('keeps server-ended and removed participants out of automatic join retries', () => {
    expect(describeRoomDisconnect(DisconnectReason.ROOM_DELETED)).toMatchObject({ retry: false });
    expect(describeRoomDisconnect(DisconnectReason.PARTICIPANT_REMOVED)).toMatchObject({
      retry: false,
    });
  });
  it('offers deliberate recovery when the server or connection fails', () => {
    expect(describeRoomDisconnect(DisconnectReason.SERVER_SHUTDOWN)).toMatchObject({ retry: true });
    expect(describeRoomDisconnect()).toMatchObject({ retry: true });
  });
  it('explains duplicate identity rather than silently moving the user home', () => {
    expect(describeRoomDisconnect(DisconnectReason.DUPLICATE_IDENTITY)).toMatchObject({
      retry: true,
      message: expect.stringContaining('kết nối khác'),
    });
  });
});
