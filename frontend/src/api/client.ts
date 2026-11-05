import createClient, { type Middleware } from 'openapi-fetch';
import type { paths, components } from './schema';

/** RFC 9457 problem as returned by the console API. */
export interface Problem {
  type?: string;
  title?: string;
  status?: number;
  code?: string;
  detail?: string;
  errors?: { field?: string; message?: string }[];
  current?: Record<string, unknown>;
  missing_variables?: string[];
}

export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem;

  constructor(status: number, problem: Problem) {
    super(problem.detail ?? problem.title ?? `HTTP ${status}`);
    this.status = status;
    this.problem = problem;
  }

  get code(): string | undefined {
    return this.problem.code;
  }
}

export function readCookie(name: string): string | undefined {
  return document.cookie
    .split(';')
    .map((c) => c.trim())
    .find((c) => c.startsWith(`${name}=`))
    ?.substring(name.length + 1);
}

const MUTATING = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

const csrf: Middleware = {
  onRequest({ request }) {
    if (MUTATING.has(request.method)) {
      const token = readCookie('XSRF-TOKEN');
      if (token) request.headers.set('X-XSRF-TOKEN', decodeURIComponent(token));
    }
    return request;
  },
};

export const api = createClient<paths>({
  baseUrl: typeof window !== 'undefined' ? window.location.origin : 'http://localhost',
  credentials: 'include',
  // resolve fetch at call time (lets test doubles such as MSW patch it after module load)
  fetch: (request: Request) => globalThis.fetch(request),
});
api.use(csrf);

/** Unwraps an openapi-fetch result, throwing {@link ApiError} with the problem document on failure. */
export async function unwrap<T>(
  promise: Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<T> {
  const { data, error, response } = await promise;
  if (!response.ok) {
    const problem = (error ?? {}) as Problem;
    throw new ApiError(response.status, problem);
  }
  return data as T;
}

export type Schemas = components['schemas'];
export type Me = Schemas['Me'];
export type Role = NonNullable<Me['role']>;
