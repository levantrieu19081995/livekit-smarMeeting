import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  loginManagementAuth,
  logoutManagementAuth,
  registerManagementAuth,
} from './managementAuthService';

function jsonResponse(payload: unknown, init: Partial<Response> = {}): Response {
  return {
    ok: init.ok ?? true,
    status: init.status ?? 200,
    headers: new Headers({ 'content-type': 'application/json' }),
    json: async () => payload,
    text: async () => JSON.stringify(payload),
  } as Response;
}

function csrfResponse() {
  return jsonResponse({
    token: 'csrf-token',
    headerName: 'X-XSRF-TOKEN',
    parameterName: '_csrf',
  });
}

function authResponse() {
  return jsonResponse({
    authenticated: true,
    user: null,
  });
}

describe('managementAuthService CSRF flow', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('gets CSRF before register and sends X-XSRF-TOKEN with credentials', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(csrfResponse()).mockResolvedValueOnce(authResponse());
    vi.stubGlobal('fetch', fetchMock);

    await registerManagementAuth({
      username: 'lvtrieu',
      fullName: 'LeVanTrieu',
      email: 'lvtrieu@ctu.edu.vn',
      password: 'password-123',
    });

    expect(fetchMock.mock.calls[0][0]).toBe('/meeting-service/api/auth/csrf');
    expect(fetchMock.mock.calls[1][0]).toBe('/meeting-service/api/auth/register');
    expect(fetchMock.mock.calls[1][1]).toMatchObject({
      method: 'POST',
      credentials: 'include',
    });
    expect((fetchMock.mock.calls[1][1]?.headers as Headers).get('X-XSRF-TOKEN')).toBe(
      'csrf-token',
    );
  });

  it('gets CSRF before login while preserving the login body', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(csrfResponse()).mockResolvedValueOnce(authResponse());
    vi.stubGlobal('fetch', fetchMock);

    await loginManagementAuth('lvtrieu', 'password-123');

    expect(fetchMock.mock.calls[0][0]).toBe('/meeting-service/api/auth/csrf');
    expect(fetchMock.mock.calls[1][0]).toBe('/meeting-service/api/auth/login');
    expect(JSON.parse(String(fetchMock.mock.calls[1][1]?.body))).toEqual({
      usernameOrEmail: 'lvtrieu',
      password: 'password-123',
    });
    expect((fetchMock.mock.calls[1][1]?.headers as Headers).get('X-XSRF-TOKEN')).toBe(
      'csrf-token',
    );
  });

  it('gets CSRF before logout', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(csrfResponse())
      .mockResolvedValueOnce(jsonResponse(undefined, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);

    await logoutManagementAuth();

    expect(fetchMock.mock.calls[0][0]).toBe('/meeting-service/api/auth/csrf');
    expect(fetchMock.mock.calls[1][0]).toBe('/meeting-service/api/auth/logout');
    expect((fetchMock.mock.calls[1][1]?.headers as Headers).get('X-XSRF-TOKEN')).toBe(
      'csrf-token',
    );
  });

  it('does not POST register when CSRF cannot be loaded', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse({}, { ok: false, status: 500 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(
      registerManagementAuth({
        username: 'lvtrieu',
        fullName: 'LeVanTrieu',
        email: 'lvtrieu@ctu.edu.vn',
        password: 'password-123',
      }),
    ).rejects.toThrow('Không thể khởi tạo phiên bảo mật.');

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
