import { decodeExpenseCommit } from './expenseSync.ts';
import type { ExpenseOperation } from './expenseSync.ts';
import { instant, object, pageOf, receiptOf, scopeOf, sequence, uuid } from './protocol.ts';
import type { Checkpoint, Operation, Scope } from './protocol.ts';
import type { LifeStore } from './store.ts';

export interface BrowserSession { scope: Scope; expiresAt: string; csrfToken: string }

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly retryAfter: number | null;
  constructor(status: number, code: string, retryAfter: number | null = null) {
    const message = status === 401 ? 'Sign in again to synchronize.' :
      status === 403 ? 'The server declined this request.' :
      status === 429 ? 'Too many requests. Wait before trying again.' :
      status === 410 || code === 'SCOPE_MISMATCH' ? 'Review your connection before syncing again. Your local changes were kept.' :
      code === 'CONFLICT_CHANGED' ? 'The server conflict changed. Review the current versions.' :
      status >= 500 ? 'The server is unavailable. Local changes were kept.' :
      'The server did not accept this request. Local changes were kept.';
    super(message);
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
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
        const retry = response.headers.get('Retry-After');
        const deadline = retry === null ? NaN : /^\d+$/.test(retry) ? Date.now() + Number(retry) * 1000 : Date.parse(retry);
        throw new ApiError(response.status, code, Number.isFinite(deadline) ? deadline : null);
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

export async function migrateJournal(session: BrowserSession, entryId: string) {
  const result = object(await json('/v1/journal/entries/' + uuid(entryId) + '/migrate', {
    method: 'POST', headers: { ...journalHeaders(session.scope), 'X-CSRF-Token': session.csrfToken },
  }), ['versionId']);
  return uuid(result.versionId);
}

export function editableVersions(scope: Scope, after: string, signal: AbortSignal) {
  const query = new URLSearchParams({ after, limit: '50' });
  return json('/v1/editable/versions?' + query, { headers: journalHeaders(scope), signal });
}

export function editablePreview(scope: Scope, version: string, signal: AbortSignal) {
  return json('/v1/editable/versions/' + uuid(version) + '/preview?habitIds=true', { headers: journalHeaders(scope), signal });
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

export interface HabitCheck { id: string; habitId: string; date: string; checked: boolean }

export async function saveHabitCheck(session: BrowserSession, base: string, edit: HabitCheck) {
  const result = object(await json('/v1/editable/versions/' + uuid(base) + '/habit-check', {
    method: 'POST',
    headers: { ...journalHeaders(session.scope), 'X-CSRF-Token': session.csrfToken, 'Content-Type': 'application/json' },
    body: JSON.stringify(edit),
  }), ['versionId', 'conflicted']);
  if (uuid(result.versionId) !== edit.id || typeof result.conflicted !== 'boolean') throw new Error('Invalid habit receipt.');
  return { versionId: edit.id, conflicted: result.conflicted };
}

export const api = {
  async login(username: string, password: string, secondFactor?: string) {
    return browserSession(await json('/v1/session', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ client: 'browser', username, password, secondFactor }),
    }));
  },
  async session() { return browserSession(await json('/v1/session')); },
  async expenseInventory(session: BrowserSession) {
    const value = object(await json('/v1/expenses/inventory', { headers: journalHeaders(session.scope) }), ['count']);
    return sequence(value.count, false);
  },
  async pullExpenses(session: BrowserSession, cursor: string) {
    const value = object(await json('/v1/expenses/pull?after=' + sequence(cursor, false),
      { headers: journalHeaders(session.scope) }), ['entries', 'hasMore']);
    if (!Array.isArray(value.entries) || value.entries.length > 32 || typeof value.hasMore !== 'boolean' ||
        (!value.entries.length && value.hasMore)) throw new Error('Invalid expense page.');
    return { entries: value.entries.map(decodeExpenseCommit), hasMore: value.hasMore };
  },
  async pushExpense(session: BrowserSession, operation: ExpenseOperation) {
    return decodeExpenseCommit(await json('/v1/expenses/push', {
      method: 'POST',
      headers: { ...journalHeaders(session.scope), 'X-CSRF-Token': session.csrfToken, 'Content-Type': 'application/json' },
      body: JSON.stringify(operation),
    }));
  },
  async backupStatus() {
    const value = object(await json('/v1/backups/status'), ['state', 'lastVerifiedAt']);
    if (value.state !== 'never' && value.state !== 'running' && value.state !== 'verified' && value.state !== 'failed') {
      throw new Error('Invalid backup status.');
    }
    return { state: value.state, lastVerifiedAt: value.lastVerifiedAt === null ? null : instant(value.lastVerifiedAt) };
  },
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
