import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, api } from './api.ts';
import type { BrowserSession } from './api.ts';
import type { ExpenseRecord } from './expense.ts';
import type { ExpenseSyncState } from './expenseSync.ts';
import { sameScope } from './protocol.ts';
import type { LifeStore } from './store.ts';

export function useExpenseSync(store: LifeStore, session: BrowserSession | null, online: boolean, visible: boolean) {
  const [state, setState] = useState<ExpenseSyncState | null>(null);
  const [status, setStatus] = useState('');
  const [generation, setGeneration] = useState(0);
  const [review, setReview] = useState<{ count: string; local: ExpenseRecord[]; session: BrowserSession } | null>(null);
  const [retryAt, setRetryAt] = useState(0);
  const running = useRef(false);
  const failures = useRef(0);
  const retry = useRef(0);

  const refresh = useCallback(async () => {
    setState(await store.expenseSyncState());
    setGeneration(value => value + 1);
  }, [store]);

  useEffect(() => { void refresh().catch(() => setStatus('Could not read the expense connection.')); }, [refresh]);

  const sync = useCallback(async (current: BrowserSession) => {
    if (running.current || !navigator.onLine || Date.now() < retry.current) return;
    running.current = true;
    try {
      let local = await store.expenseSyncState();
      if (!local) return;
      if (!sameScope(local.scope, current.scope)) throw new Error('Review your expense connection. Local changes were kept.');
      setStatus('Syncing expenses…');
      let reviewChanged = false;
      for (let index = 0; index < 4; index++) {
        for (const operation of local.pending.slice(0, 20).filter(item => !local!.acknowledged.includes(item.id))) {
          try { await store.acknowledgeExpense(current.scope, await api.pushExpense(current, operation)); }
          catch (error) {
            if (!(error instanceof ApiError && error.code === 'CONFLICT_CHANGED') ||
                (operation.mutation !== 'RESOLVE' && operation.mutation !== 'RESTORE')) throw error;
            await store.rejectExpenseResolution(current.scope, operation.id);
            reviewChanged = true;
          }
        }
        const page = await api.pullExpenses(current, local.cursor);
        await store.replayExpenses(current.scope, local.cursor, page.entries);
        local = await store.expenseSyncState();
        if (!local) throw new Error('Expense connection changed.');
        if (!page.hasMore && !local.pending.length) {
          retry.current = 0;
          failures.current = 0;
          setRetryAt(0);
          setStatus(reviewChanged ? 'Expense versions changed. Review them again.' : 'Expenses checked with the server.');
          return;
        }
      }
      retry.current = Date.now() + 1000;
      setRetryAt(retry.current);
      setStatus('More expense changes are waiting.');
    } catch (failure) {
      if (failure instanceof ApiError ? failure.status >= 500 || failure.status === 429 :
        failure instanceof Error && failure.cause !== undefined) {
        const delay = Math.min(300_000, 3000 * 2 ** Math.min(failures.current++, 7)) + Math.random() * 1500;
        retry.current = Math.max(Date.now() + delay, failure instanceof ApiError ? failure.retryAfter ?? 0 : 0);
        setRetryAt(retry.current);
      }
      setStatus(failure instanceof Error ? failure.message : 'Expense synchronization needs attention.');
    } finally {
      running.current = false;
      await refresh().catch(() => setStatus('Could not read saved expenses.'));
    }
  }, [refresh, store]);

  const dataset = session?.scope.dataset;
  const epoch = session?.scope.epoch;
  // The server reuses the session token on status checks. Refresh the ref without
  // restarting the timer every time another module checks its own session.
  const current = useRef(session);
  useEffect(() => { current.current = session; }, [session]);
  useEffect(() => {
    const signedIn = current.current;
    if (!signedIn || !online || !visible) return;
    void sync(signedIn);
    const timer = window.setInterval(() => {
      if (current.current) void sync(current.current);
    }, 5 * 60 * 1000);
    return () => window.clearInterval(timer);
  }, [dataset, epoch, online, sync, visible]);
  useEffect(() => {
    if (!retryAt || !online || !visible || !current.current) return;
    const timer = window.setTimeout(() => {
      if (current.current) void sync(current.current);
    }, Math.min(2_147_483_647, Math.max(0, retryAt - Date.now())));
    return () => window.clearTimeout(timer);
  }, [online, retryAt, sync, visible]);

  async function reviewConnection() {
    try {
      if (!session) throw new Error('Sign in under Account & Security to connect expenses.');
      const local = await store.expenseRecords();
      setReview({ count: await api.expenseInventory(session), local, session });
      setStatus('');
    } catch (failure) { setStatus(failure instanceof Error ? failure.message : 'Could not review expenses.'); }
  }

  async function bind() {
    if (!review || !session || !sameScope(review.session.scope, session.scope)) return;
    try {
      if (await api.expenseInventory(session) !== review.count) throw new Error('Server expenses changed. Review again.');
      await store.bindExpenses(session.scope, review.local);
      setReview(null);
      await sync(session);
    } catch (failure) { setStatus(failure instanceof Error ? failure.message : 'Could not connect expenses.'); }
  }

  async function resolve(selected: string, heads: string[]) {
    if (!state) return;
    try {
      await store.resolveExpense(state.scope, selected, heads);
      await refresh();
      if (session) await sync(session);
    } catch (failure) { setStatus(failure instanceof Error ? failure.message : 'Could not resolve the expense.'); }
  }

  function committed() {
    void refresh().catch(() => setStatus('Could not read saved expenses.'));
    if (session) {
      if (!running.current) void sync(session);
      else if (!retry.current) {
        retry.current = Date.now() + 1000;
        setRetryAt(retry.current);
      }
    }
  }

  return { state, status, generation, review, reviewConnection, bind, dismissReview: () => setReview(null), resolve,
    committed, syncNow: () => { if (session) void sync(session); } };
}
