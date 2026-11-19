import { afterEach, describe, expect, it, vi } from 'vitest';
import { generateMeetingConclusion, getMeetingConclusion } from './managementConclusionService';

afterEach(() => vi.unstubAllGlobals());

describe('AI conclusion session and request contract', () => {
  it('reads persistent status through Java with credentials and cancellation', async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json({ status: 'GENERATING' }));
    vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController();
    expect((await getMeetingConclusion('37', controller.signal)).status).toBe('GENERATING');
    expect(fetchMock).toHaveBeenCalledWith(
      '/meeting-service/api/meetings/37/conclusion',
      expect.objectContaining({
        credentials: 'include',
        cache: 'no-store',
        signal: expect.any(AbortSignal),
      }),
    );
  });

  it('fetches CSRF and posts the exact persisted version and manual snapshot to Java', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ token: 'csrf-test', headerName: 'X-XSRF-TOKEN' }))
      .mockResolvedValueOnce(
        Response.json({ status: 'GENERATING', generationId: 'job-1' }, { status: 202 }),
      );
    vi.stubGlobal('fetch', fetchMock);
    const source = {
      expectedMinutesVersion: 2,
      manualContent: 'Nội dung đã sửa',
      expectedStoredConclusion: 'Nội dung đã sửa',
    };
    await generateMeetingConclusion('37', source);
    const [url, init] = fetchMock.mock.calls[1];
    expect(url).toBe('/meeting-service/api/meetings/37/conclusion/generate');
    expect(init.method).toBe('POST');
    expect(init.credentials).toBe('include');
    expect(init.headers.get('X-XSRF-TOKEN')).toBe('csrf-test');
    expect(JSON.parse(init.body)).toEqual(source);
    expect(fetchMock.mock.calls).toHaveLength(2);
  });

  it('preserves the server lock error instead of treating an already generated job as successful', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(Response.json({ token: 'csrf-test' }))
        .mockResolvedValueOnce(
          Response.json(
            { code: 'AI_CONCLUSION_ALREADY_GENERATED', message: 'Đã tạo kết luận' },
            { status: 409 },
          ),
        ),
    );
    await expect(
      generateMeetingConclusion('37', {
        expectedMinutesVersion: 1,
        manualContent: '',
        expectedStoredConclusion: '',
      }),
    ).rejects.toMatchObject({
      status: 409,
      code: 'AI_CONCLUSION_ALREADY_GENERATED',
    });
  });
});
