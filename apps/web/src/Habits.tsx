import { useEffect, useRef, useState } from 'react';
import { ApiError, editablePreview, editableReview, resolveEditable } from './api.ts';
import type { BrowserSession, HabitCheck } from './api.ts';
import { object, sameScope, scopeOf, sequence, uuid } from './protocol.ts';
import type { Scope } from './protocol.ts';
import { habitMonth } from './habitSync.ts';
import type { HabitMonth, HabitSyncState } from './habitSync.ts';
import type { LifeStore } from './store.ts';

function array(value: unknown): unknown[] {
  if (!Array.isArray(value)) throw new Error('Invalid record list.');
  return value as unknown[];
}

export function Habits({ scope: { dataset, epoch }, session, store, online, generation, onSaved }: {
  scope: Scope; session: BrowserSession | null; store: LifeStore; online: boolean; generation: number; onSaved: () => void;
}) {
  const [cache, setCache] = useState<HabitSyncState | null>(null);
  const [syncError, setSyncError] = useState('');
  async function refresh() {
    setCache(await store.habitSyncState({ dataset, epoch }));
  }
  useEffect(() => {
    let active = true;
    void store.habitSyncState({ dataset, epoch }).then(value => { if (active) setCache(value); })
      .catch(() => { if (active) setSyncError('Could not open saved Habits.'); });
    return () => { active = false; };
  }, [dataset, epoch, generation, store]);
  async function save(base: string, check: HabitCheck) {
    setSyncError('');
    await store.queueHabitEdit({ dataset, epoch }, { base, check });
    await refresh();
    onSaved();
  }
  const [month, setMonth] = useState(() => {
    const now = new Date();
    return now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0');
  });
  const [selected, setSelected] = useState<string | null>(null);
  const [resolved, setResolved] = useState(false);

  function moveMonth(offset: number) {
    const date = new Date(month + '-01T00:00:00Z');
    date.setUTCMonth(date.getUTCMonth() + offset);
    const next = date.toISOString().slice(0, 7);
    if (/^\d{4}-/.test(next) && !next.startsWith('0000')) { setMonth(next); setSelected(null); setResolved(false); }
  }

  const shown = cache?.pages['0'];
  const monthRows = shown?.rows.filter(row => row.label === 'Habits · ' + month) ?? [];
  const current = monthRows.filter(row => row.current);
  const selectedRow = monthRows.find(row => row.id === selected);
  const version = selectedRow ?? current.at(-1);
  return <section aria-label="Monthly habit tracker">
    <div className="habit-month-controls">
      <button aria-label="Previous month" onClick={() => moveMonth(-1)}>‹</button>
      <label>Month <input type="month" value={month} onChange={event => {
        if (/^\d{4}-(0[1-9]|1[0-2])$/.test(event.target.value) && !event.target.value.startsWith('0000')) {
          setMonth(event.target.value); setSelected(null); setResolved(false);
        }
      }} /></label>
      <button aria-label="Next month" onClick={() => moveMonth(1)}>›</button>
    </div>
    {resolved && <p role="status">The selected version is current. Earlier versions are available in history.</p>}
    {syncError && <p role="status">{syncError}</p>}
    {!!cache?.edits.length && <p role="status">{cache.edits.length} habit changes waiting to sync.</p>}
    {!shown && <p role="status">{online ? 'Loading habit months…' : 'Open a habit month while online to make it available here.'}</p>}
    {shown && !version && <p>No synced habits for this month. Create or copy a ledger in LifeApp on Android.</p>}
    {version && <SelectedRecord key={version.id} scope={{ dataset, epoch }} session={online ? session : null}
      id={version.id} store={store} cache={cache} save={save} refresh={refresh}
      currentVersions={new Set(current.length === 1 ? current.map(row => row.id) : [])} onResolved={() => {
        setSelected(null); onSaved(); setResolved(true);
      }} />}
    {monthRows.length > 0 && <details>
      <summary>Habit months and versions</summary>
      <ul>{[...monthRows].reverse().map(row => <li key={row.id}>
        <button aria-pressed={version?.id === row.id} onClick={() => { setResolved(false); setSelected(row.current ? null : row.id); }}>
          {row.label}{row.current ? '' : ' (history)'}
        </button>
      </li>)}</ul>
    </details>}
  </section>;
}

function SelectedRecord({ scope, session, id, currentVersions, store, cache, save, refresh, onResolved }: {
  scope: Scope; session: BrowserSession | null; id: string; currentVersions: Set<string>; store: LifeStore; cache: HabitSyncState | null;
  save: (version: string, check: HabitCheck) => Promise<void>; refresh: () => Promise<void>; onResolved: () => void;
}) {
  const [reviewing, setReviewing] = useState(false);
  let version = id;
  let conflicted = false;
  const seen = new Set<string>();
  while (!seen.has(version) && cache?.receipts[version]) {
    seen.add(version);
    const receipt = cache.receipts[version]!;
    version = receipt.versionId;
    conflicted = receipt.conflicted;
    if (conflicted) break;
  }
  const editor = (currentVersions.has(id) || currentVersions.has(version)) && !conflicted ? { save, refresh, cache } : undefined;
  if (reviewing && session) {
    return <ConflictReview session={session} id={version} onClose={() => setReviewing(false)} onResolved={onResolved} />;
  }
  return <>
    {version !== id && <p role="status">{conflicted ? 'This date has competing versions. Review them before editing again.' : 'Habit completion saved.'}</p>}
    {session && <button onClick={() => setReviewing(true)}>Review current versions</button>}
    <RecordPreview key={version} scope={scope} id={version} store={store} cache={cache} online={session !== null} editor={editor} />
  </>;
}

function ConflictReview({ session, id, onClose, onResolved }: {
  session: BrowserSession; id: string; onClose: () => void; onResolved: () => void;
}) {
  const { dataset, epoch } = session.scope;
  const [variants, setVariants] = useState<{ id: string; sequence: string }[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [viewed, setViewed] = useState<string | null>(null);
  const [attempt, setAttempt] = useState<{ selected: string; id: string } | null>(null);
  const [retry, setRetry] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const sending = useRef(false);

  useEffect(() => {
    const cancel = new AbortController();
    const scope = { dataset, epoch };
    void editableReview(scope, id, cancel.signal).then(value => {
      const review = object(value, ['scope', 'kind', 'key', 'variants']);
      if (!sameScope(scope, scopeOf(review.scope))) throw new Error('The record scope changed.');
      const rows = array(review.variants).map(item => {
        const row = object(item, ['id', 'sequence']);
        return { id: uuid(row.id), sequence: sequence(row.sequence) };
      });
      if (rows.length < 1 || rows.length > 32 || new Set(rows.map(row => row.id)).size !== rows.length) {
        throw new Error('Invalid record variants.');
      }
      if (!cancel.signal.aborted) setVariants(rows);
    }).catch(() => { if (!cancel.signal.aborted) setError('Could not read the current versions.'); });
    return () => cancel.abort();
  }, [dataset, epoch, id, retry]);

  async function keepVersion() {
    if (sending.current || !selected || viewed !== selected || !variants || variants.length < 2) return;
    if (!window.confirm('Make this version current? All earlier versions will remain in history.')) return;
    const operation = attempt?.selected === selected ? attempt : { selected, id: crypto.randomUUID() };
    sending.current = true;
    setAttempt(operation);
    setBusy(true);
    setError('');
    try {
      await resolveEditable(session, selected, operation.id, variants.map(row => row.id));
      onResolved();
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 409) {
        setVariants(null);
        setSelected(null);
        setViewed(null);
        setAttempt(null);
        setError('The current versions changed. Refresh and review them again.');
      } else {
        setError('Could not confirm the resolution. Retry to check the same request.');
      }
    } finally {
      sending.current = false;
      setBusy(false);
    }
  }

  return <section aria-label="Record conflict review">
    <h2>Current versions</h2>
    {error && <p role="alert">{error}</p>}
    {!variants && !error && <p role="status">Loading current versions…</p>}
    {variants?.length === 1 && <p>This record has one current version.</p>}
    {variants && variants.length > 1 && <>
      <p>Preview each version to decide which one should be current.</p>
      <ul>{variants.map(row => <li key={row.id}>
        <button disabled={busy || (attempt !== null && selected !== row.id)} aria-pressed={selected === row.id}
          onClick={() => {
            if (selected !== row.id) { setViewed(null); setSelected(row.id); }
          }}>Preview version {row.sequence}</button>
      </li>)}</ul>
      {selected && <RecordPreview key={selected} scope={session.scope} id={selected} online onReady={setViewed} />}
      <button disabled={busy || !selected || viewed !== selected} onClick={() => void keepVersion()}>
        {busy ? 'Saving resolution…' : attempt ? 'Retry resolution' : 'Keep selected version'}
      </button>
    </>}
    {!variants && error && <button onClick={() => { setError(''); setRetry(value => value + 1); }}>Refresh current versions</button>}
    <button disabled={busy} onClick={onClose}>Close version review</button>
  </section>;
}

interface HabitEditor {
  save: (base: string, check: HabitCheck) => Promise<void>;
  refresh: () => Promise<void>;
  cache: HabitSyncState | null;
}

function HabitCheckboxes({ preview, version, editor, scope, store }: {
  preview: HabitMonth; version: string; editor: HabitEditor; scope: Scope; store?: LifeStore;
}) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const monthEnd = new Date(preview.title + '-01T00:00:00Z');
  monthEnd.setUTCMonth(monthEnd.getUTCMonth() + 1, 0);
  const days = monthEnd.getUTCDate();

  async function submit(habitId: string, date: string, checked: boolean) {
    setSaving(true);
    setError('');
    try { await editor.save(version, { id: crypto.randomUUID(), habitId, date, checked }); }
    catch (failure) { setError(failure instanceof Error ? failure.message : 'Could not save the habit change.'); }
    finally { setSaving(false); }
  }
  async function discard(id: string) {
    try {
      await store?.discardBlockedHabitEdit(scope, id);
      await editor.refresh();
    } catch { setError('Could not update the saved change.'); }
  }
  const active = preview.habits.filter(habit => !habit.archived);
  if (!Number.isInteger(days) || !active.length) return <p>No active habits for this month.</p>;
  const dates = Array.from({ length: days }, (_, index) => preview.title + '-' + String(index + 1).padStart(2, '0'));
  const pending = new Map(editor.cache?.edits.map(item => [item.check.habitId + '/' + item.check.date, item]));
  return <>
    <div className="habit-grid" tabIndex={0} aria-label="Scrollable habit grid" role="region">
      <table aria-label={preview.title + ' habit completions'}>
        <thead><tr><th scope="col">Day</th>{active.map((habit, index) =>
          <th key={habit.id ?? index} scope="col">{habit.name}</th>)}</tr></thead>
        <tbody>{dates.map((date, index) => <tr key={date}><th scope="row">{index + 1}</th>
          {active.map((habit, column) => {
            const queued = pending.get(habit.id + '/' + date);
            return <td key={habit.id ?? column}><input type="checkbox" aria-label={habit.name + ', ' + date}
              checked={queued?.check.checked ?? habit.days.includes(index + 1)}
              disabled={saving || queued !== undefined || habit.id === null}
              onChange={event => { if (habit.id) void submit(habit.id, date, event.target.checked); }} /></td>;
          })}
        </tr>)}</tbody>
        <tfoot><tr><th scope="row">Totals</th>{active.map((habit, index) => {
          const total = dates.filter((date, day) =>
            pending.get(habit.id + '/' + date)?.check.checked ?? habit.days.includes(day + 1)).length;
          return <td key={habit.id ?? index}>{total} / {days} days<br />{Math.round(total / days * 1000) / 10}%</td>;
        })}</tr></tfoot>
      </table>
    </div>
    {editor.cache?.edits.filter(item => item.blocked && item.check.date.startsWith(preview.title + '-')).map(item =>
      <p key={item.check.id}>{item.check.date} needs review. <button onClick={() => void discard(item.check.id)}>Discard this queued change</button></p>)}
    {error && <p role="alert">{error}</p>}
  </>;
}

function RecordPreview({ scope: { dataset, epoch }, id, onReady, editor, store, cache, online }: {
  scope: Scope; id: string; onReady?: (id: string) => void; editor?: HabitEditor; store?: LifeStore;
  cache?: HabitSyncState | null; online: boolean;
}) {
  const [loaded, setLoaded] = useState<HabitMonth | null>(null);
  const [failed, setFailed] = useState(false);
  const preview = loaded ?? cache?.months[id];
  useEffect(() => {
    if (!online) return;
    const cancel = new AbortController();
    const scope = { dataset, epoch };
    void editablePreview(scope, id, cancel.signal).then(async value => {
      if (cancel.signal.aborted) return;
      const month = habitMonth(value);
      await store?.saveHabitMonth(scope, id, month);
      if (!cancel.signal.aborted) { setLoaded(month); onReady?.(id); }
    }).catch(() => { if (!cancel.signal.aborted) setFailed(true); });
    return () => cancel.abort();
  }, [dataset, epoch, id, online, onReady, store]);
  if (!preview && failed) return <p role="alert">Could not display this habit version.</p>;
  if (!preview) return <p role="status">{online ? 'Loading habit month…' : 'This habit month is unavailable offline.'}</p>;
  return <section>
    <h2>{preview.title}</h2>
    {editor && preview.habits.length > 0 && <HabitCheckboxes preview={preview} version={id} editor={editor} scope={{ dataset, epoch }} store={store} />}
    {!editor && preview.habits.length > 0 && <ul>{preview.habits.map((habit, index) => <li key={habit.id ?? index}>
      {habit.name}{habit.archived ? ' (archived)' : ''} · {habit.days.length ? 'Completed days ' + habit.days.join(', ') : 'No completed days'}
    </li>)}</ul>}
    {preview.habits.length === 0 && <p>This month has no habit definitions.</p>}
  </section>;
}
