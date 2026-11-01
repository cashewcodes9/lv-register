import createClient from "openapi-fetch";
import type { paths } from "./schema";

/** Empty in production (same origin as Spring Boot); http://localhost:8080 for the dev server. */
export const API_BASE: string = import.meta.env.VITE_API_BASE ?? "";

/** Where the Pipeline page links Dagster runs. */
export const DAGSTER_URL: string = import.meta.env.VITE_DAGSTER_URL ?? "http://localhost:3000";

export const api = createClient<paths>({ baseUrl: API_BASE });

/** An error response of the API (RFC 9457 ProblemDetail). */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

interface Problem {
  title?: string;
  detail?: string;
}

/** The data of a response, or an ApiError with the ProblemDetail's detail. */
export async function unwrap<T>(call: Promise<{ data?: T; error?: unknown; response: Response }>): Promise<T> {
  const { data, error, response } = await call;
  if (data !== undefined && response.ok) {
    return data;
  }
  const problem = (error ?? {}) as Problem;
  throw new ApiError(response.status, problem.detail ?? problem.title ?? `HTTP ${response.status}`);
}
