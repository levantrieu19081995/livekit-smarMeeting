import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  actOnSpeakingRequest,
  createSpeakingRequest,
  getSpeakingContext,
  getSpeakingRequests,
} from './managementSpeakingService';

const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });

describe('Speaking HTTP/session/CSRF boundary', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('scopes and encodes a history read, forwards session and token without author claims', async () => {
    const fetch = vi.fn().mockResolvedValue(json({ pointMeeting: true }));
    vi.stubGlobal('fetch', fetch);
    const signal = new AbortController().signal;
    await getSpeakingContext('a/b', 'credential', signal);
    expect(fetch.mock.calls[0][0]).toBe('/meeting-service/api/meetings/a%2Fb/speaking/context');
    expect(fetch.mock.calls[0][1]).toMatchObject({
      credentials: 'include',
      cache: 'no-store',
      signal,
    });
    expect(fetch.mock.calls[0][1].headers.get('X-Meeting-Participant-Token')).toBe('credential');
  });

  it('supports a session-only post-meeting history read and bounded page requests', async () => {
    const page = { content: [], totalPages: 3, totalElements: 45, page: 2 };
    const fetch = vi.fn().mockResolvedValue(json(page));
    vi.stubGlobal('fetch', fetch);
    await expect(getSpeakingRequests('37', 2)).resolves.toEqual(page);
    expect(fetch.mock.calls[0][0]).toBe(
      '/meeting-service/api/meetings/37/speaking/requests?page=2&size=20',
    );
    expect(fetch.mock.calls[0][1].headers.has('X-Meeting-Participant-Token')).toBe(false);
  });

  it('sends the stable request ID and optional shared speaker, deriving point and actor on server', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json({ token: 'csrf' }))
      .mockResolvedValueOnce(json({ id: 7 }));
    vi.stubGlobal('fetch', fetch);
    const signal = new AbortController().signal;
    await createSpeakingRequest(
      '37',
      { requestId: 'stable-uuid', speakerName: '  Nguyễn Văn Bình  ' },
      'credential',
      signal,
    );
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({
      requestId: 'stable-uuid',
      speakerName: 'Nguyễn Văn Bình',
    });
    expect(fetch.mock.calls[1][1].headers.get('X-XSRF-TOKEN')).toBe('csrf');
    expect(fetch.mock.calls[1][1].headers.get('X-Meeting-Participant-Token')).toBe('credential');
    expect(fetch.mock.calls[1][1]).toMatchObject({ method: 'POST', signal });
  });

  it('leaves blank speaker identity to the server rather than inventing a browser author', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json({ token: 'csrf' }))
      .mockResolvedValueOnce(json({ id: 7 }));
    vi.stubGlobal('fetch', fetch);
    await createSpeakingRequest('37', { requestId: 'stable' });
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({
      requestId: 'stable',
      speakerName: '',
    });
  });

  it.each(['approve', 'finish', 'cancel'] as const)(
    'uses scoped PATCH and observed version for %s',
    async (action) => {
      const fetch = vi
        .fn()
        .mockResolvedValueOnce(json({ token: 'csrf' }))
        .mockResolvedValueOnce(json({ id: 7, version: 2 }));
      vi.stubGlobal('fetch', fetch);
      await actOnSpeakingRequest('37', 7, action, 1, 'credential');
      expect(fetch.mock.calls[1][0]).toBe(
        `/meeting-service/api/meetings/37/speaking/requests/7/${action}`,
      );
      expect(fetch.mock.calls[1][1].method).toBe('PATCH');
      expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({ version: 1 });
    },
  );

  it.each([401, 403, 404, 409])(
    'surfaces authoritative %i denial without claiming success',
    async (status) => {
      const fetch = vi
        .fn()
        .mockResolvedValueOnce(json({ token: 'csrf' }))
        .mockResolvedValueOnce(json({ message: 'Denied', code: 'SPEAKING_CONFLICT' }, status));
      vi.stubGlobal('fetch', fetch);
      await expect(actOnSpeakingRequest('37', 7, 'approve', 0)).rejects.toMatchObject({
        status,
        code: 'SPEAKING_CONFLICT',
      });
    },
  );

  it('never issues a write when CSRF preparation fails', async () => {
    const fetch = vi.fn().mockResolvedValue(json({ message: 'Denied' }, 403));
    vi.stubGlobal('fetch', fetch);
    await expect(createSpeakingRequest('37', { requestId: 'stable' })).rejects.toThrow();
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});
