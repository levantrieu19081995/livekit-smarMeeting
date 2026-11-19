export const SPEAKING_CHANGED_TOPIC = 'cusc.speaking.changed';

export function isSpeakingHint(payload: Uint8Array, topic: string | undefined, meetingId: string) {
  if (topic !== SPEAKING_CHANGED_TOPIC || payload.byteLength > 128) return false;
  try {
    const value: unknown = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(payload));
    return (
      value !== null &&
      typeof value === 'object' &&
      Object.keys(value).length === 1 &&
      'meetingId' in value &&
      value.meetingId === meetingId
    );
  } catch {
    return false;
  }
}

export async function publishSpeakingHint(
  meetingId: string,
  publish: (
    payload: Uint8Array<ArrayBuffer>,
    options: { topic: string; reliable: boolean },
  ) => Promise<void>,
) {
  try {
    await publish(new TextEncoder().encode(JSON.stringify({ meetingId })), {
      topic: SPEAKING_CHANGED_TOPIC,
      reliable: true,
    });
  } catch {
    // Persisted HTTP state is authoritative; visible polling recovers a lost hint.
  }
}
