import { beforeEach, describe, expect, it, vi } from 'vitest';
import { managementFetchJson } from './httpClient';
import { getManagementChatHistory, saveManagementChatMessage } from './managementChatService';
vi.mock('./httpClient', () => ({ managementFetchJson: vi.fn() }));
const fetchJson = vi.mocked(managementFetchJson);
beforeEach(() => fetchJson.mockReset());
describe('meeting chat API scope', () => {
  it('reads a bounded cursor page with the scoped participant proof and caller cancellation', async () => {
    const controller = new AbortController();
    await getManagementChatHistory('meeting/7', 'private-proof', 34, controller.signal);
    expect(fetchJson).toHaveBeenCalledWith(
      '/api/meetings/meeting%2F7/chat/messages?afterId=34&size=100',
      { signal: controller.signal, headers: { 'X-Meeting-Participant-Token': 'private-proof' } },
    );
  });
  it('writes only content and a stable client ID, without accepting sender claims', async () => {
    await saveManagementChatMessage('7', 'private-proof', {
      clientMessageId: 'request-id',
      content: ' Hello ',
    });
    expect(JSON.parse(String(fetchJson.mock.calls[0][1]?.body))).toEqual({
      clientMessageId: 'request-id',
      content: 'Hello',
    });
    expect(fetchJson.mock.calls[0][1]).toMatchObject({
      method: 'POST',
      headers: { 'X-Meeting-Participant-Token': 'private-proof' },
    });
  });
});
