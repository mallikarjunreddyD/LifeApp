import { useEffect, useRef, useState } from 'react';
import { ApiError, editablePreview, editableReview, editableVersions, resolveEditable, saveHabitCheck } from './api.ts';
import type { BrowserSession, HabitCheck } from './api.ts';
import { object, sameScope, scopeOf, sequence, uuid } from './protocol.ts';
import type { Scope } from './protocol.ts';

function array(value: unknown): unknown[] {
  if (!Array.isArray(value)) throw new Error('Invalid record list.');
  return value as unknown[];
}

function text(value: unknown): string {
  if (typeof value !== 'string') throw new Error('Invalid record text.');
  return value;
}

function number(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error('Invalid record geometry.');
  return value;
}

function flag(value: unknown): boolean {
  if (typeof value !== 'boolean') throw new Error('Invalid record state.');
  return value;
}

function previewOf(value: unknown) {
  const preview = object(value, ['title', 'pages', 'habits']);
  return {
    title: text(preview.title),
    habits: array(preview.habits).map(item => {
      const fields = ['name', 'archived', 'completedDays'];
      const includesId = typeof item === 'object' && item !== null && 'id' in item;
      const habit = object(item, includesId ? [...fields, 'id'] : fields);
      return { id: includesId ? uuid(habit.id) : null, name: text(habit.name), archived: flag(habit.archived), days: array(habit.completedDays).map(number) };
    }),
  };
}

type Preview = ReturnType<typeof previewOf>;

export function Habits({ session, generation }: { session: BrowserSession; generation: number }) {
  const { dataset, epoch } = session.scope;
  const [open, setOpen] = useState(false);
  const [after, setAfter] = useState('0');
  const [retry, setRetry] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const [failure, setFailure] = useState(false);
  const [resolved, setResolved] = useState(false);
  const [listing, setListing] = useState<{
    rows: { id: string; label: string; current: boolean }[]; next: string | null;
  } | null>(null);

  useEffect(() => {
    if (!open) return;
    const cancel = new AbortController();
    const requested = { dataset, epoch };
    void editableVersions(requested, after, cancel.signal).then(value => {
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
      if (!cancel.signal.aborted) setListing({ rows, next: flag(response.hasMore) && rows.length ? cursor : null });
    }).catch(() => { if (!cancel.signal.aborted) setFailure(true); });
    return () => cancel.abort();
  }, [after, dataset, epoch, generation, open, retry]);

  function load(cursor: string) {
    setListing(null);
    setFailure(false);
    setSelected(null);
    if (after === cursor) setRetry(value => value + 1);
    else setAfter(cursor);
  }

  const next = listing?.next;
  return <details onToggle={event => {
    setOpen(event.currentTarget.open);
    if (!event.currentTarget.open) { setResolved(false); load('0'); }
  }}>
    <summary>Habit months and versions</summary>
    {open && <>
      <p>Review saved habit months and competing versions.</p>
      {resolved && <p role="status">The selected version is current. Earlier versions are available in history.</p>}
      {failure ? <p role="alert">Could not load server record versions.</p> :
        !listing ? <p role="status">Loading record versions…</p> :
        listing.rows.length === 0 ? <p>No committed record versions.</p> :
        <ul>{listing.rows.map(row => <li key={row.id}>
          <button aria-pressed={selected === row.id} onClick={() => { setResolved(false); setSelected(row.id); }}>
            {row.label}{row.current ? '' : ' (history)'}
          </button>
        </li>)}</ul>}
      {after !== '0' && <button onClick={() => load('0')}>First versions</button>}
      {next && <button onClick={() => load(next)}>Next versions</button>}
      {(failure || listing) && <button onClick={() => load(after)}>{failure ? 'Retry' : 'Refresh versions'}</button>}
      {selected && <SelectedRecord key={selected} session={session} id={selected}
        current={listing?.rows.some(row => row.id === selected && row.current) === true} onResolved={() => {
        load(after);
        setResolved(true);
      }} />}
    </>}
  </details>;
}

type HabitReceipt = Awaited<ReturnType<typeof saveHabitCheck>>;

function SelectedRecord({ session, id, current, onResolved }: {
  session: BrowserSession; id: string; current: boolean; onResolved: () => void;
}) {
  const [reviewing, setReviewing] = useState(false);
  const [receipt, setReceipt] = useState<HabitReceipt | null>(null);
  const [day, setDay] = useState(1);
  const version = receipt?.versionId ?? id;
  if (!reviewing) return <>
    {receipt && <p role="status">{receipt.conflicted ? 'This date has competing versions. Review them before editing again.' : 'Habit completion saved.'}</p>}
    <button onClick={() => setReviewing(true)}>Review current versions</button>
    <RecordPreview key={version} scope={session.scope} id={version}
      editor={current && !receipt?.conflicted ? { session, day, setDay, onSaved: value => {
        setReceipt(value);
        if (value.conflicted) setReviewing(true);
      } } : undefined} />
  </>;
  return <>
    {receipt?.conflicted && <p role="status">This date has competing versions. Review them before editing again.</p>}
    <ConflictReview session={session} id={version} onClose={() => setReviewing(false)} onResolved={onResolved} />
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
      {selected && <RecordPreview key={selected} scope={session.scope} id={selected} onReady={setViewed} />}
      <button disabled={busy || !selected || viewed !== selected} onClick={() => void keepVersion()}>
        {busy ? 'Saving resolution…' : attempt ? 'Retry resolution' : 'Keep selected version'}
      </button>
    </>}
    {!variants && error && <button onClick={() => { setError(''); setRetry(value => value + 1); }}>Refresh current versions</button>}
    <button disabled={busy} onClick={onClose}>Close version review</button>
  </section>;
}

interface HabitEditor {
  session: BrowserSession;
  day: number;
  setDay: (day: number) => void;
  onSaved: (receipt: HabitReceipt) => void;
}

function HabitCheckboxes({ preview, version, editor }: { preview: Preview; version: string; editor: HabitEditor }) {
  const [pending, setPending] = useState<HabitCheck | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const sending = useRef(false);
  const monthEnd = new Date(preview.title + '-01T00:00:00Z');
  monthEnd.setUTCMonth(monthEnd.getUTCMonth() + 1, 0);
  const days = monthEnd.getUTCDate();

  async function submit(check: HabitCheck) {
    if (sending.current) return;
    sending.current = true;
    setBusy(true);
    setPending(check);
    setError('');
    try {
      editor.onSaved(await saveHabitCheck(editor.session, version, check));
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 409) {
        setError('The server needs a fresh review before this edit can be saved. Open Review current versions.');
        setPending(null);
      } else setError('Could not confirm the checkbox edit. Retry to check the same request.');
    } finally {
      sending.current = false;
      setBusy(false);
    }
  }

  const editable = preview.habits.filter(habit => !habit.archived).flatMap(habit => habit.id === null ? [] : [{ ...habit, id: habit.id }]);
  if (!Number.isInteger(days) || editable.length === 0) return null;
  return <fieldset>
    <legend>Daily habit completions</legend>
    <label>Day <select value={editor.day} disabled={pending !== null}
      onChange={event => editor.setDay(Number(event.target.value))}>
      {Array.from({ length: days }, (_, index) => index + 1).map(day => <option key={day} value={day}>{day}</option>)}
    </select></label>
    {editable.map(habit => <div key={habit.id}>
      <label><input type="checkbox" checked={habit.days.includes(editor.day)} disabled={pending !== null}
        onChange={event => void submit({
          id: crypto.randomUUID(), habitId: habit.id, date: preview.title + '-' + String(editor.day).padStart(2, '0'),
          checked: event.target.checked,
        })} /> {habit.name}</label>
    </div>)}
    {busy && <p role="status">Saving completion…</p>}
    {error && <p role="alert">{error}</p>}
    {pending && !busy && <button onClick={() => void submit(pending)}>Retry completion</button>}
  </fieldset>;
}

function RecordPreview({ scope: { dataset, epoch }, id, onReady, editor }: {
  scope: Scope; id: string; onReady?: (id: string) => void; editor?: HabitEditor;
}) {
  const [preview, setPreview] = useState<Preview | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    const cancel = new AbortController();
    void editablePreview({ dataset, epoch }, id, cancel.signal).then(value => {
      if (!cancel.signal.aborted) {
        setPreview(previewOf(value));
        onReady?.(id);
      }
    }).catch(() => { if (!cancel.signal.aborted) setFailed(true); });
    return () => cancel.abort();
  }, [dataset, epoch, id, onReady]);
  if (failed) return <p role="alert">Could not display this version. It may exceed the preview limit.</p>;
  if (!preview) return <p role="status">Loading record preview…</p>;
  return <section>
    <h2>{preview.title}</h2>
    {editor && preview.habits.length > 0 && <HabitCheckboxes preview={preview} version={id} editor={editor} />}
    {preview.habits.length > 0 && <ul>{preview.habits.map((habit, index) => <li key={habit.id ?? index}>
      {habit.name}{habit.archived ? ' (archived)' : ''} · {habit.days.length ? 'Completed days ' + habit.days.join(', ') : 'No completed days'}
    </li>)}</ul>}
    {preview.habits.length === 0 && <p>This month has no habit definitions.</p>}
  </section>;
}

