const GUEST_STORAGE_PREFIX = 'management-meeting-guest:';

function getStorageKey(meetingId: string) {
  return `${GUEST_STORAGE_PREFIX}${meetingId}`;
}

function isGuestId(value: string | null | undefined): value is string {
  return typeof value === 'string' && value.startsWith('guest-');
}

export function getStoredGuestId(meetingId: string): string | undefined {
  if (typeof window === 'undefined') {
    return undefined;
  }

  const storedValue = window.sessionStorage.getItem(getStorageKey(meetingId));
  return isGuestId(storedValue) ? storedValue : undefined;
}

export function storeGuestId(meetingId: string, guestId: string): void {
  if (typeof window === 'undefined' || !isGuestId(guestId)) {
    return;
  }

  window.sessionStorage.setItem(getStorageKey(meetingId), guestId);
}

export function clearStoredGuestId(meetingId: string): void {
  if (typeof window === 'undefined') {
    return;
  }

  window.sessionStorage.removeItem(getStorageKey(meetingId));
}
