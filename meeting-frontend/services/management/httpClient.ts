const MANAGEMENT_API_PROXY_PREFIX = '/meeting-service';
const DEFAULT_CSRF_HEADER_NAME = 'X-XSRF-TOKEN';
export const MANAGEMENT_REQUEST_TIMEOUT_MS = 30000;
export type ManagementRequestInit = RequestInit & { timeoutMs?: number };

export class ManagementTransportError extends Error {
  constructor(
    public readonly code: 'REQUEST_TIMEOUT' | 'NETWORK_UNAVAILABLE',
    public readonly outcomeUnknown: boolean,
  ) {
    super(
      outcomeUnknown
        ? 'Máy chủ chưa xác nhận thao tác. Hãy tải lại để kiểm tra trước khi thử lại.'
        : 'Không thể kết nối máy chủ. Kiểm tra kết nối và thử lại.',
    );
    this.name = 'ManagementTransportError';
  }
}

async function boundedRequest<T>(
  init: ManagementRequestInit | undefined,
  operation: (signal: AbortSignal) => Promise<T>,
): Promise<T> {
  const controller = new AbortController();
  const signal = init?.signal;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let rejectAbort: (reason: unknown) => void = () => {};
  const cancelled = new Promise<never>((_, reject) => {
    rejectAbort = reject;
  });
  const abort = () => {
    controller.abort(signal?.reason);
    rejectAbort(signal?.reason ?? new DOMException('Request cancelled', 'AbortError'));
  };
  if (signal?.aborted) abort();
  else signal?.addEventListener('abort', abort, { once: true });
  const duration = init?.timeoutMs ?? MANAGEMENT_REQUEST_TIMEOUT_MS;
  timer = setTimeout(() => {
    const failure = new ManagementTransportError('REQUEST_TIMEOUT', isMutationMethod(init?.method));
    controller.abort(failure);
    rejectAbort(failure);
  }, duration);
  try {
    if (signal?.aborted) return await cancelled;
    return await Promise.race([operation(controller.signal), cancelled]);
  } catch (failure) {
    if (controller.signal.aborted) throw controller.signal.reason ?? failure;
    if (failure instanceof ManagementApiError || failure instanceof ManagementTransportError)
      throw failure;
    throw new ManagementTransportError('NETWORK_UNAVAILABLE', isMutationMethod(init?.method));
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', abort);
  }
}

export type CsrfResponse = {
  token: string;
  headerName?: string;
  parameterName?: string;
};

function isMutationMethod(method?: string) {
  if (!method) {
    return false;
  }
  const normalized = method.toUpperCase();
  return (
    normalized === 'POST' ||
    normalized === 'PUT' ||
    normalized === 'PATCH' ||
    normalized === 'DELETE'
  );
}

export function toManagementApiUrl(path: string): string {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`;
  return `${MANAGEMENT_API_PROXY_PREFIX}${normalizedPath}`;
}

function normalizeCsrfResponse(payload: unknown): CsrfResponse | null {
  const candidate =
    payload != null &&
    typeof payload === 'object' &&
    'payload' in payload &&
    (payload as { payload?: unknown }).payload != null
      ? (payload as { payload?: unknown }).payload
      : payload;

  if (candidate == null || typeof candidate !== 'object') {
    return null;
  }

  const token = (candidate as { token?: unknown }).token;
  if (typeof token !== 'string' || token.length === 0) {
    return null;
  }

  const headerName = (candidate as { headerName?: unknown }).headerName;
  const parameterName = (candidate as { parameterName?: unknown }).parameterName;

  return {
    token,
    headerName: typeof headerName === 'string' && headerName.length > 0 ? headerName : undefined,
    parameterName:
      typeof parameterName === 'string' && parameterName.length > 0 ? parameterName : undefined,
  };
}

async function loadCsrfToken(signal: AbortSignal): Promise<CsrfResponse> {
  const response = await fetch(toManagementApiUrl('/api/auth/csrf'), {
    method: 'GET',
    cache: 'no-store',
    credentials: 'include',
    signal,
    headers: {
      Accept: 'application/json',
    },
  });

  if (!response.ok) {
    throw new ManagementApiError('Không thể khởi tạo phiên bảo mật.', response.status);
  }

  const csrf = normalizeCsrfResponse(await response.json());
  if (!csrf) {
    throw new ManagementApiError('Máy chủ không trả về CSRF token.', 502);
  }

  return csrf;
}

export function getCsrfToken(signal?: AbortSignal): Promise<CsrfResponse> {
  return boundedRequest({ signal }, loadCsrfToken);
}

async function buildHeaders(init: RequestInit | undefined, signal: AbortSignal) {
  const headers = new Headers(init?.headers);

  headers.set('Accept', 'application/json');

  if (init?.body != null && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  if (isMutationMethod(init?.method)) {
    const csrf = await loadCsrfToken(signal);
    headers.set(csrf.headerName ?? DEFAULT_CSRF_HEADER_NAME, csrf.token);
  }

  return headers;
}

async function parseError(response: Response): Promise<{ message: string; code?: string }> {
  const contentType = response.headers.get('content-type') || '';

  if (contentType.includes('application/json')) {
    const payload = (await response.json()) as {
      message?: string;
      error?: string;
      code?: unknown;
    };

    return {
      message:
        typeof payload?.message === 'string'
          ? payload.message.slice(0, 2000)
          : typeof payload?.error === 'string'
            ? payload.error.slice(0, 2000)
            : 'Yêu cầu management API thất bại.',
      code: typeof payload?.code === 'string' ? payload.code : undefined,
    };
  }

  const text = await response.text();
  return { message: text.slice(0, 2000) || 'Yêu cầu management API thất bại.' };
}

export class ManagementApiError extends Error {
  status: number;
  code?: string;

  constructor(message: string, status: number, code?: string) {
    super(message);
    this.name = 'ManagementApiError';
    this.status = status;
    this.code = code;
  }
}

async function requestResponse(
  path: string,
  init: ManagementRequestInit | undefined,
  signal: AbortSignal,
) {
  const headers = await buildHeaders(init, signal);
  if (signal.aborted) throw signal.reason ?? new DOMException('Request cancelled', 'AbortError');
  const { timeoutMs: _timeoutMs, ...request } = init ?? {};
  return fetch(toManagementApiUrl(path), {
    ...request,
    signal,
    cache: 'no-store',
    credentials: 'include',
    headers,
  });
}

export function managementFetch(path: string, init?: ManagementRequestInit): Promise<Response> {
  return boundedRequest(init, (signal) => requestResponse(path, init, signal));
}

async function requireResponse(response: Response) {
  if (!response.ok) {
    const error = await parseError(response);
    throw new ManagementApiError(error.message, response.status, error.code);
  }
}

export function managementFetchJson<T>(path: string, init?: ManagementRequestInit): Promise<T> {
  return boundedRequest(init, async (signal) => {
    const response = await requestResponse(path, init, signal);
    await requireResponse(response);
    return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
  });
}

export async function managementFetchBlob(
  path: string,
  init?: ManagementRequestInit,
): Promise<{
  blob: Blob;
  response: Response;
}> {
  return boundedRequest(init, async (signal) => {
    const response = await requestResponse(path, init, signal);
    await requireResponse(response);
    return { blob: await response.blob(), response };
  });
}

export function managementFetchVoid(path: string, init?: ManagementRequestInit): Promise<void> {
  return boundedRequest(init, async (signal) =>
    requireResponse(await requestResponse(path, init, signal)),
  );
}

export function managementFetchStatus(
  path: string,
  init?: ManagementRequestInit,
): Promise<{ status: number; headers: Headers }> {
  return boundedRequest(init, async (signal) => {
    const response = await requestResponse(path, init, signal);
    await requireResponse(response);
    return { status: response.status, headers: response.headers };
  });
}
