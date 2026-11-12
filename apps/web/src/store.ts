import { applyExpenseOperation, competingExpenseHeads, deleted, effectiveExpenseHeads, newExpenseOperation, recordOf, sameOperation as sameExpenseOperation } from './expenseSync.ts';
import type { ExpenseCommit, ExpenseMutation, ExpenseSyncState } from './expenseSync.ts';
import { checkExpenseRecord, expenseDefaults, expenseNameKey } from './expense.ts';
import type { ExpenseRecord } from './expense.ts';
import { checkWorkoutRecords, revise } from './workout.ts';
import type { Session, WorkoutRecord } from './workout.ts';
import { advance, calendarDate, decodeOperation, instantKey, nextSequence, now, sameOperation, sameScope, title } from './protocol.ts';
import type { Checkpoint, Operation, Page, Receipt, Scope, Todo } from './protocol.ts';

interface Settings { deviceId: string; sequence: string; checkpoint: Checkpoint | null; lastSync: string | null }
interface StoredOperation { operation: Operation; queue?: string; rejected?: boolean }
interface Projection { id: string; heads: string[]; record: Todo }
export interface TaskView { record: Todo; head: string | null; conflicts: Operation[]; resolving: boolean }
export interface LocalView { tasks: TaskView[]; pending: number; checkpoint: Checkpoint | null; lastSync: string | null }

function result<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function complete(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onabort = () => reject(transaction.error ?? new Error('Local transaction was canceled.'));
  });
}

export async function openStore(name = 'lifeapp-browser'): Promise<LifeStore> {
  const open = indexedDB.open(name, 3);
  open.onupgradeneeded = event => {
    const db = open.result;
    if (event.oldVersion === 0) {
      db.createObjectStore('settings');
      const ops = db.createObjectStore('operations', { keyPath: 'operation.operationId' });
      ops.createIndex('queue', 'queue');
      ops.createIndex('record', 'operation.recordId');
      db.createObjectStore('tasks', { keyPath: 'id' });
    }
    if (event.oldVersion < 2) db.createObjectStore('workouts', { keyPath: 'value.metadata.id' });
    db.createObjectStore('expenses', { keyPath: 'value.metadata.id' });
  };
  const db = await result(open);
  db.onversionchange = () => db.close();
  return new LifeStore(db);
}

class Transaction {
  readonly settings: IDBObjectStore;
  readonly operations: IDBObjectStore;
  readonly tasks: IDBObjectStore;
  readonly workouts: IDBObjectStore;
  readonly expenses: IDBObjectStore;

  constructor(transaction: IDBTransaction) {
    this.settings = transaction.objectStore('settings');
    this.operations = transaction.objectStore('operations');
    this.tasks = transaction.objectStore('tasks');
    this.workouts = transaction.objectStore('workouts');
    this.expenses = transaction.objectStore('expenses');
  }

  async state(): Promise<Settings> {
    const saved = await result(this.settings.get('device')) as Settings | undefined;
    return saved ?? { deviceId: crypto.randomUUID(), sequence: '0', checkpoint: null, lastSync: null };
  }

  async save(state: Settings) { await result(this.settings.put(state, 'device')); }

  async operation(id: string): Promise<StoredOperation | undefined> {
    return await result(this.operations.get(id)) as StoredOperation | undefined;
  }

  async projection(id: string): Promise<Projection | undefined> {
    return await result(this.tasks.get(id)) as Projection | undefined;
  }

  async project(id: string, heads: string[]) {
    const operations = await Promise.all(heads.map(head => this.operation(head)));
    if (!operations.length || operations.some(op => !op || op.rejected)) throw new Error('A task head is missing.');
    const variants = operations.map(entry => entry!.operation).sort((a, b) =>
      Number(b.record.deleted) - Number(a.record.deleted) || (a.operationId < b.operationId ? 1 : -1));
    await result(this.tasks.put({ id, heads, record: variants[0]!.record } satisfies Projection));
  }

  async accept(operation: Operation, pending: boolean) {
    const existing = await this.operation(operation.operationId);
    if (existing) {
      if (existing.rejected || !sameOperation(existing.operation, operation)) throw new Error('Operation identity collision.');
      // A pull echo is not an upload receipt.
      return;
    }
    for (const parent of operation.parents) {
      const previous = await this.operation(parent);
      if (!previous || previous.rejected || previous.operation.recordId !== operation.recordId ||
          (previous.operation.record.deleted && !operation.record.deleted)) throw new Error('Invalid causal parent.');
    }
    const previous = await this.projection(operation.recordId);
    const heads = (previous?.heads ?? []).filter(head => !operation.parents.includes(head));
    if (heads.length >= 128) throw new Error('Too many unresolved versions for this task.');
    await result(this.operations.put({ operation, queue: pending ? operation.sequence.padStart(19, '0') : undefined }));
    await this.project(operation.recordId, [...heads, operation.operationId]);
  }
}

export class LifeStore {
  #database: IDBDatabase;
  constructor(database: IDBDatabase) { this.#database = database; }
  close() { this.#database.close(); }

  private async use<T>(mode: IDBTransactionMode, work: (tx: Transaction) => Promise<T>): Promise<T> {
    const transaction = this.#database.transaction(['settings', 'operations', 'tasks', 'workouts', 'expenses'], mode);
    const finished = complete(transaction);
    void finished.catch(() => {});
    try {
      const value = await work(new Transaction(transaction));
      await finished;
      return value;
    } catch (error) {
      try { transaction.abort(); } catch { /* It may already be aborted. */ }
      await finished.catch(() => {});
      throw error;
    }
  }

  async seedExpenses(): Promise<void> {
    await this.use('readwrite', async tx => {
      if (await result(tx.expenses.count()) !== 0 || await result(tx.settings.get('expense-sync'))) return;
      for (const label of expenseDefaults()) await result(tx.expenses.add({ kind: 'label', value: label }));
    });
  }

  async expenseRecords(): Promise<ExpenseRecord[]> {
    return this.use('readonly', async tx => await result(tx.expenses.getAll()) as ExpenseRecord[]);
  }

  async expenseSyncState(): Promise<ExpenseSyncState | null> {
    return this.use('readonly', async tx => (await result(tx.settings.get('expense-sync'))) ?? null);
  }

  async bindExpenses(scope: Scope, reviewed: ExpenseRecord[]): Promise<void> {
    await this.use('readwrite', async tx => {
      if (await result(tx.settings.get('expense-sync'))) throw new Error('Expenses are already connected.');
      const records = await result(tx.expenses.getAll()) as ExpenseRecord[];
      if (JSON.stringify(records) !== JSON.stringify(reviewed)) throw new Error('Expenses changed. Review again.');
      const ordered = [...records.filter(record => record.kind === 'label'), ...records.filter(record => record.kind === 'expense')];
      const pending = ordered.map(record => newExpenseOperation(record, record.kind === 'expense' && record.value.deleted ? 'DELETE' : 'PUT'));
      await result(tx.settings.put({ scope, cursor: '0', heads: [], pending, acknowledged: [] } satisfies ExpenseSyncState, 'expense-sync'));
    });
  }

  async acknowledgeExpense(scope: Scope, receipt: ExpenseCommit): Promise<void> {
    await this.use('readwrite', async tx => {
      const state = await this.requireExpenseScope(tx, scope);
      const queued = state.pending.find(item => item.id === receipt.operation.id);
      if (!queued || !sameExpenseOperation(queued, receipt.operation)) throw new Error('Expense receipt did not match.');
      if (BigInt(receipt.sequence) <= BigInt(state.cursor)) {
        state.pending = state.pending.filter(item => item.id !== queued.id);
        await this.projectExpenses(tx, state);
      } else if (!state.acknowledged.includes(queued.id)) {
        state.acknowledged.push(queued.id);
        await result(tx.settings.put(state, 'expense-sync'));
      }
    });
  }

  async replayExpenses(scope: Scope, cursor: string, entries: ExpenseCommit[]): Promise<void> {
    await this.use('readwrite', async tx => {
      const state = await this.requireExpenseScope(tx, scope);
      if (cursor !== state.cursor) throw new Error('Expense checkpoint changed.');
      if (!entries.length) return;
      for (const commit of entries) {
        if (commit.sequence !== nextSequence(state.cursor)) throw new Error('Expense feed has a gap.');
        const queued = state.pending.find(item => item.id === commit.operation.id);
        if (queued && !sameExpenseOperation(queued, commit.operation)) throw new Error('Expense operation identity changed.');
        state.heads = applyExpenseOperation(state.heads, commit.operation);
        if (state.acknowledged.includes(commit.operation.id)) {
          state.pending = state.pending.filter(item => item.id !== commit.operation.id);
          state.acknowledged = state.acknowledged.filter(id => id !== commit.operation.id);
        }
        state.cursor = commit.sequence;
      }
      await this.projectExpenses(tx, state);
    });
  }

  async resolveExpense(scope: Scope, selected: string, reviewed: string[]): Promise<void> {
    await this.use('readwrite', async tx => {
      const state = await this.requireExpenseScope(tx, scope);
      const versions = competingExpenseHeads(state).find(heads => heads.some(item => item.id === selected));
      if (!versions || versions.length !== reviewed.length || !versions.every(item => reviewed.includes(item.id))) {
        throw new Error('Expense versions changed. Review again.');
      }
      const chosen = recordOf(versions.find(item => item.id === selected)!);
      const revision = versions.reduce((max, item) => BigInt(item.record.metadata.revision) > BigInt(max)
        ? item.record.metadata.revision : max, '0');
      const updatedAt = versions.reduce((max, item) => instantKey(item.record.metadata.updatedAt) > instantKey(max)
        ? item.record.metadata.updatedAt : max, chosen.value.metadata.updatedAt);
      const metadata = revise({ ...chosen.value.metadata, revision, updatedAt });
      const resolved: ExpenseRecord = chosen.kind === 'expense'
        ? { kind: 'expense', value: { ...chosen.value, metadata } }
        : { kind: 'label', value: { ...chosen.value, metadata } };
      state.pending.push(newExpenseOperation(resolved, 'RESOLVE', reviewed));
      await this.projectExpenses(tx, state);
    });
  }

  async rejectExpenseResolution(scope: Scope, id: string): Promise<void> {
    await this.use('readwrite', async tx => {
      const state = await this.requireExpenseScope(tx, scope);
      const rejected = state.pending.find(item => item.id === id);
      if (!rejected || !['RESTORE', 'RESOLVE'].includes(rejected.mutation) || state.pending.some(item => item.parents.includes(id))) {
        throw new Error('Could not reopen the expense review.');
      }
      state.pending = state.pending.filter(item => item.id !== id);
      state.acknowledged = state.acknowledged.filter(item => item !== id);
      await this.projectExpenses(tx, state);
    });
  }

  private async requireExpenseScope(tx: Transaction, scope: Scope): Promise<ExpenseSyncState> {
    const state = await result(tx.settings.get('expense-sync')) as ExpenseSyncState | undefined;
    if (!state || !sameScope(state.scope, scope)) throw new Error('Review your expense connection. Local changes were kept.');
    return state;
  }

  private async queueExpense(tx: Transaction, record: ExpenseRecord, mutation: ExpenseMutation) {
    const state = await result(tx.settings.get('expense-sync')) as ExpenseSyncState | undefined;
    if (!state) return;
    const versions = effectiveExpenseHeads(state).find(heads => heads[0]?.record.metadata.id === record.value.metadata.id) ?? [];
    if (competingExpenseHeads(state).some(heads => heads[0]?.record.metadata.id === record.value.metadata.id) ||
        versions.some(head => state.pending.some(item => item.id === head.id && ['RESOLVE', 'RESTORE'].includes(item.mutation)))) {
      throw new Error('Review competing expense versions before editing.');
    }
    state.pending.push(newExpenseOperation(record, mutation, versions.map(head => head.id)));
    await result(tx.settings.put(state, 'expense-sync'));
  }

  private async projectExpenses(tx: Transaction, state: ExpenseSyncState) {
    const records = effectiveExpenseHeads(state).map(versions => recordOf([...versions].sort((a, b) =>
      Number(deleted(b)) - Number(deleted(a)) || b.id.localeCompare(a.id))[0]!));
    const labels = new Map(records.flatMap(record => record.kind === 'label' ? [[record.value.metadata.id, record.value.kind] as const] : []));
    for (const record of records) {
      if (record.kind === 'expense' && (labels.get(record.value.categoryId) !== 'CATEGORY' || labels.get(record.value.paymentMethodId) !== 'PAYMENT')) {
        throw new Error('An expense refers to an unavailable label.');
      }
      await result(tx.expenses.put(record));
    }
    await result(tx.settings.put(state, 'expense-sync'));
  }

  async saveExpense(record: ExpenseRecord, expected: string | null): Promise<void> {
    await this.use('readwrite', async tx => {
      const records = await result(tx.expenses.getAll()) as ExpenseRecord[];
      const { metadata } = record.value;
      const prior = records.find(item => item.value.metadata.id === metadata.id);
      if ((prior?.value.metadata.revision ?? null) !== expected || (prior && prior.kind !== record.kind) ||
          metadata.revision !== (expected === null ? '1' : nextSequence(expected)) ||
          (prior && (prior.value.metadata.createdAt !== metadata.createdAt ||
            instantKey(prior.value.metadata.updatedAt) > instantKey(metadata.updatedAt)))) {
        throw new Error('This record changed. Reopen it before saving.');
      }
      checkExpenseRecord(record);
      const labels = records.flatMap(item => item.kind === 'label' ? [item.value] : []);
      if (record.kind === 'label') {
        const { kind, name, archived } = record.value;
        if (prior?.kind === 'label' && prior.value.kind !== kind) throw new Error('A label cannot change type.');
        if (!archived && labels.some(item => item.metadata.id !== metadata.id && item.kind === kind &&
            expenseNameKey(item.name) === expenseNameKey(name))) throw new Error('This name already exists. Check archived items.');
      } else {
        const previous = prior?.kind === 'expense' ? prior.value : undefined;
        if (record.value.deleted || previous?.deleted) throw new Error('Restore this expense before editing it.');
        const category = labels.find(item => item.metadata.id === record.value.categoryId && item.kind === 'CATEGORY');
        const payment = labels.find(item => item.metadata.id === record.value.paymentMethodId && item.kind === 'PAYMENT');
        if (!category || !payment || (category.archived && previous?.categoryId !== category.metadata.id) ||
            (payment.archived && previous?.paymentMethodId !== payment.metadata.id)) {
          throw new Error('Choose an available category and payment method.');
        }
      }
      await this.queueExpense(tx, record, 'PUT');
      await result(tx.expenses.put(record));
    });
  }

  async setExpenseDeleted(id: string, deleted: boolean, expected: string): Promise<void> {
    await this.use('readwrite', async tx => {
      const prior = await result(tx.expenses.get(id)) as ExpenseRecord | undefined;
      if (prior?.kind !== 'expense') throw new Error('Expense is unavailable.');
      if (prior.value.deleted === deleted) return;
      if (prior.value.metadata.revision !== expected) throw new Error('This expense changed. Reopen it before continuing.');
      const record = { kind: 'expense', value: {
        ...prior.value, metadata: revise(prior.value.metadata), deleted,
      } } satisfies ExpenseRecord;
      await this.queueExpense(tx, record, deleted ? 'DELETE' : 'RESTORE');
      await result(tx.expenses.put(record));
    });
  }

  async workoutRecords(): Promise<WorkoutRecord[]> {
    return this.use('readonly', async tx => await result(tx.workouts.getAll()) as WorkoutRecord[]);
  }

  async saveWorkout(record: WorkoutRecord, expected: string | null): Promise<void> {
    return this.use('readwrite', async tx => {
      const records = await result(tx.workouts.getAll()) as WorkoutRecord[];
      const id = record.value.metadata.id;
      const prior = records.find(item => item.value.metadata.id === id);
      if ((prior?.value.metadata.revision ?? null) !== expected || (prior && prior.kind !== record.kind)) {
        throw new Error('This record changed. Reopen it before saving.');
      }
      if ((prior?.kind === 'session' && prior.value.deleted) || (record.kind === 'session' && record.value.deleted)) {
        throw new Error('Restore this workout before editing it.');
      }
      const metadata = record.value.metadata;
      if (metadata.revision !== (expected === null ? '1' : nextSequence(expected)) ||
          (prior && (prior.value.metadata.createdAt !== metadata.createdAt ||
            instantKey(prior.value.metadata.updatedAt) > instantKey(metadata.updatedAt)))) {
        throw new Error('Invalid workout revision.');
      }
      checkWorkoutRecords([...records.filter(item => item.value.metadata.id !== id), record]);
      await result(tx.workouts.put(record));
    });
  }

  async workoutDraft(): Promise<{ session: Session; revision: string | null } | undefined> {
    return this.use('readonly', async tx => await result(tx.settings.get('workout-draft')));
  }

  async saveWorkoutDraft(draft: { session: Session; revision: string | null } | null): Promise<void> {
    await this.use('readwrite', async tx => {
      if (draft) await result(tx.settings.put(draft, 'workout-draft'));
      else await result(tx.settings.delete('workout-draft'));
    });
  }

  async setWorkoutDeleted(id: string, deleted: boolean): Promise<void> {
    await this.use('readwrite', async tx => {
      const record = await result(tx.workouts.get(id)) as WorkoutRecord | undefined;
      if (record?.kind !== 'session') throw new Error('Workout no longer exists.');
      if (!!record.value.deleted === deleted) return;
      await result(tx.workouts.put({ kind: 'session', value: {
        ...record.value, deleted, metadata: revise(record.value.metadata),
      } }));
    });
  }

  private async readLocalView(tx: Transaction): Promise<LocalView> {
    const state = await tx.state();
    const rows = await result(tx.tasks.getAll()) as Projection[];
    const tasks = await Promise.all(rows.map(async row => {
      const heads = await Promise.all(row.heads.map(head => tx.operation(head)));
      return {
        record: row.record,
        head: row.heads.length === 1 ? row.heads[0]! : null,
        conflicts: heads.length > 1 ? heads.map(head => head!.operation) : [],
        resolving: heads.length === 1 && !!heads[0]?.queue && heads[0].operation.parents.length > 1,
      };
    }));
    return { tasks, checkpoint: state.checkpoint, lastSync: state.lastSync, pending: await result(tx.operations.index('queue').count()) };
  }

  async view(): Promise<LocalView> {
    return this.use('readonly', tx => this.readLocalView(tx));
  }

  async exportSnapshot() {
    return this.use('readonly', async tx => ({
      view: await this.readLocalView(tx),
      workouts: await result(tx.workouts.getAll()) as WorkoutRecord[],
      expenses: await result(tx.expenses.getAll()) as ExpenseRecord[],
      expensePending: ((await result(tx.settings.get('expense-sync'))) as ExpenseSyncState | undefined)?.pending.length ?? 0,
    }));
  }

  async bind(scope: Scope): Promise<void> {
    await this.use('readwrite', async tx => {
      const state = await tx.state();
      if (state.checkpoint && !sameScope(state.checkpoint.scope, scope)) {
        throw new Error('This browser is bound to a different dataset or recovery epoch. Local changes were kept.');
      }
      if (!state.checkpoint) await tx.save({ ...state, checkpoint: { scope, sequence: '0' } });
    });
  }

  async add(name: string, deadline: string): Promise<void> {
    const id = crypto.randomUUID();
    const time = now();
    await this.use('readwrite', tx => this.append(tx, {
      metadata: { id, createdAt: time, updatedAt: time, schemaVersion: 1, revision: '1' },
      title: title(name), deadline: calendarDate(deadline), completedAt: null, deleted: false,
    }, []));
  }

  async edit(id: string, changes: { title?: string; deadline?: string; completed?: boolean; delete?: boolean }, expectedHead?: string | null): Promise<void> {
    await this.use('readwrite', async tx => {
      const row = await tx.projection(id);
      if (!row || row.record.deleted) throw new Error('This task has been deleted.');
      if (expectedHead !== undefined && (row.heads.length !== 1 || row.heads[0] !== expectedHead)) {
        throw new Error('This task changed. Review its current version before saving.');
      }
      const head = row.heads.length === 1 ? await tx.operation(row.heads[0]!) : undefined;
      if (!head || (head.queue && head.operation.parents.length > 1)) {
        throw new Error('Review or synchronize the task conflict before editing.');
      }
      const record = row.record;
      const updatedAt = latest([record.metadata.updatedAt, now()]);
      await this.append(tx, { ...record,
        metadata: { ...record.metadata, updatedAt, revision: nextSequence(record.metadata.revision) },
        title: changes.title === undefined ? record.title : title(changes.title),
        deadline: changes.deadline === undefined ? record.deadline : calendarDate(changes.deadline),
        completedAt: changes.completed === undefined ? record.completedAt :
          changes.completed ? record.completedAt ?? updatedAt : null,
        deleted: changes.delete ?? false,
      }, row.heads);
    });
  }

  async resolve(id: string, selected: string, expected: string[]): Promise<void> {
    await this.use('readwrite', async tx => {
      const row = await tx.projection(id);
      if (!row || row.heads.length < 2 || row.heads.length !== expected.length ||
          !row.heads.every(head => expected.includes(head)) || !row.heads.includes(selected)) {
        throw new Error('The conflict changed. Review its current versions.');
      }
      const chosen = (await tx.operation(selected))!.operation.record;
      if (row.record.deleted && !chosen.deleted) throw new Error('A deleted task cannot be revived.');
      const heads = await Promise.all(row.heads.map(head => tx.operation(head)));
      const revision = heads.reduce((max, head) => {
        const value = BigInt(head!.operation.record.metadata.revision);
        return value > max ? value : max;
      }, 0n);
      await this.append(tx, { ...chosen, metadata: { ...chosen.metadata,
        revision: nextSequence(revision.toString()),
        updatedAt: latest([...heads.map(head => head!.operation.record.metadata.updatedAt), now()]),
      } }, row.heads);
    });
  }

  private async append(tx: Transaction, record: Todo, parents: string[]) {
    const state = await tx.state();
    const sequence = nextSequence(state.sequence);
    await tx.accept(decodeOperation({ format: 'LifeAppOperation', protocolVersion: 1, payloadVersion: 1,
      operationId: crypto.randomUUID(), deviceId: state.deviceId, sequence, parents,
      recordType: 'todo', recordId: record.metadata.id, type: record.deleted ? 'delete' : 'put', record }), true);
    await tx.save({ ...state, sequence });
  }

  async pending(scope: Scope): Promise<Operation[]> {
    return this.use('readonly', async tx => {
      requireScope(await tx.state(), scope);
      const rows = await result(tx.operations.index('queue').getAll(undefined, 100)) as StoredOperation[];
      return rows.map(row => row.operation);
    });
  }

  async applyPage(from: Checkpoint, page: Page): Promise<Checkpoint> {
    const next = advance(from, page);
    await this.use('readwrite', async tx => {
      const state = await tx.state();
      requireScope(state, from.scope);
      if (state.checkpoint!.sequence !== from.sequence) throw new Error('Another tab advanced the cursor. Sync again.');
      for (const entry of page.entries) await tx.accept(entry.operation, false);
      await tx.save({ ...state, checkpoint: next });
    });
    return next;
  }

  async acknowledge(scope: Scope, sent: Operation, receipt: Receipt): Promise<void> {
    if (!sameScope(receipt.scope, scope) || !sameOperation(receipt.commit.operation, sent)) {
      throw new Error('The receipt did not match the queued operation.');
    }
    await this.use('readwrite', async tx => {
      requireScope(await tx.state(), scope);
      const local = await tx.operation(sent.operationId);
      if (!local || local.rejected || !sameOperation(local.operation, sent)) throw new Error('Queued operation changed.');
      if (local.queue) await result(tx.operations.put({ operation: local.operation }));
    });
  }

  async markSync(scope: Scope): Promise<void> {
    await this.use('readwrite', async tx => {
      const state = await tx.state();
      requireScope(state, scope);
      await tx.save({ ...state, lastSync: now() });
    });
  }

  async retainRejectedResolution(scope: Scope, operation: Operation): Promise<void> {
    await this.use('readwrite', async tx => {
      requireScope(await tx.state(), scope);
      const saved = await tx.operation(operation.operationId);
      if (!saved?.queue || operation.parents.length < 2 || !sameOperation(saved.operation, operation)) return;
      const history = await result(tx.operations.index('record').getAll(operation.recordId)) as StoredOperation[];
      if (history.some(row => !row.rejected && row.operation.parents.includes(operation.operationId))) {
        throw new Error('A pending edit depends on the rejected resolution.');
      }
      await result(tx.operations.put({ operation, rejected: true } satisfies StoredOperation));
      const remaining = history.filter(row => !row.rejected && row.operation.operationId !== operation.operationId);
      const parents = new Set(remaining.flatMap(row => row.operation.parents));
      await tx.project(operation.recordId, remaining.map(row => row.operation.operationId).filter(id => !parents.has(id)));
    });
  }
}

function requireScope(state: Settings, scope: Scope) {
  if (!state.checkpoint || !sameScope(state.checkpoint.scope, scope)) throw new Error('The dataset or recovery epoch changed.');
}

function latest(times: string[]) {
  return times.reduce((last, value) => instantKey(value) > instantKey(last) ? value : last);
}
