export interface SpeakingDraft {
  requestId: string;
  pointId: number;
  speakerName: string;
}

export function speakingDraftKey(meetingId: string, attendeeId: string) {
  return `cusc.speaking.pending:${encodeURIComponent(meetingId)}:${encodeURIComponent(attendeeId)}`;
}

// Persist only an unconfirmed operation, never a participant credential.
export function readSpeakingDraft(key: string): SpeakingDraft | null {
  try {
    const value: unknown = JSON.parse(window.sessionStorage.getItem(key) || 'null');
    if (
      value &&
      typeof value === 'object' &&
      'requestId' in value &&
      typeof value.requestId === 'string' &&
      /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
        value.requestId,
      ) &&
      'pointId' in value &&
      typeof value.pointId === 'number' &&
      Number.isSafeInteger(value.pointId) &&
      value.pointId > 0 &&
      'speakerName' in value &&
      typeof value.speakerName === 'string' &&
      value.speakerName.length <= 255
    ) {
      return value as SpeakingDraft;
    }
  } catch {
    // Storage can be unavailable in private browsing. In-memory retry remains.
  }
  return null;
}

export function storeSpeakingDraft(key: string, value: SpeakingDraft | null) {
  try {
    if (value) window.sessionStorage.setItem(key, JSON.stringify(value));
    else window.sessionStorage.removeItem(key);
  } catch {
    // Storage is only recovery assistance; it cannot invalidate a saved request.
  }
}

export function newSpeakingRequestId(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') return globalThis.crypto.randomUUID();
  if (typeof globalThis.crypto?.getRandomValues !== 'function') {
    throw new Error(
      'Trình duyệt chưa hỗ trợ mã xác nhận yêu cầu. Hãy dùng trình duyệt mới hoặc mở bằng HTTPS.',
    );
  }
  const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
