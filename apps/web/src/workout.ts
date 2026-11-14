import { calendarDate, instantKey, nextSequence, now } from './protocol.ts';
import type { Metadata } from './protocol.ts';

export interface Exercise {
  metadata: Metadata; name: string; category: string | null; equipment: string | null; archived: boolean; description?: string;
}
export interface Routine { metadata: Metadata; name: string; exerciseOrder: string[]; plannedSetCounts?: (number | null)[]; archived?: boolean }
export interface RecordedSet {
  metadata: Metadata; entryId: string; number: number; weight: { value: number; unit: 'KG' | 'LB' } | null;
  repetitions: number | null; durationSeconds: number | null; restSeconds: number | null; completed: boolean;
}
export interface Entry { metadata: Metadata; sessionId: string; exerciseId: string; order: number; round: number | null; sets: RecordedSet[]; remarks?: string }
export interface Session {
  metadata: Metadata; date: string; startedAt: string | null; endedAt: string | null;
  logged?: boolean; deleted?: boolean; routineId?: string; routineName?: string;
  averageHeartRateBpm?: number; durationManuallyEntered?: boolean;
  durationSeconds: number | null; caloriesKcal: number | null; notes: string | null; completed: boolean; entries: Entry[];
}
export type WorkoutRecord =
  | { kind: 'exercise'; value: Exercise }
  | { kind: 'routine'; value: Routine }
  | { kind: 'session'; value: Session };

export function revise(previous?: Metadata): Metadata {
  const time = now();
  if (!previous) return { id: crypto.randomUUID(), createdAt: time, updatedAt: time, schemaVersion: 1, revision: '1' };
  return { ...previous, updatedAt: instantKey(previous.updatedAt) > instantKey(time) ? previous.updatedAt : time,
    revision: nextSequence(previous.revision) };
}

export function entryFor(sessionId: string, exerciseId: string, order: number): Entry {
  return { metadata: revise(), sessionId, exerciseId, order, round: null, sets: [] };
}

export function checkWorkoutRecords(records: WorkoutRecord[]) {
  const exerciseIds = new Set(records.filter(record => record.kind === 'exercise').map(record => record.value.metadata.id));
  const ids = new Set<string>();
  function identify(metadata: Metadata) {
    if (ids.has(metadata.id)) throw new Error('Duplicate workout record.');
    ids.add(metadata.id);
  }
  for (const record of records) {
    identify(record.value.metadata);
    if (record.kind === 'exercise' || record.kind === 'routine') {
      const { name } = record.value;
      if (!name.trim() || name.length > 120 || name !== name.trim()) throw new Error('Enter a name up to 120 characters.');
    }
    if (record.kind === 'exercise') {
      if ((record.value.description?.length ?? 0) > 4000) throw new Error('Description is too long.');
      for (const field of [record.value.category, record.value.equipment]) {
        if (field !== null && (!field.trim() || field.length > 120)) throw new Error('Exercise fields must be up to 120 characters.');
      }
    } else if (record.kind === 'routine') {
      if (!record.value.exerciseOrder.length || record.value.exerciseOrder.some(id => !exerciseIds.has(id))) {
        throw new Error('Choose exercises for this routine.');
      }
      const plan = record.value.plannedSetCounts;
      if (plan?.length && (plan.length !== record.value.exerciseOrder.length ||
          plan.some(count => count !== null && (!Number.isInteger(count) || count < 1 || count > 100)))) {
        throw new Error('Enter between 1 and 100 planned sets per exercise.');
      }
    } else {
      const session = record.value;
      calendarDate(session.date);
      if (!!session.routineId !== !!session.routineName || (session.routineName?.length ?? 0) > 120) {
        throw new Error('Invalid routine reference.');
      }
      if ((session.notes?.length ?? 0) > 4000) throw new Error('Notes may contain up to 4,000 characters.');
      if (session.completed && !session.logged && session.endedAt === null) throw new Error('Missing workout end time.');
      integer(session.caloriesKcal, 0);
      integer(session.durationSeconds, 0);
      if (session.averageHeartRateBpm !== undefined) {
        integer(session.averageHeartRateBpm, 20);
        if (session.averageHeartRateBpm > 250) throw new Error('Average heart rate must be 20–250 BPM.');
      }
      if (session.durationManuallyEntered && session.durationSeconds === null) throw new Error('Enter the workout duration.');
      if (session.endedAt !== null && (session.startedAt === null || session.durationSeconds === null ||
          instantKey(session.endedAt) < instantKey(session.startedAt) ||
          (!session.durationManuallyEntered && session.durationSeconds > Math.floor((Date.parse(session.endedAt) - Date.parse(session.startedAt)) / 1000)))) {
        throw new Error('Invalid workout times.');
      }
      session.entries.forEach((entry, index) => {
        identify(entry.metadata);
        if ((entry.remarks?.length ?? 0) > 4000) throw new Error('Exercise remarks are too long.');
        if (entry.sessionId !== session.metadata.id || entry.order !== index || !exerciseIds.has(entry.exerciseId)) {
          throw new Error('Invalid workout exercise.');
        }
        integer(entry.round, 1);
        entry.sets.forEach((set, index) => {
          identify(set.metadata);
          if (set.entryId !== entry.metadata.id || set.number !== index + 1) throw new Error('Invalid set order.');
          integer(set.repetitions, 0);
          integer(set.durationSeconds, 1);
          integer(set.restSeconds, 0);
          if (set.weight && (!Number.isFinite(set.weight.value) || set.weight.value < 0 ||
              (set.weight.unit !== 'KG' && set.weight.unit !== 'LB'))) throw new Error('Enter a valid weight and unit.');
          if (set.completed && !set.repetitions && !set.durationSeconds) throw new Error('A completed set needs repetitions or duration.');
        });
      });
    }
  }
}

function integer(value: number | null, minimum: number) {
  if (value !== null && (!Number.isInteger(value) || value < minimum || value > 2_147_483_647)) {
    throw new Error('Enter a whole number in the allowed range.');
  }
}

export function blankSet(entryId: string, number: number): RecordedSet {
  return { metadata: revise(), entryId, number, weight: null, repetitions: null,
    durationSeconds: null, restSeconds: null, completed: false };
}

export function routineEntries(sessionId: string, routine?: Routine): Entry[] {
  return routine?.exerciseOrder.map((id, order) => {
    const entry = entryFor(sessionId, id, order);
    entry.sets = Array.from({ length: routine.plannedSetCounts?.[order] ?? 0 },
      (_, index) => blankSet(entry.metadata.id, index + 1));
    return entry;
  }) ?? [];
}

export function setSummary(set: RecordedSet): string {
  if (set.repetitions !== null) return `${set.weight ? `${set.weight.value} ${set.weight.unit} × ` : ''}${set.repetitions} reps`;
  return set.weight ? `${set.weight.value} ${set.weight.unit}` : set.durationSeconds !== null ? `${set.durationSeconds}s` : '—';
}
