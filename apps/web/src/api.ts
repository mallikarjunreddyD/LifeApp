import { instant, object, pageOf, receiptOf, scopeOf, uuid } from './protocol.ts';
import type { Checkpoint, Operation, Scope } from './protocol.ts';
import type { LifeStore } from './store.ts';

export interface BrowserSession { scope: Scope; expiresAt: string; csrfToken: string }

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  constructor(status: number, code: string) {
    const message = status === 401 ? 'Sign in again to synchronize.' :
      status === 403 ? 'The server declined this request.' :
      status === 429 ? 'Too many requests. Wait before trying again.' :
      status === 410 || code === 'SCOPE_MISMATCH' ? 'The server dataset or cursor changed. Local changes were kept.' :
      code === 'CONFLICT_CHANGED' ? 'The server conflict changed. Review the current versions.' :
      status >= 500 ? 'The server is unavailable. Local changes were kept.' :
      'The server did not accept this request. Local changes were kept.';
    super(message);
    this.status = status;
    this.code = code;
  }
}

export async function withRequestTimeout<T>(
  timeoutMillis: number, signal: AbortSignal | null | undefined, run: (signal: AbortSignal) => Promise<T>,
): Promise<T> {
  const controller = new AbortController();
  const abort = () => controller.abort();
  if (signal?.aborted) abort();
  else signal?.addEventListener('abort', abort, { once: true });
  const timeout = setTimeout(abort, timeoutMillis);
  try {
    return await run(controller.signal);
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener('abort', abort);
  }
}

async function json(path: string, options: RequestInit = {}, empty = false): Promise<unknown> {
  try {
    return await withRequestTimeout(15_000, options.signal, async signal => {
      const response = await fetch(path, { ...options, credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal });
      if (!response.ok) {
        let code = '';
        // Structured error codes are safe to use. Do not surface raw response text.
        if (response.headers.get('content-type')?.includes('application/json')) {
          try {
            const error = await readJson(response, 16_384) as { code?: unknown };
            if (typeof error?.code === 'string') code = error.code;
          } catch { /* Retain the HTTP status. */ }
        }
        throw new ApiError(response.status, code);
      }
      if (empty) return undefined;
      if (!response.headers.get('content-type')?.includes('application/json')) throw new Error('Expected a LifeApp JSON response.');
      return await readJson(response, 8 * 1024 * 1024);
    });
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new Error('Could not read a response from LifeApp. Local changes were kept.', { cause: error });
  }
}

async function readJson(response: Response, limit: number): Promise<unknown> {
  if (Number(response.headers.get('content-length') ?? 0) > limit) throw new Error('Response exceeds size limit.');
  const reader = response.body?.getReader();
  if (!reader) throw new Error('Missing response body.');
  let size = 0;
  let text = '';
  const decoder = new TextDecoder('utf-8', { fatal: true });
  try {
    for (;;) {
      const chunk = await reader.read();
      if (chunk.done) break;
      size += chunk.value.byteLength;
      if (size > limit) throw new Error('Response exceeds size limit.');
      text += decoder.decode(chunk.value, { stream: true });
    }
    return JSON.parse(text + decoder.decode()) as unknown;
  } catch (error) {
    void reader.cancel().catch(() => {});
    throw error;
  } finally {
    reader.releaseLock();
  }
}

function browserSession(value: unknown): BrowserSession {
  const session = object(value, ['scope', 'expiresAt', 'csrfToken']);
  if (typeof session.csrfToken !== 'string' || !/^[a-zA-Z0-9_-]{43}$/.test(session.csrfToken)) {
    throw new Error('The server did not provide a browser session.');
  }
  return { scope: scopeOf(session.scope), expiresAt: instant(session.expiresAt), csrfToken: session.csrfToken };
}

export function journalHeaders(scope: Scope) {
  return { 'X-LifeApp-Dataset': scope.dataset, 'X-LifeApp-Epoch': scope.epoch };
}

export async function journalList(path: string, scope: Scope, cursor: string | null, signal: AbortSignal) {
  const query = new URLSearchParams({ limit: '50' });
  if (cursor) query.set('cursor', cursor);
  const result = object(await json(path + '?' + query, { headers: journalHeaders(scope), signal }), ['items', 'next']);
  if (!Array.isArray(result.items) || result.items.length > 50) throw new Error('Invalid journal list.');
  const next = result.next === null ? null : uuid(result.next);
  if (next && (next === cursor || result.items.length !== 50)) throw new Error('Invalid journal cursor.');
  return { items: result.items as unknown[], next };
}

export function editableVersions(scope: Scope, after: string, signal: AbortSignal) {
  const query = new URLSearchParams({ after, limit: '50' });
  return json('/v1/editable/versions?' + query, { headers: journalHeaders(scope), signal });
}

export function editablePreview(scope: Scope, version: string, signal: AbortSignal) {
  return json('/v1/editable/versions/' + uuid(version) + '/preview', { headers: journalHeaders(scope), signal });
}

export function editableReview(scope: Scope, version: string, signal: AbortSignal) {
  return json('/v1/editable/versions/' + uuid(version) + '/review', { headers: journalHeaders(scope), signal });
}

export async function resolveEditable(session: BrowserSession, selected: string, id: string, reviewed: string[]) {
  await json('/v1/editable/versions/' + uuid(selected) + '/resolve', {
    method: 'POST',
    headers: { ...journalHeaders(session.scope), 'X-CSRF-Token': session.csrfToken, 'Content-Type': 'application/json' },
    body: JSON.stringify({ id, reviewed }),
  }, true);
}

export const api = {
  async login(username: string, password: string, secondFactor?: string) {
    return browserSession(await json('/v1/session', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ client: 'browser', username, password, secondFactor }),
    }));
  },
  async session() { return browserSession(await json('/v1/session')); },
  async logout(session: BrowserSession) {
    await json('/v1/session', { method: 'DELETE', headers: { 'X-CSRF-Token': session.csrfToken } }, true);
  },
  async pull(checkpoint: Checkpoint) {
    const query = new URLSearchParams({ epoch: checkpoint.scope.epoch, after: checkpoint.sequence });
    return pageOf(await json('/v1/sync/pull?' + query));
  },
  async submit(session: BrowserSession, operation: Operation) {
    const route = operation.parents.length > 1 ? '/v1/conflicts/' + operation.recordId + '/resolve' : '/v1/sync/push';
    return receiptOf(await json(route, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': session.csrfToken },
      body: JSON.stringify({ scope: session.scope, operation }),
    }));
  },
  async read(kind: 'workouts' | 'exercises', cursor?: string) {
    const query = new URLSearchParams({ limit: '10' });
    if (cursor) query.set('cursor', uuid(cursor));
    const page = object(await json('/v1/' + kind + '?' + query), ['items', 'next']);
    if (!Array.isArray(page.items) || page.items.length > 10) throw new Error('Invalid record list.');
    return { items: page.items as unknown[], next: page.next === null ? null : uuid(page.next) };
  },
};

export async function synchronize(store: LifeStore, session: BrowserSession): Promise<boolean> {
  const view = await store.view();
  if (view.checkpoint?.scope.dataset !== session.scope.dataset || view.checkpoint.scope.epoch !== session.scope.epoch) {
    throw new Error('Confirm the dataset in Settings before synchronizing.');
  }
  let checkpoint = view.checkpoint;
  for (let round = 0; round < 10; round++) {
    const page = await api.pull(checkpoint);
    checkpoint = await store.applyPage(checkpoint, page);
    if (page.hasMore) continue;
    const operations = await store.pending(session.scope);
    for (const operation of operations) {
      try {
        const receipt = await api.submit(session, operation);
        await store.acknowledge(session.scope, operation, receipt);
      } catch (error) {
        if (error instanceof ApiError && error.code === 'CONFLICT_CHANGED' && operation.parents.length > 1) {
          await store.retainRejectedResolution(session.scope, operation);
        }
        throw error;
      }
    }
    const tail = await api.pull(checkpoint);
    checkpoint = await store.applyPage(checkpoint, tail);
    if (!tail.hasMore && (await store.view()).pending === 0) {
      await store.markSync(session.scope);
      return true;
    }
  }
  return false;
}
