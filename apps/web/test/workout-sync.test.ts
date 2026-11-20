import 'fake-indexeddb/auto';
import assert from 'node:assert/strict';
import test from 'node:test';
import { openStore } from '../src/store.ts';
import { revise } from '../src/workout.ts';
import type { Session, WorkoutRecord } from '../src/workout.ts';
import { syncWorkouts } from '../src/workoutSync.ts';

const scope = { dataset: crypto.randomUUID(), epoch: crypto.randomUUID() };
const session = { scope, csrfToken: 'disposable', expiresAt: '9999-12-31T23:59:59Z' };

test('a protected draft defers its incoming workout and a later pass downloads it', async context => {
  const store = await openStore(crypto.randomUUID());
  const workout: Session = {
    metadata: revise(), date: '2026-11-01', startedAt: null, endedAt: null,
    durationSeconds: null, caloriesKcal: null, notes: null, completed: false, entries: [], logged: true,
  };
  const version = { version: crypto.randomUUID(), record: { kind: 'session', value: workout } };
  context.mock.method(globalThis, 'fetch', async () => Response.json({ items: [version], next: null }));
  try {
    await store.bindStructured(scope, [], []);
    await store.saveWorkoutDraft({ session: workout, revision: null });
    const deferred = await syncWorkouts(store, session);
    assert.equal(deferred.deferred, 1);
    assert.equal((await store.workoutRecords()).length, 0);
    assert.deepEqual((await store.workoutDraft())?.session, workout);
    await store.saveWorkoutDraft(null);
    const complete = await syncWorkouts(store, session);
    assert.equal(complete.deferred, 0);
    assert.equal((await store.workoutRecords()).length, 1);
  } finally { store.close(); }
});

test('an oversized workout stays local while another record is acknowledged', async context => {
  const store = await openStore(crypto.randomUUID());
  const records: WorkoutRecord[] = ['00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000002'].map(id => ({
    kind: 'exercise', value: { metadata: { ...revise(), id }, name: id, category: null, equipment: null, archived: false },
  }));
  context.mock.method(globalThis, 'fetch', async (_url: unknown, options: RequestInit) => {
    if (options?.method !== 'POST') return Response.json({ items: [], next: null });
    const body = JSON.parse(String(options.body));
    if (body.entry.record.value.metadata.id === records[0]!.value.metadata.id) {
      return Response.json({ code: 'LIMIT_EXCEEDED' }, { status: 413 });
    }
    return Response.json(body.entry);
  });
  try {
    for (const record of records) await store.saveWorkout(record, null);
    await store.bindStructured(scope, [], records);
    const result = await syncWorkouts(store, session);
    assert.equal(result.oversized, 1);
    assert.equal((await store.workoutRecords()).length, 2);
    const binding = await store.workoutBinding();
    assert.equal(binding?.versions[records[0]!.value.metadata.id], undefined);
    assert.ok(binding?.versions[records[1]!.value.metadata.id]);
  } finally { store.close(); }
});
