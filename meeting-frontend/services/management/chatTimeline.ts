import type { ManagementChatMessageResponse } from '@/types/management/chat';

export const CHAT_RUNTIME_WINDOW = 500;
export const CHAT_CHANGE_TOPIC = 'cusc.chat.changed';

// IDs and text always come from Java. LiveKit packets only request a fresh API read.
export function mergeChatTimeline(
  previous: ManagementChatMessageResponse[],
  incoming: ManagementChatMessageResponse[],
) {
  const records = new Map(previous.map((message) => [message.id, message]));
  for (const message of incoming) records.set(message.id, message);
  return [...records.values()].sort((a, b) => a.id - b.id).slice(-CHAT_RUNTIME_WINDOW);
}

export function reusableChatDraft(
  current: { clientMessageId: string; content: string } | null,
  content: string,
  createId: () => string,
) {
  const normalized = content.trim();
  return current?.content === normalized
    ? current
    : { clientMessageId: createId(), content: normalized };
}

export async function persistChatThenNotify(
  persist: () => Promise<ManagementChatMessageResponse>,
  notify: (receipt: ManagementChatMessageResponse) => Promise<unknown>,
) {
  const receipt = await persist();
  // Notification failure cannot turn a confirmed persistent message into an unknown write.
  void Promise.resolve()
    .then(() => notify(receipt))
    .catch(() => {});
  return receipt;
}

export function confirmsPendingChat(
  incoming: ManagementChatMessageResponse[],
  pending: { clientMessageId: string; content: string } | null,
  connectionId: string,
) {
  return (
    pending != null &&
    incoming.some(
      (message) =>
        message.clientMessageId === pending.clientMessageId &&
        message.content === pending.content &&
        message.senderConnectionId === connectionId,
    )
  );
}
