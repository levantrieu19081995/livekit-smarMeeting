import { afterEach, describe, expect, it, vi } from 'vitest';
import { ManagementApiError } from './httpClient';
import {
  createPoll,
  getActivePoll,
  getPolls,
  submitPollVote,
  transitionPoll,
  updatePoll,
} from './managementPollService';
import type { PollDraftInput } from '@/types/management/poll';

const draft: PollDraftInput = {
  question: 'Thông qua?',
  type: 'YES_NO_ABSTAIN',
  allowChangeVote: false,
  resultVisibility: 'AFTER_CLOSE',
  minSelections: 1,
  maxSelections: 1,
  eligibleParticipantKeys: ['member-1'],
};
const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });

describe('Poll through the existing session/CSRF proxy', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('handles no active Poll without parsing an empty body', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetch);
    await expect(getActivePoll('7')).resolves.toBeNull();
    expect(fetch.mock.calls[0][1]).toMatchObject({ credentials: 'include', cache: 'no-store' });
  });

  it('encodes the meeting and forwards pagination and cancellation', async () => {
    const fetch = vi.fn().mockResolvedValue(json({ content: [] }));
    vi.stubGlobal('fetch', fetch);
    const signal = new AbortController().signal;
    await getPolls('a/b', 2, signal);
    expect(fetch.mock.calls[0][0]).toBe('/meeting-service/api/meetings/a%2Fb/polls?page=2&size=20');
    expect(fetch.mock.calls[0][1].signal).toBeInstanceOf(AbortSignal);
  });

  it.each(['create', 'update', 'open'] as const)(
    'protects %s with real client CSRF headers',
    async (action) => {
      const fetch = vi
        .fn()
        .mockResolvedValueOnce(json({ token: 'csrf' }))
        .mockResolvedValueOnce(json({ id: 9 }));
      vi.stubGlobal('fetch', fetch);
      if (action === 'create') await createPoll('7', draft);
      else if (action === 'update') await updatePoll('7', 9, draft);
      else await transitionPoll('7', 9, 'open');
      expect(fetch.mock.calls[0][0]).toBe('/meeting-service/api/auth/csrf');
      expect(fetch.mock.calls[1][1].headers.get('X-XSRF-TOKEN')).toBe('csrf');
      expect(fetch.mock.calls[1][1].credentials).toBe('include');
      expect(fetch.mock.calls[1][1].method).toBe(action === 'update' ? 'PUT' : 'POST');
    },
  );

  it('sends only choices and surfaces authoritative conflict without fabricating success', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json({ token: 'csrf' }))
      .mockResolvedValueOnce(json({ message: 'Đã đóng' }, 409));
    vi.stubGlobal('fetch', fetch);
    await expect(submitPollVote('7', 9, [42])).rejects.toEqual(
      new ManagementApiError('Đã đóng', 409),
    );
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({ optionIds: [42] });
  });
});
