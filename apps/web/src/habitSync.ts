import { ApiError, editablePreview, editableVersions, saveHabitCheck } from './api.ts';
import type { BrowserSession, HabitCheck } from './api.ts';
import { object, uuid, sameScope, scopeOf, sequence } from './protocol.ts';
import type { LifeStore } from './store.ts';

export interface HabitMonth {
  title: string;
  habits: { id: string | null; name: string; archived: boolean; days: number[] }[];
}

export interface HabitVersionPage {
  rows: { id: string; label: string; current: boolean }[];
  next: string | null;
}

export interface HabitEdit {
  base: string;
  check: HabitCheck;
  blocked?: boolean;
}

export interface HabitSyncState {
  pages: Record<string, HabitVersionPage>;
  months: Record<string, HabitMonth>;
  edits: HabitEdit[];
  receipts: Record<string, { versionId: string; conflicted: boolean }>;
}

export function habitMonth(value: unknown): HabitMonth {
  const preview = object(value, ['title', 'pages', 'habits']);
  if (typeof preview.title !== 'string' || !/^\d{4}-(0[1-9]|1[0-2])$/.test(preview.title) ||
      !Array.isArray(preview.pages) || preview.pages.length !== 0 || !Array.isArray(preview.habits)) {
    throw new Error('Invalid habit month.');
  }
  const end = new Date(preview.title + '-01T00:00:00Z');
  end.setUTCMonth(end.getUTCMonth() + 1, 0);
  return {
    title: preview.title,
    habits: preview.habits.map((item: unknown) => {
      const fields = ['name', 'archived', 'completedDays'];
      const includesId = typeof item === 'object' && item !== null && 'id' in item;
      const habit = object(item, includesId ? [...fields, 'id'] : fields);
      const days = habit.completedDays;
      if (typeof habit.name !== 'string' || typeof habit.archived !== 'boolean' || !Array.isArray(days) ||
          !days.every(day => typeof day === 'number' && Number.isInteger(day) && day >= 1 && day <= end.getUTCDate())) {
        throw new Error('Invalid habit definition.');
      }
      return { id: includesId ? uuid(habit.id) : null, name: habit.name, archived: habit.archived, days: days as number[] };
    }),
  };
}

async function downloadHabitMonths(store: LifeStore, session: BrowserSession) {
  const rows: HabitVersionPage['rows'] = [];
  let cursor = '0';
  for (let page = 0; page < 2000; page++) {
    const response = object(await editableVersions(session.scope, cursor, AbortSignal.timeout(15_000)), ['scope', 'items', 'hasMore']);
    if (!sameScope(session.scope, scopeOf(response.scope)) || !Array.isArray(response.items) || typeof response.hasMore !== 'boolean') {
      throw new Error('Invalid habit inventory.');
    }
    for (const item of response.items) {
      const row = object(item, ['id', 'sequence', 'kind', 'key', 'rootId', 'sha256', 'parents', 'current']);
      const next = sequence(row.sequence);
      if (row.kind !== 'habits' || typeof row.key !== 'string' || typeof row.current !== 'boolean' ||
          BigInt(next) <= BigInt(cursor)) throw new Error('Invalid habit record.');
      cursor = next;
      rows.push({ id: uuid(row.id), label: 'Habits · ' + row.key, current: row.current });
    }
    if (!response.hasMore) {
      const previous = await store.habitSyncState(session.scope);
      const cachedMonths = new Set(Object.values(previous.months).map(month => month.title));
      const now = new Date();
      cachedMonths.add(now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0'));
      for (const row of rows.filter(row => row.current && cachedMonths.has(row.label.slice(9)) && !previous.months[row.id])) {
        await store.saveHabitMonth(session.scope, row.id,
          habitMonth(await editablePreview(session.scope, row.id, AbortSignal.timeout(15_000))));
      }
      await store.saveHabitPage(session.scope, '0', { rows, next: null });
      return;
    }
    if (!response.items.length) break;
  }
  throw new Error('Habit history exceeds the refresh limit.');
}

const running = new WeakMap<LifeStore, Promise<void>>();

export function syncHabits(store: LifeStore, session: BrowserSession): Promise<void> {
  const active = running.get(store);
  if (active) return active;
  const work = (async () => {
    const snapshot = await store.habitSyncState(session.scope);
    for (const edit of snapshot.edits.filter(item => !item.blocked).slice(0, 32)) {
      try {
        const receipt = await saveHabitCheck(session, edit.base, edit.check);
        const month = habitMonth(await editablePreview(session.scope, receipt.versionId, AbortSignal.timeout(15_000)));
        await store.confirmHabitEdit(session.scope, edit, receipt, month);
      } catch (failure) {
        if (!(failure instanceof ApiError && failure.status === 409 && failure.code !== 'SCOPE_MISMATCH')) throw failure;
        await store.blockHabitEdit(session.scope, edit);
      }
    }
    await downloadHabitMonths(store, session);
  })().finally(() => running.delete(store));
  running.set(store, work);
  return work;
}
