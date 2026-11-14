import { api, ApiError } from './api.ts';
import type { BrowserSession } from './api.ts';
import { instantKey, object, sameScope, uuid } from './protocol.ts';
import type { Scope } from './protocol.ts';
import type { LifeStore } from './store.ts';
import { revise } from './workout.ts';
import type { WorkoutRecord } from './workout.ts';

export interface WorkoutVersion { version: string; record: WorkoutRecord }
export interface WorkoutBinding { scope: Scope; versions: Record<string, WorkoutVersion> }
export interface WorkoutConflict { local: WorkoutRecord; remote: WorkoutVersion }

function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical);
  if (!value || typeof value !== 'object') return value;
  return Object.fromEntries(Object.entries(value).filter(([, item]) => item !== undefined)
    .sort(([a], [b]) => a.localeCompare(b)).map(([key, item]) => [key, canonical(item)]));
}

export function workoutMatches(a?: WorkoutRecord, b?: WorkoutRecord): boolean {
  function stable(record?: WorkoutRecord) {
    if (!record) return undefined;
    const value: Record<string, unknown> = { ...record.value };
    if (record.kind === 'session') {
      for (const key of ['deleted', 'logged', 'durationManuallyEntered']) if (value[key] === false) delete value[key];
    }
    if (record.kind === 'routine' && value.archived === false) delete value.archived;
    if (record.kind === 'routine' && Array.isArray(value.plannedSetCounts) && !value.plannedSetCounts.length) {
      delete value.plannedSetCounts;
    }
    return JSON.stringify(canonical({ kind: record.kind, value }));
  }
  return stable(a) === stable(b);
}

export function workoutVersion(value: unknown): WorkoutVersion {
  const entry = object(value, ['version', 'record']);
  const record = object(entry.record, ['kind', 'value']);
  if (record.kind !== 'exercise' && record.kind !== 'routine' && record.kind !== 'session') {
    throw new Error('Invalid workout record kind.');
  }
  const version = uuid(entry.version);
  if (typeof record.value !== 'object' || record.value === null || !('metadata' in record.value)) {
    throw new Error('Invalid workout record.');
  }
  const meta = object(record.value.metadata, ['id', 'createdAt', 'updatedAt', 'schemaVersion', 'revision']);
  uuid(meta.id);
  if (meta.schemaVersion !== 1) throw new Error('Unsupported workout schema.');
  return { version, record: record as unknown as WorkoutRecord };
}

export async function syncWorkouts(store: LifeStore, session: BrowserSession): Promise<WorkoutConflict[]> {
  const binding = await store.workoutBinding();
  if (!binding) return [];
  if (!sameScope(binding.scope, session.scope)) throw new Error('Workouts are connected to another account or recovery epoch.');
  const remote = new Map<string, WorkoutVersion>();
  let cursor: string | null = null;
  for (let page = 0; page < 1_668; page++) {
    const response = await api.workoutVersions(session, cursor);
    for (const entry of response.items) {
      const id = entry.record.value.metadata.id;
      if (remote.has(id)) throw new Error('Duplicate workout version.');
      remote.set(id, entry);
    }
    cursor = response.next;
    if (!cursor) break;
    if (page === 1_667) throw new Error('Workout inventory exceeds the synchronization limit.');
  }
  const local = new Map((await store.workoutRecords()).map(record => [record.value.metadata.id, record]));
  const order = ['exercise', 'routine', 'session'];
  const kind = (id: string) => local.get(id)?.kind ?? remote.get(id)!.record.kind;
  const ids = [...new Set([...remote.keys(), ...local.keys()])].sort((a, b) => order.indexOf(kind(a)) - order.indexOf(kind(b)));
  const conflicts: WorkoutConflict[] = [];
  for (const id of ids) {
    const saved = local.get(id);
    const server = remote.get(id);
    const prior = binding.versions[id];
    if (server && workoutMatches(saved, server.record)) {
      if (prior?.version !== server.version) await store.applyWorkoutVersion(session.scope, prior, server, saved, false);
    } else if (server && (!saved || workoutMatches(saved, prior?.record))) {
      const applied = await store.applyWorkoutVersion(session.scope, prior, server, saved, true);
      if (!applied && saved) conflicts.push({ local: saved, remote: server });
    } else if (saved && server?.version === prior?.version) {
      try {
        const receipt = await api.pushWorkout(session, { version: crypto.randomUUID(), record: saved }, prior?.version ?? null);
        if (!workoutMatches(saved, receipt.record)) throw new Error('Workout receipt does not match the local record.');
        await store.applyWorkoutVersion(session.scope, prior, receipt, saved, false);
      } catch (failure) {
        if (!(failure instanceof ApiError && failure.code === 'WORKOUT_CHANGED')) throw failure;
        throw new Error('A workout changed while syncing. Local records were kept. Sync again.');
      }
    } else if (saved && server) {
      conflicts.push({ local: saved, remote: server });
    } else {
      throw new Error('The server workout inventory changed. Local records were kept.');
    }
  }
  return conflicts;
}

export async function resolveWorkoutConflict(store: LifeStore, session: BrowserSession, conflict: WorkoutConflict, keepLocal: boolean) {
  const binding = await store.workoutBinding();
  if (!binding || !sameScope(binding.scope, session.scope)) throw new Error('Workout connection changed.');
  const id = conflict.local.value.metadata.id;
  const local = (await store.workoutRecords()).find(record => record.value.metadata.id === id);
  if (!local || !workoutMatches(local, conflict.local)) throw new Error('The local copy changed. Sync and review again.');
  const latest = await api.workoutHead(session, id);
  if (latest.version !== conflict.remote.version) throw new Error('The server copy changed. Sync and review again.');
  if ((await store.workoutDraft())?.session.metadata.id === id) throw new Error('Finish the workout draft before resolving this copy.');
  let chosen = latest;
  if (keepLocal) {
    const remote = latest.record.value.metadata;
    const metadata = local.value.metadata;
    if (remote.createdAt !== metadata.createdAt || latest.record.kind !== local.kind) {
      throw new Error('These copies have different origins. Export your local copy before choosing the server copy.');
    }
    const revisions = BigInt(metadata.revision) > BigInt(remote.revision) ? metadata.revision : remote.revision;
    const updatedAt = instantKey(metadata.updatedAt) > instantKey(remote.updatedAt) ? metadata.updatedAt : remote.updatedAt;
    const revised = { ...local, value: { ...local.value, metadata: revise({ ...metadata, revision: revisions, updatedAt }) } } as WorkoutRecord;
    chosen = await api.pushWorkout(session, { version: crypto.randomUUID(), record: revised }, latest.version);
    if (!workoutMatches(revised, chosen.record)) throw new Error('Workout receipt did not match.');
  }
  if (!await store.applyWorkoutVersion(session.scope, binding.versions[id], chosen, local, true)) {
    throw new Error('The local workout changed during review. Its changes were kept.');
  }
}
