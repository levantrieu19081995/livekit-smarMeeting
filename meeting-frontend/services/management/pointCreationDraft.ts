export interface PointCreationDraft {
  fingerprint: string;
  clientRequestId: string;
}

export function readPendingPointCreation(accountPublicId: string): PointCreationDraft | null {
  return pending.get(accountPublicId) ?? storedDraft(accountPublicId);
}

const pending = new Map<string, PointCreationDraft>();
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function storageKey(accountPublicId: string) {
  return `cusc-point-creation:${encodeURIComponent(accountPublicId)}`;
}

function newRequestId() {
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

function storedDraft(accountPublicId: string): PointCreationDraft | null {
  try {
    const raw = window.sessionStorage.getItem(storageKey(accountPublicId));
    if (!raw) return null;
    const value: unknown = JSON.parse(raw);
    if (value == null || typeof value !== 'object') return null;
    const candidate = value as Partial<PointCreationDraft>;
    if (
      typeof candidate.fingerprint !== 'string' ||
      typeof candidate.clientRequestId !== 'string' ||
      !UUID.test(candidate.clientRequestId)
    )
      return null;
    return { fingerprint: candidate.fingerprint, clientRequestId: candidate.clientRequestId };
  } catch {
    return null;
  }
}

export function getPointCreationRequestId(accountPublicId: string, fingerprint: string): string {
  if (!accountPublicId.trim()) throw new Error('Vui lòng đăng nhập lại trước khi tạo cuộc họp.');
  const previous = pending.get(accountPublicId) ?? storedDraft(accountPublicId);
  if (previous?.fingerprint === fingerprint) {
    pending.set(accountPublicId, previous);
    return previous.clientRequestId;
  }
  const draft = { fingerprint, clientRequestId: newRequestId() };
  pending.set(accountPublicId, draft);
  if (pending.size > 100) pending.delete(pending.keys().next().value!);
  try {
    window.sessionStorage.setItem(storageKey(accountPublicId), JSON.stringify(draft));
  } catch {
    /* Retry within the same page still uses the in-memory key. */
  }
  return draft.clientRequestId;
}

function creationScope(accountPublicId: string, mode: string) {
  // POINT keeps its original key so drafts from the previous release remain recoverable.
  return mode === 'POINT' ? accountPublicId : `${accountPublicId}:${mode}`;
}

export function readPendingMeetingCreation(accountPublicId: string, mode: string) {
  return readPendingPointCreation(creationScope(accountPublicId, mode));
}

export function getMeetingCreationRequestId(
  accountPublicId: string,
  mode: string,
  fingerprint: string,
) {
  if (!accountPublicId.trim()) throw new Error('Vui lòng đăng nhập lại trước khi tạo cuộc họp.');
  return getPointCreationRequestId(creationScope(accountPublicId, mode), fingerprint);
}

export function confirmMeetingCreationRequest(
  accountPublicId: string,
  mode: string,
  requestId: string,
) {
  confirmPointCreationRequest(creationScope(accountPublicId, mode), requestId);
}

export function confirmPointCreationRequest(accountPublicId: string, clientRequestId: string) {
  const memoryMatches = pending.get(accountPublicId)?.clientRequestId === clientRequestId;
  if (memoryMatches) pending.delete(accountPublicId);
  const stored = storedDraft(accountPublicId);
  if (stored ? stored.clientRequestId !== clientRequestId : !memoryMatches) return;
  try {
    window.sessionStorage.removeItem(storageKey(accountPublicId));
  } catch {
    /* Optional storage cannot undo a confirmed creation receipt. */
  }
}
