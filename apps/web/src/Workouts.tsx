import type { WorkoutConflict } from './workoutSync.ts';
import { useEffect, useRef, useState } from 'react';
import { today } from './protocol.ts';
import type { LifeStore } from './store.ts';
import { WorkoutReport } from './WorkoutReport.tsx';
import { blankSet, entryFor, revise, routineEntries, setSummary } from './workout.ts';
import type { Exercise, Routine, Session, WorkoutRecord } from './workout.ts';

type Draft = { session: Session; revision: string | null };
type Editor = { kind: 'exercise'; value?: Exercise } | { kind: 'routine'; value?: Routine };
const text = (data: FormData, name: string) => String(data.get(name) ?? '').trim();
const optionalNumber = (raw: string) => raw === '' ? null : Number(raw);

function move<T>(items: T[], index: number, offset: number): T[] {
  const next = [...items];
  const destination = index + offset;
  if (destination >= 0 && destination < next.length) [next[index], next[destination]] = [next[destination]!, next[index]!];
  return next;
}

export function Workouts({ store, historyOnly = false, generation, syncStatus, conflicts, onSaved, onResolve }: {
  store: LifeStore; historyOnly?: boolean; generation: number; syncStatus: string;
  conflicts: WorkoutConflict[]; onSaved: () => void;
  onResolve: (conflict: WorkoutConflict, keepLocal: boolean) => Promise<void>;
}) {
  const [records, setRecords] = useState<WorkoutRecord[] | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [editor, setEditor] = useState<Editor | null>(null);
  const [reportOpen, setReportOpen] = useState(false);
  const [library, setLibrary] = useState(false);
  const [routinesOpen, setRoutinesOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [month, setMonth] = useState('');
  const [day, setDay] = useState('');
  const [routineFilter, setRoutineFilter] = useState('');
  const [view, setView] = useState(() => localStorage.getItem('lifeapp-workout-view') === 'table' ? 'table' : 'cards');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState<Session | null>(null);
  const historyScroll = useRef(0);
  const editing = draft !== null;

  useEffect(() => {
    window.scrollTo(0, editing ? 0 : historyScroll.current);
  }, [editing]);

  useEffect(() => {
    let current = true;
    void Promise.all([store.workoutRecords(), store.workoutDraft()]).then(([saved, pending]) => {
      if (!current) return;
      setRecords(saved);
      if (pending) {
        const prior = saved.find(item => item.kind === 'session' && item.value.metadata.id === pending.session.metadata.id);
        if ((prior?.value.metadata.revision ?? null) === pending.revision) setDraft(pending);
      }
    }).catch(() => { if (current) setError('Could not load saved workouts.'); });
    return () => { current = false; };
  }, [store]);

  useEffect(() => {
    let active = true;
    void store.workoutRecords().then(value => { if (active) setRecords(value); })
      .catch(() => { if (active) setError('Could not refresh saved workouts.'); });
    return () => { active = false; };
  }, [generation, store]);

  async function write(action: () => Promise<void>) {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      await action();
      setRecords(await store.workoutRecords());
      onSaved();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not save the workout.');
    } finally { setBusy(false); }
  }

  function keepDraft(next: Draft | null) {
    if (!draft && next) historyScroll.current = window.scrollY;
    setDraft(next);
    void store.saveWorkoutDraft(next).catch(() => setError('Could not save the draft on this device.'));
  }

  function change(update: (value: Session) => Session) {
    if (draft) keepDraft({ ...draft, session: update(draft.session) });
  }

  const exercises = (records ?? []).flatMap(item => item.kind === 'exercise' ? [item.value] : [])
    .sort((a, b) => a.name.localeCompare(b.name));
  const sessions = (records ?? []).flatMap(item => item.kind === 'session' ? [item.value] : [])
    .sort((a, b) => b.date.localeCompare(a.date) || b.metadata.createdAt.localeCompare(a.metadata.createdAt));
  const routines = (records ?? []).flatMap(item => item.kind === 'routine' ? [item.value] : []);
  const available = exercises.filter(exercise => !exercise.archived);
  const names = new Map(exercises.map(exercise => [exercise.metadata.id, exercise.name]));
  const byId = (id: string) => names.get(id) ?? 'Exercise';
  const visible = sessions.filter(value => !value.deleted && (!month || value.date.startsWith(month)) &&
    (!day || value.date === day) && (!routineFilter || value.routineName === routineFilter));
  const session = draft?.session;
  const selectedRoutine = routines.find(value => value.metadata.id === session?.routineId);
  const summary = (value: Session) => [
    `${value.entries.length} ${value.entries.length === 1 ? 'exercise' : 'exercises'}`,
    value.durationSeconds !== null && `${+(value.durationSeconds / 60).toFixed(1)} min`,
    value.averageHeartRateBpm !== undefined && `${value.averageHeartRateBpm} BPM`,
    value.caloriesKcal !== null && `${value.caloriesKcal} kcal`,
  ].filter(Boolean).join(' · ');
  const title = (value: Session) => `${value.routineName ?? 'Workout'} · ${value.date}`;
  const rows = (value: Session) => <table className="workout-history-table">
    <thead><tr><th>Exercise</th><th>Weight × reps</th><th>Remarks</th></tr></thead>
    <tbody>{value.entries.map(entry => <tr key={entry.metadata.id}>
      <th scope="row">{byId(entry.exerciseId)}</th><td>{entry.sets.map(setSummary).join(', ') || '—'}</td>
      <td>{entry.remarks ?? '—'}</td>
    </tr>)}</tbody>
  </table>;

  if (reportOpen) return <WorkoutReport sessions={sessions} exercises={exercises} close={() => setReportOpen(false)} />;

  return <section className="workout-logbook">
    <div className="page-heading section-heading">
      <h1>{historyOnly ? 'History' : 'Workouts'}</h1>
      {!historyOnly && !draft && <button className="primary workout-add" disabled={busy} onClick={() => keepDraft({ revision: null,
        session: { metadata: revise(), date: today(), startedAt: null, endedAt: null, durationSeconds: null,
          caloriesKcal: null, notes: null, completed: false, logged: true, entries: [] } })}>Add workout</button>}
    </div>
    {syncStatus && <p role="status">{syncStatus}</p>}
    {!!conflicts.length && <details><summary>Review workout copies</summary>
      {conflicts.map(conflict => {
        const { local, remote } = conflict;
        return <article className="card" key={local.value.metadata.id}>
        <h2>{local.kind === 'session' ? local.value.date : local.value.name}</h2>
        <details><summary>This device</summary><pre>{JSON.stringify(local.value, null, 2)}</pre></details>
        <details><summary>Server copy</summary><pre>{JSON.stringify(remote.record.value, null, 2)}</pre></details>
        <div className="actions">
          <button disabled={busy} onClick={() => {
            if (window.confirm('Keep this device’s workout copy?')) void write(() => onResolve(conflict, true));
          }}>Keep this device’s copy</button>
          <button disabled={busy} onClick={() => {
            if (window.confirm('Replace this device’s workout with the reviewed server copy?')) void write(() => onResolve(conflict, false));
          }}>Keep server copy</button>
        </div>
      </article>;
      })}
    </details>}
    {error && <p role="alert">{error}</p>}
    {!records && !error && <p role="status">Loading workouts…</p>}
    {records && (session && draft ? <form className="editor" onSubmit={event => {
      event.preventDefault();
      void write(async () => {
        const prior = sessions.find(value => value.metadata.id === session.metadata.id);
        const entries = session.entries.map(entry => {
          const original = prior?.entries.find(value => value.metadata.id === entry.metadata.id);
          return { ...entry, metadata: original ? revise(original.metadata) : entry.metadata,
            sets: entry.sets.map(set => {
              const saved = original?.sets.find(value => value.metadata.id === set.metadata.id);
              return { ...set, metadata: saved ? revise(saved.metadata) : set.metadata,
                completed: set.completed && (!!set.repetitions || set.durationSeconds !== null) };
            }) };
        });
        const value: Session = { ...session, entries, logged: true,
          metadata: prior ? revise(prior.metadata) : session.metadata };
        await store.saveWorkout({ kind: 'session', value }, draft.revision);
        keepDraft(null);
      });
    }}>
      <h2>{draft.revision ? 'Edit workout' : 'Add workout'}</h2>
      <label>Workout date<input type="date" required value={session.date} onChange={event => {
        const date = event.currentTarget.value; change(value => ({ ...value, date }));
      }} /></label>
      {!draft.revision && <label>Routine<select value={session.routineId ?? ''} onChange={event => {
        const routine = routines.find(value => value.metadata.id === event.currentTarget.value);
        if (session.entries.length && !window.confirm('Replace the exercises and sets in this draft?')) return;
        change(value => ({ ...value, routineId: routine?.metadata.id, routineName: routine?.name,
          entries: routineEntries(value.metadata.id, routine) }));
      }}><option value="">No routine</option>{routines.map(value =>
        <option key={value.metadata.id} value={value.metadata.id}>{value.name}</option>)}</select></label>}
      {draft.revision && session.routineName && <p>{session.routineName}</p>}
      <div className="actions">
        <button type="button" onClick={() => setEditor({ kind: 'routine', value: selectedRoutine })}>
          {selectedRoutine ? 'Edit routine' : 'New routine'}
        </button>
        {selectedRoutine && session.entries.length > 0 && <button type="button" disabled={busy}
          onClick={() => void write(async () => store.saveWorkout({ kind: 'routine', value: {
            ...selectedRoutine, metadata: revise(selectedRoutine.metadata),
            exerciseOrder: session.entries.map(entry => entry.exerciseId),
            plannedSetCounts: session.entries.map(entry => entry.sets.length || null),
          } }, selectedRoutine.metadata.revision))}>Save changes to routine</button>}
      </div>
      {session.entries.map((entry, position) => <details key={entry.metadata.id} open className="workout-exercise">
        <summary>{byId(entry.exerciseId)}</summary>
        <div className="actions">
          <button type="button" disabled={!position} onClick={() => change(value => ({ ...value,
            entries: move(value.entries, position, -1).map((item, order) => ({ ...item, order })) }))}>Move up</button>
          <button type="button" disabled={position === session.entries.length - 1} onClick={() => change(value => ({ ...value,
            entries: move(value.entries, position, 1).map((item, order) => ({ ...item, order })) }))}>Move down</button>
          <button type="button" onClick={() => change(value => ({ ...value, entries: value.entries
            .filter(item => item.metadata.id !== entry.metadata.id).map((item, order) => ({ ...item, order })) }))}>Remove exercise</button>
        </div>
        {entry.sets.map(set => {
          const update = (apply: (previous: typeof set) => typeof set) => change(value => ({ ...value,
            entries: value.entries.map(item => item.metadata.id !== entry.metadata.id ? item : { ...item,
              sets: item.sets.map(row => row.metadata.id === set.metadata.id ? apply(row) : row) }) }));
          return <div key={set.metadata.id} className="workout-set-row">
            <span>Set {set.number}</span>
            <label>Weight<input type="number" min="0" step="any" inputMode="decimal" value={set.weight?.value ?? ''}
              onChange={event => {
                const weight = optionalNumber(event.currentTarget.value);
                update(row => ({ ...row, weight: weight === null ? null : { value: weight, unit: row.weight?.unit ?? 'KG' } }));
              }} /></label>
            <label>Unit<select value={set.weight?.unit ?? 'KG'} disabled={!set.weight} onChange={event => {
              const unit = event.currentTarget.value === 'LB' ? 'LB' : 'KG';
              update(row => ({ ...row, weight: row.weight && { ...row.weight, unit } }));
            }}><option>KG</option><option>LB</option></select></label>
            <label>Repetitions<input type="number" min="0" max="2147483647" inputMode="numeric" value={set.repetitions ?? ''}
              onChange={event => {
                const repetitions = optionalNumber(event.currentTarget.value);
                update(row => ({ ...row, repetitions }));
              }} /></label>
            <button type="button" onClick={() => change(value => ({ ...value, entries: value.entries.map(item =>
              item.metadata.id !== entry.metadata.id ? item : { ...item, sets: item.sets.filter(row => row.metadata.id !== set.metadata.id)
                .map((row, index) => ({ ...row, number: index + 1 })) }) }))}>Remove set</button>
          </div>;
        })}
        <div className="actions">{[false, true].map(copy => <button key={String(copy)} type="button"
          disabled={copy && !entry.sets.length} onClick={() => change(value => ({ ...value, entries: value.entries.map(item => {
            if (item.metadata.id !== entry.metadata.id) return item;
            const last = copy ? item.sets.at(-1) : undefined;
            const set = blankSet(item.metadata.id, item.sets.length + 1);
            return { ...item, sets: [...item.sets, { ...set, weight: last?.weight ?? null, repetitions: last?.repetitions ?? null }] };
          }) }))}>{copy ? 'Copy previous set' : 'Add set'}</button>)}</div>
        <label>Exercise remarks<textarea maxLength={4000} value={entry.remarks ?? ''} onChange={event => {
          const remarks = event.currentTarget.value;
          change(value => ({ ...value, entries: value.entries.map(item => item.metadata.id === entry.metadata.id ? { ...item, remarks } : item) }));
        }} /></label>
      </details>)}
      <label>Add exercise<select value="" onChange={event => {
        const id = event.currentTarget.value;
        if (id) change(value => ({ ...value, entries: [...value.entries, entryFor(value.metadata.id, id, value.entries.length)] }));
      }}><option value="">Choose exercise</option>{available.map(value =>
        <option key={value.metadata.id} value={value.metadata.id}>{value.name}</option>)}</select></label>
      <button type="button" onClick={() => setEditor({ kind: 'exercise' })}>New exercise</button>
      <h3>Workout summary</h3>
      <label>Workout minutes<input type="number" min="0" max="35791394" inputMode="numeric"
        value={session.durationSeconds === null ? '' : Math.floor(session.durationSeconds / 60)} onChange={event => {
          const minutes = optionalNumber(event.currentTarget.value);
          change(value => ({ ...value, durationSeconds: minutes === null
            ? (value.startedAt && value.endedAt ? Math.floor((Date.parse(value.endedAt) - Date.parse(value.startedAt)) / 1000) : null)
            : minutes * 60, durationManuallyEntered: minutes !== null }));
        }} /></label>
      <label>Average heart rate (BPM)<input type="number" min="20" max="250" inputMode="numeric"
        value={session.averageHeartRateBpm ?? ''} onChange={event => {
          const averageHeartRateBpm = optionalNumber(event.currentTarget.value) ?? undefined;
          change(value => ({ ...value, averageHeartRateBpm }));
        }} /></label>
      <label>Active calories<input type="number" min="0" max="2147483647" inputMode="numeric"
        value={session.caloriesKcal ?? ''} onChange={event => {
          const caloriesKcal = optionalNumber(event.currentTarget.value); change(value => ({ ...value, caloriesKcal }));
        }} /></label>
      <label>Overall remarks<textarea maxLength={4000} value={session.notes ?? ''} onChange={event => {
        const notes = event.currentTarget.value; change(value => ({ ...value, notes }));
      }} /></label>
      <div className="actions"><button className="primary" disabled={busy}>Save workout</button>
        <button type="button" disabled={busy} onClick={() => {
          if (window.confirm('Discard this workout draft?')) keepDraft(null);
        }}>Discard draft</button></div>
    </form> : <>

      <h2>Workout history</h2>
      <div className="workout-filters">
        <label>Month<input type="month" value={month} onChange={event => setMonth(event.currentTarget.value)} /></label>
        <label>Workout date<input type="date" value={day} onChange={event => setDay(event.currentTarget.value)} /></label>
        <label>Routine<select value={routineFilter} onChange={event => setRoutineFilter(event.currentTarget.value)}>
          <option value="">All routines</option>{[...new Set(sessions.flatMap(value => value.routineName ? [value.routineName] : []))]
            .sort().map(name => <option key={name}>{name}</option>)}</select></label>
        <label>History view<select value={view} onChange={event => {
          const next = event.currentTarget.value === 'table' ? 'table' : 'cards';
          setView(next); localStorage.setItem('lifeapp-workout-view', next);
        }}><option value="cards">Workout cards</option><option value="table">Spreadsheet</option></select></label>
      </div>
      <button type="button" onClick={() => setReportOpen(true)}>Export monthly PDF</button>
      {!visible.length && <p>No workouts match these filters.</p>}
      {visible.map(value => <article key={value.metadata.id} className="workout-history">
        {view === 'table' ? <><h3>{title(value)}</h3><p>{summary(value)}</p>{rows(value)}</> :
          <details><summary>{title(value)} · {summary(value)}</summary>{rows(value)}{value.notes && <p>{value.notes}</p>}</details>}
        <div className="actions"><button disabled={busy} onClick={() => keepDraft({ session: value,
          revision: value.metadata.revision })}>Edit workout</button>
          <button disabled={busy} onClick={() => setConfirm(value)}>Delete workout</button></div>
      </article>)}
      {confirm && <div role="alertdialog" aria-label="Delete workout confirmation" className="editor">
        <p>Move {title(confirm)} to Recently deleted?</p>
        <button disabled={busy} onClick={() => void write(async () => {
          await store.setWorkoutDeleted(confirm.metadata.id, true); setConfirm(null);
        })}>Confirm delete</button>
        <button disabled={busy} onClick={() => setConfirm(null)}>Cancel</button>
      </div>}
      {sessions.some(value => value.deleted) && <details><summary>Recently deleted</summary>
        {sessions.filter(value => value.deleted).map(value => <article key={value.metadata.id}>{title(value)}
          <button disabled={busy} onClick={() => void write(() => store.setWorkoutDeleted(value.metadata.id, false))}>Restore workout</button>
        </article>)}</details>}
      {!historyOnly && <>
        <details open={library} onToggle={event => setLibrary(event.currentTarget.open)}><summary>Exercise library</summary>
          {library && <><label>Search exercises<input type="search" value={query} onChange={event => setQuery(event.currentTarget.value)} /></label>
            <button onClick={() => setEditor({ kind: 'exercise' })}>New exercise</button>
            {exercises.filter(value => value.name.toLowerCase().includes(query.toLowerCase())).map(value =>
              <article key={value.metadata.id}><h3>{value.name}{value.archived && ' (archived)'}</h3>
                <p>{value.category} {value.description}</p>
                <button onClick={() => setEditor({ kind: 'exercise', value })}>Edit exercise</button>
                <button disabled={busy} onClick={() => void write(() => store.saveWorkout({ kind: 'exercise',
                  value: { ...value, metadata: revise(value.metadata), archived: !value.archived } }, value.metadata.revision))}>
                  {value.archived ? 'Restore exercise' : 'Archive exercise'}</button></article>)}</>}
        </details>
        <details open={routinesOpen} onToggle={event => setRoutinesOpen(event.currentTarget.open)}><summary>Manage routines</summary>
          {routinesOpen && <><button onClick={() => setEditor({ kind: 'routine' })}>New routine</button>
            {routines.map(value => <article key={value.metadata.id}><h3>{value.name}</h3>
              <p>{value.exerciseOrder.map(byId).join(' · ')}</p>
              <button onClick={() => setEditor({ kind: 'routine', value })}>Edit routine</button></article>)}</>}
        </details>
      </>}
    </>)}
    {editor?.kind === 'exercise' && <form key={editor.value?.metadata.id ?? 'new-exercise'} className="editor" onSubmit={event => {
      event.preventDefault();
      const values = new FormData(event.currentTarget);
      const original = editor.value;
      void write(async () => {
        await store.saveWorkout({ kind: 'exercise', value: { metadata: revise(original?.metadata),
          name: text(values, 'name'), category: text(values, 'category') || null, equipment: original?.equipment ?? null,
          description: text(values, 'description'), archived: original?.archived ?? false } }, original?.metadata.revision ?? null);
        setEditor(null);
      });
    }}><h2>{editor.value ? 'Edit exercise' : 'New exercise'}</h2>
      <label>Exercise name<input name="name" required maxLength={120} defaultValue={editor.value?.name} /></label>
      <label>Category<input name="category" maxLength={120} defaultValue={editor.value?.category ?? ''} /></label>
      <label>Description<textarea name="description" maxLength={4000} defaultValue={editor.value?.description} /></label>
      <button disabled={busy}>Save exercise</button><button type="button" onClick={() => setEditor(null)}>Cancel</button>
    </form>}
    {editor?.kind === 'routine' && <RoutineEditor key={editor.value?.metadata.id ?? 'new-routine'} routine={editor.value}
      exercises={exercises} busy={busy} close={() => setEditor(null)} save={(name, exerciseOrder, plannedSetCounts) => {
        const prior = editor.value;
        void write(async () => {
          await store.saveWorkout({ kind: 'routine', value: { metadata: revise(prior?.metadata), name,
            exerciseOrder, plannedSetCounts } }, prior?.metadata.revision ?? null);
          setEditor(null);
        });
      }} />}
  </section>;
}

function RoutineEditor({ routine, exercises, busy, close, save }: { routine?: Routine; exercises: Exercise[]; busy: boolean;
  close: () => void; save: (name: string, order: string[], counts: (number | null)[]) => void }) {
  const [plan, setPlan] = useState(() => (routine?.exerciseOrder ?? []).map((id, index) => ({
    id, count: routine?.plannedSetCounts?.[index] ?? null,
  })));
  return <form className="editor" onSubmit={event => {
    event.preventDefault(); save(text(new FormData(event.currentTarget), 'name'), plan.map(item => item.id), plan.map(item => item.count));
  }}>
    <h2>Workout routine</h2><label>Routine name<input name="name" required maxLength={120} defaultValue={routine?.name} /></label>
    <ol>{plan.map(({ id, count }, index) => <li key={`${index}-${id}`}>{exercises.find(exercise => exercise.metadata.id === id)?.name}
      <label>Planned sets<input type="number" min="1" max="100" value={count ?? ''} onChange={event => {
        const next = event.currentTarget.valueAsNumber;
        setPlan(plan.map((item, position) => position === index ? { id, count: Number.isNaN(next) ? null : next } : item));
      }} /></label>
      <button type="button" disabled={!index} onClick={() => setPlan(move(plan, index, -1))}>Move up</button>
      <button type="button" disabled={index === plan.length - 1} onClick={() => setPlan(move(plan, index, 1))}>Move down</button>
      <button type="button" onClick={() => setPlan(plan.filter((_, position) => position !== index))}>Remove</button></li>)}</ol>
    {exercises.filter(exercise => !exercise.archived).map(exercise => <button type="button" key={exercise.metadata.id}
      onClick={() => setPlan([...plan, { id: exercise.metadata.id, count: null }])}>Add {exercise.name}</button>)}
    <button type="submit" disabled={busy || !plan.length}>Save routine</button>
    <button type="button" disabled={busy} onClick={close}>Cancel</button>
  </form>;
}
