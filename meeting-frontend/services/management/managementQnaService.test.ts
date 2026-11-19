import { afterEach, describe, expect, it, vi } from 'vitest';
import { ManagementApiError } from './httpClient';
import {
  askQnaQuestion,
  getQnaContext,
  getQnaQuestions,
  getQnaReport,
  moderateQnaQuestion,
  setQnaUpvote,
  updateQnaSettings,
} from './managementQnaService';

const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
const csrf = () => json({ token: 'csrf-qna', headerName: 'X-XSRF-TOKEN' });

describe('Q&A through the real session/CSRF HTTP client', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('encodes meeting identity and sends participant credentials on context reads', async () => {
    const fetch = vi.fn().mockResolvedValue(json({ canAsk: true }));
    vi.stubGlobal('fetch', fetch);
    const signal = new AbortController().signal;
    await getQnaContext('a/b', 'participant-qna', signal);
    expect(fetch.mock.calls[0][0]).toBe('/meeting-service/api/meetings/a%2Fb/qa/context');
    expect(fetch.mock.calls[0][1]).toMatchObject({
      credentials: 'include',
      cache: 'no-store',
      signal,
    });
    expect(fetch.mock.calls[0][1].headers.get('X-Meeting-Participant-Token')).toBe(
      'participant-qna',
    );
  });

  it('supports tokenless authorized history reads without fabricating a participant token', async () => {
    const fetch = vi.fn().mockResolvedValue(json({ canAsk: false }));
    vi.stubGlobal('fetch', fetch);
    await getQnaContext('7');
    expect(fetch.mock.calls[0][1].headers.has('X-Meeting-Participant-Token')).toBe(false);
    expect(fetch.mock.calls[0][1].credentials).toBe('include');
  });

  it('forwards sort, filter, pagination and cancellation and preserves the backend page shape', async () => {
    const page = {
      content: [],
      page: 2,
      size: 20,
      totalElements: 45,
      totalPages: 3,
      first: false,
      last: true,
    };
    const fetch = vi.fn().mockResolvedValue(json(page));
    vi.stubGlobal('fetch', fetch);
    const signal = new AbortController().signal;
    await expect(
      getQnaQuestions('7', { page: 2, sort: 'POPULAR', filter: 'MINE' }, 'participant', signal),
    ).resolves.toEqual(page);
    expect(fetch.mock.calls[0][0]).toBe(
      '/meeting-service/api/meetings/7/qa/questions?page=2&size=20&sort=POPULAR&filter=MINE',
    );
    expect(fetch.mock.calls[0][1].signal).toBeInstanceOf(AbortSignal);
  });

  it('asks anonymously using CSRF and participant token without caller-supplied author identity', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(json({ id: 9 }));
    vi.stubGlobal('fetch', fetch);
    const signal = new AbortController().signal;
    await askQnaQuestion(
      '7',
      { clientQuestionId: 'qna-client-uuid', content: '  Nội dung câu hỏi  ', anonymous: true },
      'participant',
      signal,
    );
    expect(fetch.mock.calls[0][0]).toBe('/meeting-service/api/auth/csrf');
    expect(fetch.mock.calls[1][1].headers.get('X-XSRF-TOKEN')).toBe('csrf-qna');
    expect(fetch.mock.calls[1][1].headers.get('X-Meeting-Participant-Token')).toBe('participant');
    expect(fetch.mock.calls[1][1].signal).toBeInstanceOf(AbortSignal);
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({
      clientQuestionId: 'qna-client-uuid',
      content: 'Nội dung câu hỏi',
      anonymous: true,
    });
  });

  it('sends desired upvote state rather than a toggle or client tally', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(json({ id: 9, upvotedByMe: false, upvoteCount: 3 }));
    vi.stubGlobal('fetch', fetch);
    await expect(setQnaUpvote('7', 9, false, 'participant')).resolves.toMatchObject({
      upvotedByMe: false,
      upvoteCount: 3,
    });
    expect(fetch.mock.calls[1][0]).toBe('/meeting-service/api/meetings/7/qa/questions/9/upvote');
    expect(fetch.mock.calls[1][1].method).toBe('PUT');
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({ upvoted: false });
  });

  it.each([401, 403, 404, 409])(
    'surfaces authoritative %i failures instead of reporting a saved question',
    async (status) => {
      const fetch = vi
        .fn()
        .mockResolvedValueOnce(csrf())
        .mockResolvedValueOnce(json({ message: 'Không thể gửi câu hỏi.' }, status));
      vi.stubGlobal('fetch', fetch);
      await expect(
        askQnaQuestion(
          '7',
          { clientQuestionId: 'same-client-id', content: 'Câu hỏi', anonymous: false },
          'participant',
        ),
      ).rejects.toEqual(new ManagementApiError('Không thể gửi câu hỏi.', status));
      expect(fetch).toHaveBeenCalledTimes(2);
    },
  );

  it('does not automatically resend a write whose response was lost', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(csrf())
      .mockRejectedValueOnce(new Error('Lost response'));
    vi.stubGlobal('fetch', fetch);
    await expect(
      askQnaQuestion(
        '7',
        { clientQuestionId: 'same-client-id', content: 'Câu hỏi', anonymous: false },
        'participant',
      ),
    ).rejects.toThrow();
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('saves explicit moderation settings with the observed settings version and CSRF', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(json({ settingsVersion: 8, moderationEnabled: false }));
    vi.stubGlobal('fetch', fetch);
    await updateQnaSettings(
      '7',
      { enabled: false, anonymousAllowed: true, moderationEnabled: false, expectedVersion: 7 },
      'participant',
    );
    expect(fetch.mock.calls[1][0]).toBe('/meeting-service/api/meetings/7/qa/settings');
    expect(fetch.mock.calls[1][1].method).toBe('PUT');
    expect(fetch.mock.calls[1][1].headers.get('X-XSRF-TOKEN')).toBe('csrf-qna');
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({
      enabled: false,
      anonymousAllowed: true,
      moderationEnabled: false,
      expectedVersion: 7,
    });
  });

  it.each(['APPROVE', 'ANSWER', 'HIDE', 'DELETE'] as const)(
    'sends %s with the observed question version and no author identity',
    async (action) => {
      const fetch = vi
        .fn()
        .mockResolvedValueOnce(csrf())
        .mockResolvedValueOnce(json({ id: 9, version: 5 }));
      vi.stubGlobal('fetch', fetch);
      const signal = new AbortController().signal;
      await moderateQnaQuestion('7', 9, action, 4, 'participant', signal);
      expect(fetch.mock.calls[1][0]).toBe('/meeting-service/api/meetings/7/qa/questions/9/actions');
      expect(fetch.mock.calls[1][1].method).toBe('POST');
      expect(fetch.mock.calls[1][1].signal).toBeInstanceOf(AbortSignal);
      expect(fetch.mock.calls[1][1].headers.get('X-Meeting-Participant-Token')).toBe('participant');
      expect(fetch.mock.calls[1][1].headers.get('X-XSRF-TOKEN')).toBe('csrf-qna');
      expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({ action, expectedVersion: 4 });
    },
  );

  it('sends written answers with CSRF and version, deriving responder identity on the server', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(
        json({
          id: 9,
          answer: 'Câu trả lời\nDòng hai',
          answerAuthorDisplayName: 'Người trả lời',
          version: 5,
        }),
      );
    vi.stubGlobal('fetch', fetch);
    const answer = await moderateQnaQuestion(
      '7',
      9,
      'ANSWER',
      4,
      'participant',
      undefined,
      '  Câu trả lời\nDòng hai  ',
    );
    expect(answer.answer).toBe('Câu trả lời\nDòng hai');
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({
      action: 'ANSWER',
      expectedVersion: 4,
      answer: 'Câu trả lời\nDòng hai',
    });
    expect(fetch.mock.calls[1][1].headers.get('X-XSRF-TOKEN')).toBe('csrf-qna');
    expect(fetch.mock.calls[1][1].headers.get('X-Meeting-Participant-Token')).toBe('participant');
  });

  it('never automatically repeats moderation after a version conflict', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(json({ message: 'Câu hỏi đã thay đổi.' }, 409));
    vi.stubGlobal('fetch', fetch);
    await expect(moderateQnaQuestion('7', 9, 'DELETE', 4)).rejects.toEqual(
      new ManagementApiError('Câu hỏi đã thay đổi.', 409),
    );
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('preserves the backend CSV bytes/BOM, forwards cancellation and uses a safe download filename', async () => {
    const response = new Response('\uFEFFNội dung,Người hỏi\r\n"Câu hỏi","Ẩn danh"', {
      headers: {
        'Content-Type': 'text/csv;charset=UTF-8',
        'Content-Disposition': 'attachment; filename="../../unsafe.csv"',
      },
    });
    const fetch = vi.fn().mockResolvedValue(response);
    vi.stubGlobal('fetch', fetch);
    const signal = new AbortController().signal;
    const file = await getQnaReport('../7/extra', undefined, signal);
    expect(fetch.mock.calls[0][0]).toBe('/meeting-service/api/meetings/..%2F7%2Fextra/qa/report');
    expect(fetch.mock.calls[0][1].signal).toBeInstanceOf(AbortSignal);
    expect(fetch.mock.calls[0][1].method).toBeUndefined();
    expect(fetch.mock.calls[0][1].headers.has('X-Meeting-Participant-Token')).toBe(false);
    expect(file.filename).toBe('meeting-___7_extra-qa.csv');
    expect(Array.from(new Uint8Array(await file.blob.arrayBuffer()).slice(0, 3))).toEqual([
      0xef, 0xbb, 0xbf,
    ]);
  });

  it.each([
    new Response('<html>Đăng nhập</html>', { headers: { 'Content-Type': 'text/html' } }),
    new Response('', { headers: { 'Content-Type': 'text/csv' } }),
  ])('rejects a non-CSV or empty report instead of downloading it', async (response) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response));
    await expect(getQnaReport('7')).rejects.toEqual(
      new ManagementApiError('Máy chủ chưa trả về báo cáo hỏi đáp hợp lệ.', 502),
    );
  });

  it('surfaces permission revocation on report reads without requesting a mutation or fabricating success', async () => {
    const fetch = vi.fn().mockResolvedValue(json({ message: 'Không có quyền xuất hỏi đáp.' }, 403));
    vi.stubGlobal('fetch', fetch);
    await expect(getQnaReport('7', 'participant')).rejects.toEqual(
      new ManagementApiError('Không có quyền xuất hỏi đáp.', 403),
    );
    expect(fetch).toHaveBeenCalledOnce();
    expect(fetch.mock.calls[0][1].headers.get('X-Meeting-Participant-Token')).toBe('participant');
  });
});
