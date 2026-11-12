import { useEffect, useRef, useState } from 'react';
import { ApiError, editablePreview, editableReview, editableVersions, resolveEditable } from './api.ts';
import type { BrowserSession, HabitCheck } from './api.ts';
import { object, sameScope, scopeOf, sequence, uuid } from './protocol.ts';
import type { Scope } from './protocol.ts';
import { habitMonth, syncHabits } from './habitSync.ts';
import type { HabitMonth, HabitSyncState, HabitVersionPage } from './habitSync.ts';
import type { LifeStore } from './store.ts';

function array(value: unknown): unknown[] {
  if (!Array.isArray(value)) throw new Error('Invalid record list.');
  return value as unknown[];
}

function text(value: unknown): string {
  if (typeof value !== 'string') throw new Error('Invalid record text.');
  return value;
}

function flag(value: unknown): boolean {
  if (typeof value !== 'boolean') throw new Error('Invalid record state.');
  return value;
}

export function Habits({ scope: { dataset, epoch }, session, store, online, generation }: {
  scope: Scope; session: BrowserSession | null; store: LifeStore; online: boolean; generation: number;
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
    if (online && session) {
      try { await syncHabits(store, session); }
      catch { setSyncError('Habit changes are saved here and waiting to sync.'); }
      await refresh();
    }
  }
  const [open, setOpen] = useState(false);
  const [after, setAfter] = useState('0');
  const [retry, setRetry] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const [failure, setFailure] = useState(false);
  const [resolved, setResolved] = useState(false);
  const [listing, setListing] = useState<HabitVersionPage | null>(null);

  useEffect(() => {
    if (!open || !online || !session) return;
    const cancel = new AbortController();
    const requested = { dataset, epoch };
    void editableVersions(requested, after, cancel.signal).then(async value => {
      const response = object(value, ['scope', 'items', 'hasMore']);
      if (!sameScope(requested, scopeOf(response.scope))) throw new Error('The record scope changed.');
      let cursor = after;
      const rows = array(response.items).map(item => {
        const row = object(item, ['id', 'sequence', 'kind', 'key', 'rootId', 'sha256', 'parents', 'current']);
        const kind = text(row.kind);
        if (kind !== 'habits') throw new Error('Invalid habit record.');
        const next = sequence(row.sequence);
        if (BigInt(next) <= BigInt(cursor)) throw new Error('Invalid record order.');
        cursor = next;
        return { id: uuid(row.id), label: 'Habits · ' + text(row.key), current: flag(row.current) };
      });
      const page = { rows, next: flag(response.hasMore) && rows.length ? cursor : null };
      if (cancel.signal.aborted) return;
      await store.saveHabitPage(requested, after, page);
      if (!cancel.signal.aborted) { setListing(page); setFailure(false); }
    }).catch(() => { if (!cancel.signal.aborted) setFailure(true); });
    return () => cancel.abort();
  }, [after, dataset, epoch, generation, online, open, retry, session, store]);

  function load(cursor: string) {
    setListing(null);
    setFailure(false);
    setSelected(null);
    if (after === cursor) setRetry(value => value + 1);
    else setAfter(cursor);
  }

  const shown = listing ?? cache?.pages[after] ?? null;
  const next = shown?.next;
  return <details onToggle={event => {
    setOpen(event.currentTarget.open);
    if (!event.currentTarget.open) { setResolved(false); load('0'); }
  }}>
    <summary>Habit months and versions</summary>
    {open && <>
      <p>Review saved habit months and competing versions.</p>
      {resolved && <p role="status">The selected version is current. Earlier versions are available in history.</p>}
      {syncError && <p role="status">{syncError}</p>}
      {!!cache?.edits.length && <p role="status">{cache.edits.length} habit changes waiting to sync.</p>}
      {failure && <p role="alert">Could not refresh habit months.</p>}
      {!shown ? <p role="status">{online && session ? 'Loading record versions…' : 'Open a habit month while online to make it available here.'}</p> :
        shown.rows.length === 0 ? <p>No committed record versions.</p> :
        <ul>{shown.rows.map(row => <li key={row.id}>
          <button aria-pressed={selected === row.id} onClick={() => { setResolved(false); setSelected(row.id); }}>
            {row.label}{row.current ? '' : ' (history)'}
          </button>
        </li>)}</ul>}
      {after !== '0' && <button onClick={() => load('0')}>First versions</button>}
      {next && <button onClick={() => load(next)}>Next versions</button>}
      {(failure || listing) && <button onClick={() => load(after)}>{failure ? 'Retry' : 'Refresh versions'}</button>}
      {selected && <SelectedRecord key={selected} scope={{ dataset, epoch }} session={online ? session : null} id={selected}
        store={store} cache={cache} save={save} refresh={refresh}
        currentVersions={new Set(shown?.rows.filter(row => row.current).map(row => row.id))} onResolved={() => {
        load(after);
        setResolved(true);
      }} />}
    </>}
  </details>;
}

function SelectedRecord({ scope, session, id, currentVersions, store, cache, save, refresh, onResolved }: {
  scope: Scope; session: BrowserSession | null; id: string; currentVersions: Set<string>; store: LifeStore; cache: HabitSyncState | null;
  save: (version: string, check: HabitCheck) => Promise<void>; refresh: () => Promise<void>; onResolved: () => void;
}) {
  const [reviewing, setReviewing] = useState(false);
  const [day, setDay] = useState(1);
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
  const editor = (currentVersions.has(id) || currentVersions.has(version)) && !conflicted ? { day, setDay, save, refresh, cache } : undefined;
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
  day: number;
  setDay: (day: number) => void;
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
  const date = preview.title + '-' + String(editor.day).padStart(2, '0');

  async function submit(habitId: string, checked: boolean) {
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
  const editable = preview.habits.filter(habit => !habit.archived).flatMap(habit => habit.id === null ? [] : [{ ...habit, id: habit.id }]);
  if (!Number.isInteger(days) || editable.length === 0) return null;
  return <fieldset>
    <legend>Daily habit completions</legend>
    <label>Day <select value={editor.day} onChange={event => editor.setDay(Number(event.target.value))}>
      {Array.from({ length: days }, (_, index) => index + 1).map(day => <option key={day} value={day}>{day}</option>)}
    </select></label>
    {editable.map(habit => {
      const pending = editor.cache?.edits.find(item => item.check.habitId === habit.id && item.check.date === date);
      return <div key={habit.id}>
        <label><input type="checkbox" checked={pending?.check.checked ?? habit.days.includes(editor.day)}
          disabled={saving || pending !== undefined} onChange={event => void submit(habit.id, event.target.checked)} /> {habit.name}</label>
        {pending?.blocked && <p>The server needs a fresh review. <button onClick={() => void discard(pending.check.id)}>Discard this queued change</button></p>}
      </div>;
    })}
    {error && <p role="alert">{error}</p>}
  </fieldset>;
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
    {preview.habits.length > 0 && <ul>{preview.habits.map((habit, index) => <li key={habit.id ?? index}>
      {habit.name}{habit.archived ? ' (archived)' : ''} · {habit.days.length ? 'Completed days ' + habit.days.join(', ') : 'No completed days'}
    </li>)}</ul>}
    {preview.habits.length === 0 && <p>This month has no habit definitions.</p>}
  </section>;
}
