import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  ManagementApiError,
  getCsrfToken,
  managementFetchBlob,
  managementFetchJson,
  managementFetchVoid,
  toManagementApiUrl,
  ManagementTransportError,
} from './httpClient';

function jsonResponse(payload: unknown, init: Partial<Response> = {}): Response {
  return {
    ok: init.ok ?? true,
    status: init.status ?? 200,
    headers: new Headers({ 'content-type': 'application/json' }),
    json: async () => payload,
    text: async () => JSON.stringify(payload),
  } as Response;
}

function blobResponse(blob: Blob, init: Partial<Response> = {}): Response {
  return {
    ok: init.ok ?? true,
    status: init.status ?? 200,
    headers: new Headers({ 'content-type': blob.type || 'application/pdf' }),
    blob: async () => blob,
    json: async () => ({}),
    text: async () => '',
  } as Response;
}

describe('toManagementApiUrl', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('routes meeting requests through the same-origin Next proxy', () => {
    expect(toManagementApiUrl('/api/meetings/7/join')).toBe('/meeting-service/api/meetings/7/join');
  });

  it('normalizes a path without a leading slash', () => {
    expect(toManagementApiUrl('api/meetings')).toBe('/meeting-service/api/meetings');
  });

  it('gets a CSRF token through the management proxy with credentials', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        token: 'csrf-token',
        headerName: 'X-XSRF-TOKEN',
        parameterName: '_csrf',
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(getCsrfToken()).resolves.toEqual({
      token: 'csrf-token',
      headerName: 'X-XSRF-TOKEN',
      parameterName: '_csrf',
    });

    expect(fetchMock).toHaveBeenCalledWith('/meeting-service/api/auth/csrf', {
      method: 'GET',
      cache: 'no-store',
      credentials: 'include',
      signal: expect.any(AbortSignal),
      headers: {
        Accept: 'application/json',
      },
    });
  });

  it('adds the CSRF header and credentials to mutation requests', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-token', headerName: 'X-XSRF-TOKEN' }))
      .mockResolvedValueOnce(jsonResponse({ authenticated: true }));
    vi.stubGlobal('fetch', fetchMock);

    await managementFetchJson('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify({ username: 'lvtrieu' }),
    });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[1][0]).toBe('/meeting-service/api/auth/register');
    expect(fetchMock.mock.calls[1][1]).toMatchObject({
      method: 'POST',
      credentials: 'include',
      cache: 'no-store',
    });
    expect((fetchMock.mock.calls[1][1]?.headers as Headers).get('X-XSRF-TOKEN')).toBe('csrf-token');
  });

  it('does not send a mutation request when CSRF initialization fails', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({}, { ok: false, status: 500 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(
      managementFetchJson('/api/auth/register', {
        method: 'POST',
        body: JSON.stringify({ username: 'lvtrieu' }),
      }),
    ).rejects.toThrow('Không thể khởi tạo phiên bảo mật.');

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe('/meeting-service/api/auth/csrf');
  });

  it('adds CSRF and credentials to blob mutation requests', async () => {
    const pdfBlob = new Blob(['pdf'], { type: 'application/pdf' });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-token', headerName: 'X-XSRF-TOKEN' }))
      .mockResolvedValueOnce(blobResponse(pdfBlob));
    vi.stubGlobal('fetch', fetchMock);

    await managementFetchBlob('/api/meetings/12/minutes/versions/2/export/pdf', {
      method: 'POST',
      body: JSON.stringify({
        templateCode: 'CUSC_STANDARD',
      }),
    });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[1][0]).toBe(
      '/meeting-service/api/meetings/12/minutes/versions/2/export/pdf',
    );
    expect(fetchMock.mock.calls[1][1]).toMatchObject({
      method: 'POST',
      credentials: 'include',
      cache: 'no-store',
    });
    expect((fetchMock.mock.calls[1][1]?.headers as Headers).get('X-XSRF-TOKEN')).toBe('csrf-token');
  });

  it('returns the blob response body', async () => {
    const pdfBlob = new Blob(['pdf'], { type: 'application/pdf' });
    const fetchMock = vi.fn().mockResolvedValue(blobResponse(pdfBlob));
    vi.stubGlobal('fetch', fetchMock);

    const result = await managementFetchBlob('/api/meetings/12/minutes/versions/2/export/pdf');

    expect(result.blob).toBe(pdfBlob);
  });

  it('throws ManagementApiError for failed blob responses', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(
        {
          message: 'Chỉ có thể xuất PDF từ phiên bản biên bản đã hoàn tất',
        },
        { ok: false, status: 400 },
      ),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(
      managementFetchBlob('/api/meetings/12/minutes/versions/2/export/pdf'),
    ).rejects.toMatchObject({
      name: 'ManagementApiError',
      status: 400,
      message: 'Chỉ có thể xuất PDF từ phiên bản biên bản đã hoàn tất',
    } satisfies Partial<ManagementApiError>);
  });
});

describe('bounded management transport', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('times out a stuck CSRF read without issuing the mutation', async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn().mockImplementation(() => new Promise(() => {}));
    vi.stubGlobal('fetch', fetchMock);
    const request = managementFetchJson('/api/meetings', {
      method: 'POST',
      body: '{}',
      timeoutMs: 10,
    });
    const result = expect(request).rejects.toMatchObject({
      code: 'REQUEST_TIMEOUT',
      outcomeUnknown: true,
    });
    await vi.advanceTimersByTimeAsync(10);
    await result;
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
  });
  it('includes response JSON consumption in the deadline', async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ...jsonResponse({}), json: () => new Promise(() => {}) }),
    );
    const request = managementFetchJson('/api/meetings/1', { timeoutMs: 20 });
    const result = expect(request).rejects.toBeInstanceOf(ManagementTransportError);
    await vi.advanceTimersByTimeAsync(20);
    await result;
    expect(vi.getTimerCount()).toBe(0);
  });
  it('includes blob response consumption in the deadline', async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ...blobResponse(new Blob()), blob: () => new Promise(() => {}) }),
    );
    const request = managementFetchBlob('/api/documents/1', { timeoutMs: 20 });
    const result = expect(request).rejects.toMatchObject({
      code: 'REQUEST_TIMEOUT',
      outcomeUnknown: false,
    });
    await vi.advanceTimersByTimeAsync(20);
    await result;
  });
  it('cancels CSRF initialization immediately and never dispatches the old write', async () => {
    let resolveCsrf!: (value: Response) => void;
    const fetchMock = vi.fn().mockImplementation(
      () =>
        new Promise<Response>((resolve) => {
          resolveCsrf = resolve;
        }),
    );
    vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController();
    const request = managementFetchJson('/api/meetings', {
      method: 'POST',
      body: '{}',
      signal: controller.signal,
    });
    const result = expect(request).rejects.toMatchObject({ name: 'AbortError' });
    controller.abort();
    await result;
    resolveCsrf(jsonResponse({ token: 'old-csrf' }));
    await Promise.resolve();
    await Promise.resolve();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it('does not start any fetch for an already cancelled request', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController();
    controller.abort();
    await expect(
      managementFetchJson('/api/meetings', { signal: controller.signal }),
    ).rejects.toMatchObject({ name: 'AbortError' });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe('Management API error diagnostics', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const clients = [
    { name: 'JSON', request: managementFetchJson },
    { name: 'blob', request: managementFetchBlob },
    { name: 'void', request: managementFetchVoid },
  ];

  it('keeps the two-argument error constructor compatible', () => {
    const error = new ManagementApiError('Thông báo đã có.', 403);
    expect(error).toBeInstanceOf(Error);
    expect(error).toMatchObject({
      name: 'ManagementApiError',
      message: 'Thông báo đã có.',
      status: 403,
    });
    expect(error.code).toBeUndefined();
  });

  it.each(clients)(
    'retains backend code and HTTP status for $name failures without credential fallback',
    async ({ request }) => {
      const fetchMock = vi.fn().mockResolvedValue(
        jsonResponse(
          {
            code: 'QA_CONNECTION_NOT_CONNECTED',
            message: 'Bạn không có quyền thực hiện thao tác hỏi đáp này.',
            status: 200,
          },
          { ok: false, status: 403 },
        ),
      );
      vi.stubGlobal('fetch', fetchMock);

      const error = await request('/api/meetings/35/qa/context', {
        headers: { 'X-Meeting-Participant-Token': 'dummy-room-token' },
      }).catch((failure) => failure);

      expect(error).toBeInstanceOf(ManagementApiError);
      expect(error).toMatchObject({
        code: 'QA_CONNECTION_NOT_CONNECTED',
        status: 403,
        message: 'Bạn không có quyền thực hiện thao tác hỏi đáp này.',
      });
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(fetchMock.mock.calls[0][1]).toMatchObject({
        credentials: 'include',
        cache: 'no-store',
      });
      expect(
        (fetchMock.mock.calls[0][1].headers as Headers).get('X-Meeting-Participant-Token'),
      ).toBe('dummy-room-token');
    },
  );

  it.each(clients)(
    'preserves a legacy plain-text $name failure without inventing a code',
    async ({ request }) => {
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue(
          new Response('Quyền truy cập đã bị từ chối.', {
            status: 403,
            headers: { 'Content-Type': 'text/plain' },
          }),
        ),
      );
      const error = await request('/api/meetings/35/qa/context').catch((failure) => failure);
      expect(error).toBeInstanceOf(ManagementApiError);
      expect(error).toMatchObject({ status: 403, message: 'Quyền truy cập đã bị từ chối.' });
      expect(error.code).toBeUndefined();
    },
  );

  it.each([
    null,
    403,
    true,
    { name: 'QA_CONNECTION_NOT_CONNECTED' },
    ['QA_CONNECTION_NOT_CONNECTED'],
  ])('ignores non-string backend codes while preserving controlled messages: %j', async (code) => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse({ code, message: 'Thông báo backend.' }, { ok: false, status: 403 }),
        ),
    );
    const error = await managementFetchJson('/api/meetings/35/qa/context').catch(
      (failure) => failure,
    );
    if (!(error instanceof ManagementApiError)) {
      throw new Error('Expected an API error for the rejected response.');
    }
    expect(error).toMatchObject({ message: 'Thông báo backend.', status: 403 });
    expect(error.code).toBeUndefined();
  });

  it('retains other backend codes and the legacy error-field fallback', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse(
            { code: 'MEETING_ACCESS_DENIED', error: 'Forbidden' },
            { ok: false, status: 403 },
          ),
        ),
    );
    await expect(managementFetchJson('/api/meetings/35')).rejects.toMatchObject({
      code: 'MEETING_ACCESS_DENIED',
      message: 'Forbidden',
      status: 403,
    });
  });

  it('keeps successful JSON responses successful even if their body includes a code', async () => {
    const payload = { code: 'QA_CONNECTION_NOT_CONNECTED', enabled: true };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(payload)));
    await expect(managementFetchJson('/api/meetings/35/qa/context')).resolves.toEqual(payload);
  });

  it('keeps successful void responses body-free', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
    await expect(managementFetchVoid('/api/meetings/35')).resolves.toBeUndefined();
  });
});
