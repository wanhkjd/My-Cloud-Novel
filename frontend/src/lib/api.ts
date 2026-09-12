export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
  ) {
    super(message);
  }
}
let csrf: { token: string; headerName: string } | undefined;
export function resetCsrf() {
  csrf = undefined;
}
export async function request<T>(url: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers);
  const method = (options.method ?? 'GET').toUpperCase();
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    csrf ??= await request<{ token: string; headerName: string }>('/api/auth/csrf');
    headers.set(csrf.headerName, csrf.token);
  }
  let response: Response;
  try {
    response = await fetch(url, {
      ...options,
      method,
      headers,
      credentials: 'same-origin',
      signal:
        options.signal ??
        (options.keepalive
          ? undefined
          : AbortSignal.timeout(options.body instanceof FormData ? 120000 : 20000)),
    });
  } catch {
    throw new ApiError('无法连接书房服务，请检查网络或后端是否已启动。', 0);
  }
  const text = await response.text();
  let result: any;
  try {
    result = text ? JSON.parse(text) : null;
  } catch {
    throw new ApiError('服务器返回了无法识别的内容。', response.status);
  }
  if (!response.ok) {
    if (response.status === 401 || response.status === 403) resetCsrf();
    throw new ApiError(result?.message ?? '请求失败，请稍后重试。', response.status);
  }
  return result as T;
}
export function jsonRequest<T>(url: string, method: string, body?: unknown, keepalive = false) {
  return request<T>(url, {
    method,
    body: body === undefined ? undefined : JSON.stringify(body),
    headers: { 'Content-Type': 'application/json' },
    keepalive,
  });
}
export const errorMessage = (error: unknown) =>
  error instanceof Error ? error.message : '发生了意外错误，请重试。';
