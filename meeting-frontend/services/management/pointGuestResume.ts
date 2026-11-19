interface PointGuestResume {
  guestId: string;
  guestResumeToken: string;
}

function key(meetingId: string) {
  return `cusc-point-guest-resume:${meetingId}`;
}

export function readPointGuestResume(meetingId: string): PointGuestResume | undefined {
  try {
    const raw = window.sessionStorage.getItem(key(meetingId));
    if (!raw) return undefined;
    const value: unknown = JSON.parse(raw);
    if (value == null || typeof value !== 'object') return undefined;
    const candidate = value as Partial<PointGuestResume>;
    if (
      typeof candidate.guestId !== 'string' ||
      !candidate.guestId.startsWith('guest-') ||
      candidate.guestId.length <= 6 ||
      typeof candidate.guestResumeToken !== 'string' ||
      !candidate.guestResumeToken ||
      candidate.guestResumeToken.length > 255
    )
      return undefined;
    return { guestId: candidate.guestId, guestResumeToken: candidate.guestResumeToken };
  } catch {
    return undefined;
  }
}

export function storePointGuestResume(meetingId: string, attendeeId: string, token: string) {
  try {
    if (!attendeeId.startsWith('guest:') || !token || token.length > 255) return;
    // Persist identity and its proof together to avoid mismatched writes.
    window.sessionStorage.setItem(
      key(meetingId),
      JSON.stringify({
        guestId: attendeeId.replace(/^guest:/, 'guest-'),
        guestResumeToken: token,
      }),
    );
  } catch {
    // A successful join does not depend on optional browser storage.
  }
}

export function clearPointGuestResume(meetingId: string) {
  try {
    window.sessionStorage.removeItem(key(meetingId));
  } catch {
    // Storage can be disabled by the browser.
  }
}
