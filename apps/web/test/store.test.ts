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
