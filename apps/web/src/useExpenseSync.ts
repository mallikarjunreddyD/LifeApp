import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, api } from './api.ts';
import type { BrowserSession } from './api.ts';
import type { ExpenseSyncState } from './expenseSync.ts';
import { competingExpenseHeads } from './expenseSync.ts';
import { sameScope } from './protocol.ts';
import type { LifeStore } from './store.ts';

export function useExpenseSync(store: LifeStore, onCommitted: () => void) {
  const [state, setState] = useState<ExpenseSyncState | null>(null);
  const [error, setError] = useState('');
  const [generation, setGeneration] = useState(0);
  const running = useRef(false);

  const refresh = useCallback(async () => {
    setState(await store.expenseSyncState());
    setGeneration(value => value + 1);
  }, [store]);

  useEffect(() => { void refresh().catch(() => setError('Could not read the expense connection.')); }, [refresh]);

  const sync = useCallback(async (current: BrowserSession) => {
    if (running.current) throw new Error("Expense sync is already running.");
    running.current = true;
    try {
      let local = await store.expenseSyncState();
      if (!local) return;
      if (!sameScope(local.scope, current.scope)) throw new Error('Review your expense connection. Local changes were kept.');
      setError('');
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
          return !reviewChanged && !competingExpenseHeads(local).length;
        }
      }
      return false;
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Expense synchronization needs attention.');
      throw failure;
    } finally {
      running.current = false;
      await refresh().catch(() => setError('Could not read saved expenses.'));
    }
  }, [refresh, store]);

  async function resolve(selected: string, heads: string[]) {
    if (!state) return;
    try {
      await store.resolveExpense(state.scope, selected, heads);
      await refresh();
      onCommitted();
    } catch (failure) { setError(failure instanceof Error ? failure.message : 'Could not resolve the expense.'); }
  }

  function committed() {
    void refresh().catch(() => setError('Could not read saved expenses.'));
    onCommitted();
  }

  return { state, error, generation, refresh, resolve,
    committed, run: sync };
}
