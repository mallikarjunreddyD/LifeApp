import 'fake-indexeddb/auto';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { advance, calendarDate, decodeOperation, pageOf } from '../src/protocol.ts';
import type { Operation, Page } from '../src/protocol.ts';
import { openStore } from '../src/store.ts';

const fixture = (name: string): unknown =>
  JSON.parse(readFileSync(new URL('../../../contracts/fixtures/' + name + '.json', import.meta.url), 'utf8'));
const first = decodeOperation(fixture('todo-operation'));
const initial = pageOf(fixture('sync-page'));
const scope = initial.scope;
const cursor = { scope, sequence: '0' };

function changed(base: Operation, name: string, options: { deleted?: boolean; parents?: string[] } = {}): Operation {
  return {
    ...base, operationId: crypto.randomUUID(), deviceId: crypto.randomUUID(), parents: options.parents ?? [base.operationId],
    type: options.deleted ? 'delete' : 'put',
    record: { ...base.record, title: name, deleted: !!options.deleted },
  };
}

test('Phase 5 fixtures preserve counters above Number.MAX_SAFE_INTEGER', () => {
  assert.equal(first.sequence, '9007199254740993');
  assert.equal(first.record.metadata.revision, first.sequence);
  assert.equal(first.record.deadline, '2026-11-12');
  assert.equal(advance(cursor, initial).sequence, '1');
  assert.throws(() => decodeOperation({ ...first, sequence: Number(first.sequence) }));
  assert.throws(() => decodeOperation({ ...first, payloadVersion: 2 }));
  assert.throws(() => advance(cursor, { ...initial, entries: [{ ...initial.entries[0]!, sequence: '2' }] }));
  assert.throws(() => calendarDate('2026-02-29'));
  assert.equal(calendarDate('2028-02-29'), '2028-02-29');
});

test('offline task and outbox survive reopening and only a matching receipt clears the queue', async () => {
  const name = crypto.randomUUID();
  const before = await openStore(name);
  await before.add('  Offline task  ', '2026-11-12');
  assert.equal((await before.view()).pending, 1);
  await assert.rejects(before.pending(scope));
  before.close();
  const store = await openStore(name);
  try {
    const view = await store.view();
    assert.equal(view.tasks[0]!.record.title, 'Offline task');
    await store.bind(scope);
    const pending = (await store.pending(scope))[0]!;
    const echo = { scope, entries: [{ sequence: '1', operation: pending }], hasMore: false };
    await store.applyPage(cursor, echo);
    assert.equal((await store.view()).pending, 1);
    await assert.rejects(store.acknowledge(scope, pending, { scope, commit: initial.entries[0]! }));
    await store.acknowledge(scope, pending, { scope, commit: echo.entries[0]! });
    await store.acknowledge(scope, pending, { scope, commit: echo.entries[0]! });
    assert.equal((await store.view()).pending, 0);
    await assert.rejects(store.bind({ ...scope, epoch: crypto.randomUUID() }));
    await store.edit(pending.recordId, { completed: true }, pending.operationId);
    assert.ok((await store.view()).tasks[0]!.record.completedAt);
    await assert.rejects(store.edit(pending.recordId, { title: 'Stale edit' }, pending.operationId));
    assert.equal((await store.pending(scope))[0]!.sequence, '2');
  } finally { store.close(); }
});

test('a failed replay rolls back the entire page and checkpoint', async () => {
  const store = await openStore(crypto.randomUUID());
  try {
    await store.bind(scope);
    const missing = changed(first, 'Unknown parent', { parents: [crypto.randomUUID()] });
    const broken: Page = { scope, entries: [...initial.entries, { sequence: '2', operation: missing }], hasMore: false };
    await assert.rejects(store.applyPage(cursor, broken));
    assert.equal((await store.view()).tasks.length, 0);
    assert.equal((await store.view()).checkpoint?.sequence, '0');
    await store.applyPage(cursor, initial);
    await assert.rejects(store.applyPage(cursor, initial));
    assert.equal((await store.view()).tasks.length, 1);
  } finally { store.close(); }
});

test('delete and edit retain both versions, and a stale resolution restores them for review', async () => {
  const store = await openStore(crypto.randomUUID());
  try {
    await store.bind(scope);
    const checkpoint = await store.applyPage(cursor, initial);
    await store.edit(first.recordId, { title: 'Offline edit' });
    const local = (await store.pending(scope))[0]!;
    const deletion = changed(first, 'Server deletion', { deleted: true });
    await store.applyPage(checkpoint, { scope, entries: [{ sequence: '2', operation: deletion }], hasMore: false });
    let task = (await store.view()).tasks[0]!;
    assert.equal(task.record.deleted, true);
    assert.equal(task.conflicts.length, 2);
    const heads = task.conflicts.map(operation => operation.operationId);
    await assert.rejects(store.resolve(first.recordId, local.operationId, heads));
    await store.resolve(first.recordId, deletion.operationId, heads);
    const resolution = (await store.pending(scope))[1]!;
    await store.retainRejectedResolution(scope, resolution);
    task = (await store.view()).tasks[0]!;
    assert.equal(task.conflicts.length, 2);
    assert.equal(task.record.deleted, true);
    assert.deepEqual((await store.pending(scope)).map(operation => operation.operationId), [local.operationId]);
  } finally { store.close(); }
});

test('workout tombstones are idempotent and reject a stale edit after reopening', async () => {
  const name = crypto.randomUUID();
  const firstStore = await openStore(name);
  const { revise } = await import('../src/workout.ts');
  const value = { metadata: revise(), date: '2026-11-12', startedAt: null, endedAt: null,
    durationSeconds: 4320, durationManuallyEntered: true, averageHeartRateBpm: 136,
    caloriesKcal: null, notes: null, completed: false, logged: true, entries: [] };
  const other = { ...value, metadata: revise() };
  await firstStore.saveWorkout({ kind: 'session', value }, null);
  await firstStore.saveWorkout({ kind: 'session', value: other }, null);
  await firstStore.setWorkoutDeleted(value.metadata.id, true);
  await firstStore.setWorkoutDeleted(value.metadata.id, true);
  firstStore.close();

  const reopened = await openStore(name);
  try {
    const records = (await reopened.workoutRecords()).flatMap(record => record.kind === 'session' ? [record.value] : []);
    const deleted = records.find(record => record.metadata.id === value.metadata.id)!;
    assert.equal(deleted.deleted, true);
    assert.equal(deleted.metadata.revision, '2');
    assert.deepEqual(records.find(record => record.metadata.id === other.metadata.id), other);
    await assert.rejects(reopened.saveWorkout({ kind: 'session', value: { ...value, metadata: revise(value.metadata) } }, '1'));
    await reopened.setWorkoutDeleted(value.metadata.id, false);
    const restored = (await reopened.workoutRecords()).find(record => record.value.metadata.id === value.metadata.id);
    assert.equal(restored?.kind === 'session' && restored.value.deleted, false);
    assert.equal(restored?.value.metadata.revision, '3');
  } finally { reopened.close(); }
});

test('exact INR and offline expense CRUD survive reopening', async () => {
  const { parseExpensePaise, formatExpensePaise } = await import('../src/expense.ts');
  const { revise } = await import('../src/workout.ts');
  assert.equal(parseExpensePaise('1,250.75'), '125075');
  assert.equal(formatExpensePaise(12500000n), '₹1,25,000.00');
  for (const amount of ['0', '-1', '0.001', '10,00', '1e5', '1000000000']) assert.throws(() => parseExpensePaise(amount));
  const database = crypto.randomUUID();
  const before = await openStore(database);
  await before.seedExpenses();
  const records = await before.expenseRecords();
  const category = { metadata: revise(), kind: 'CATEGORY' as const, name: 'École', icon: 'OTHER' as const, color: 0x527062, archived: false };
  await before.saveExpense({ kind: 'label', value: category }, null);
  await assert.rejects(before.saveExpense({ kind: 'label', value: { ...category, metadata: revise(), name: 'école' } }, null));
  const payment = records.find(record => record.kind === 'label' && record.value.kind === 'PAYMENT')!;
  const value = { metadata: revise(), date: '2024-02-29', name: 'Train', amountMinor: '125075', currency: 'INR' as const,
    categoryId: category.metadata.id, paymentMethodId: payment.value.metadata.id, remarks: 'Window seat', deleted: false };
  await before.saveExpense({ kind: 'expense', value }, null);
  const other = { ...value, metadata: revise() };
  await before.saveExpense({ kind: 'expense', value: other }, null);
  await before.setExpenseDeleted(value.metadata.id, true, '1');
  await before.setExpenseDeleted(value.metadata.id, true, '1');
  before.close();

  const reopened = await openStore(database);
  try {
    const saved = (await reopened.expenseRecords()).flatMap(record => record.kind === 'expense' ? [record.value] : []);
    assert.equal(saved.length, 2);
    const deleted = saved.find(item => item.deleted)!;
    assert.equal(deleted.metadata.revision, '2');
    assert.equal(deleted.amountMinor, '125075');
    assert.deepEqual(saved.find(item => !item.deleted), other);
    const { expenseSpending } = await import('../src/expenseAnalytics.ts');
    const archived = { ...category, name: 'Renamed', archived: true };
    const spending = [...saved, { ...other, date: '2024-01-01', amountMinor: '1' }, { ...other, date: '2025-01-01' }];
    assert.equal(expenseSpending(spending, [archived], '2024-02').total, 125075n);
    assert.deepEqual(expenseSpending(spending, [archived], '2024'), {
      total: 125076n, categories: [{ category: archived, amount: 125076n }],
    });
    assert.equal(expenseSpending(spending, [archived], '2024-12').total, 0n);
    await assert.rejects(reopened.saveExpense({ kind: 'expense', value: { ...value, metadata: revise(value.metadata) } }, '1'));
    await reopened.setExpenseDeleted(value.metadata.id, false, '2');
    assert.ok((await reopened.expenseRecords()).every(record => record.kind !== 'expense' || !record.value.deleted));
  } finally { reopened.close(); }
});

test('IndexedDB version two upgrades without dropping workouts or queued tasks', async () => {
  const { revise } = await import('../src/workout.ts');
  const name = crypto.randomUUID();
  const exercise = { metadata: revise(), name: 'Existing workout exercise', category: null, equipment: null, archived: false };
  await new Promise<void>((resolve, reject) => {
    const request = indexedDB.open(name, 2);
    request.onupgradeneeded = () => {
      const db = request.result;
      db.createObjectStore('settings');
      const operations = db.createObjectStore('operations', { keyPath: 'operation.operationId' });
      operations.createIndex('queue', 'queue');
      operations.createIndex('record', 'operation.recordId');
      operations.put({ operation: first, queue: '0000000000000000001' });
      db.createObjectStore('tasks', { keyPath: 'id' }).put({ id: first.recordId, heads: [first.operationId], record: first.record });
      db.createObjectStore('workouts', { keyPath: 'value.metadata.id' }).put({ kind: 'exercise', value: exercise });
    };
    request.onerror = () => reject(request.error);
    request.onsuccess = () => { request.result.close(); resolve(); };
  });
  const store = await openStore(name);
  try {
    await store.seedExpenses();
    assert.equal((await store.expenseRecords()).length, 17);
    assert.deepEqual(await store.workoutRecords(), [{ kind: 'exercise', value: exercise }]);
    const view = await store.view();
    assert.equal(view.pending, 1);
    assert.deepEqual(view.tasks[0]?.record, first.record);
  } finally { store.close(); }
});
