export const QNA_CHANGED_TOPIC = 'cusc.qna.changed';

// Room packets are only reload hints. Questions and authorization come from HTTP.
export function isQnaHint(payload: Uint8Array, topic: string | undefined, meetingId: string) {
  if (topic !== QNA_CHANGED_TOPIC || payload.byteLength > 128) return false;
  try {
    const value: unknown = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(payload));
    return (
      typeof value === 'object' &&
      value !== null &&
      Object.keys(value).length === 1 &&
      'meetingId' in value &&
      value.meetingId === meetingId
    );
  } catch {
    return false;
  }
}

export async function publishQnaHint(
  meetingId: string,
  publish: (
    payload: Uint8Array<ArrayBuffer>,
    options: { topic: string; reliable: boolean },
  ) => Promise<void>,
) {
  try {
    await publish(new TextEncoder().encode(JSON.stringify({ meetingId })), {
      topic: QNA_CHANGED_TOPIC,
      reliable: true,
    });
  } catch {
    // The HTTP mutation already succeeded; visible polling provides recovery.
  }
}

// Bound untrusted peer hints and retain one trailing reload for a burst.
export class QnaHintGate {
  private last = -Infinity;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private disposed = false;

  constructor(
    private readonly refresh: () => void,
    private readonly intervalMs = 3000,
  ) {}

  request() {
    if (this.disposed || this.timer !== undefined) return;
    const remaining = this.intervalMs - (Date.now() - this.last);
    if (remaining <= 0) this.run();
    else this.timer = setTimeout(() => this.run(), remaining);
  }

  dispose() {
    this.disposed = true;
    clearTimeout(this.timer);
    this.timer = undefined;
  }

  private run() {
    this.timer = undefined;
    if (this.disposed) return;
    this.last = Date.now();
    this.refresh();
  }
}
