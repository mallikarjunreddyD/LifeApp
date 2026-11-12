import { ApiError, editablePreview, saveHabitCheck } from './api.ts';
import type { BrowserSession, HabitCheck } from './api.ts';
import { object, uuid } from './protocol.ts';
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
  })().finally(() => running.delete(store));
  running.set(store, work);
  return work;
}
