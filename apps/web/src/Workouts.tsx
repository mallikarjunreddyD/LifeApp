import { useEffect, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { now, today } from './protocol.ts';
import type { LifeStore } from './store.ts';
import { WorkoutReport } from './WorkoutReport.tsx';
import { entryFor, revise } from './workout.ts';
import type { Entry, Exercise, RecordedSet, Routine, Session, WorkoutRecord } from './workout.ts';

type Editor = { kind: 'exercise'; exercise?: Exercise } | { kind: 'routine'; routine?: Routine } |
  { kind: 'set'; entry: Entry; set?: RecordedSet } | { kind: 'details' } | { kind: 'finish' };

export function Workouts({ store, historyOnly = false }: { store: LifeStore; historyOnly?: boolean }) {
  const [records, setRecords] = useState<WorkoutRecord[] | null>(null);
  const [active, setActive] = useState<string | null>(null);
  const [editor, setEditor] = useState<Editor | null>(null);
  const [reportOpen, setReportOpen] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let current = true;
    void store.workoutRecords().then(value => { if (current) setRecords(value); })
      .catch(() => { if (current) setError('Could not load saved workouts.'); });
    return () => { current = false; };
  }, [store]);

  async function save(build: () => WorkoutRecord, expected: string | null, open = false) {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      const record = build();
      await store.saveWorkout(record, expected);
      setRecords(await store.workoutRecords());
      if (open) setActive(record.value.metadata.id);
      setEditor(null);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not save the workout.');
    } finally { setBusy(false); }
  }

  const exercises = (records ?? []).flatMap(item => item.kind === 'exercise' ? [item.value] : [])
    .sort((a, b) => a.name.localeCompare(b.name));
  const sessions = (records ?? []).flatMap(item => item.kind === 'session' ? [item.value] : [])
    .sort((a, b) => b.date.localeCompare(a.date) || (b.startedAt ?? '').localeCompare(a.startedAt ?? ''));
  const routines = (records ?? []).flatMap(item => item.kind === 'routine' ? [item.value] : []);
  const session = sessions.find(item => item.metadata.id === active);
  const available = exercises.filter(exercise => !exercise.archived);
  const names = new Map(exercises.map(exercise => [exercise.metadata.id, exercise.name]));
  const byId = (id: string) => names.get(id) ?? id;
  const start = (routine?: Routine) => void save(() => {
    const metadata = revise();
    return { kind: 'session', value: { metadata, date: today(), startedAt: metadata.createdAt,
      endedAt: null, durationSeconds: null, caloriesKcal: null, notes: null, completed: false,
      entries: routine?.exerciseOrder.map((id, index) => {
        const entry = entryFor(metadata.id, id, index);
        entry.sets = Array.from({ length: routine.plannedSetCounts?.[index] ?? 0 }, (_, offset) => ({
          metadata: revise(), entryId: entry.metadata.id, number: offset + 1, weight: null, repetitions: null,
          durationSeconds: null, restSeconds: null, completed: false,
        }));
        return entry;
      }) ?? [],
    } };
  }, null, true);
  const change = (update: (value: Session) => Session) => {
    if (session) void save(() => ({ kind: 'session', value: { ...update(session), metadata: revise(session.metadata) } }),
      session.metadata.revision);
  };
  const date = new Date();
  const weekStart = new Date(date.getFullYear(), date.getMonth(), date.getDate() - (date.getDay() + 6) % 7);
  const completed = sessions.filter(value => value.completed);
  const weekly = completed.filter(value => value.date >= today(weekStart) && value.date <= today(date));

  function form(title: string, contents: ReactNode, submit: (values: FormData) => void) {
    return <form key={title} className="editor" onSubmit={(event: FormEvent<HTMLFormElement>) => {
      event.preventDefault(); submit(new FormData(event.currentTarget));
    }}>
      <h2>{title}</h2>{contents}<div className="actions"><button type="submit" disabled={busy}>Save</button>
        <button type="button" disabled={busy} onClick={() => { setEditor(null); setError(''); }}>Cancel</button></div>
    </form>;
  }

  function setForm(selection: Extract<Editor, { kind: 'set' }>) {
    return form(selection.set ? 'Edit set' : 'Add set', <>
      <label>Weight<input type="number" step="any" min="0" inputMode="decimal" name="weight" defaultValue={selection.set?.weight?.value ?? ''} /></label>
      <label>Weight unit<select name="unit" defaultValue={selection.set?.weight?.unit ?? 'KG'}><option>KG</option><option>LB</option></select></label>
      <label>Repetitions<input type="number" min="0" inputMode="numeric" name="reps" defaultValue={selection.set?.repetitions ?? ''} /></label>
      <label>Duration (seconds)<input type="number" min="1" name="duration" defaultValue={selection.set?.durationSeconds ?? ''} /></label>
      <label>Rest (seconds)<input type="number" min="0" name="rest" defaultValue={selection.set?.restSeconds ?? ''} /></label>
      <label><input type="checkbox" name="completed" defaultChecked={selection.set?.completed ?? false} /> Set completed</label>
    </>, values => change(value => {
      const weight = number(values, 'weight');
      const set: RecordedSet = { metadata: revise(selection.set?.metadata), entryId: selection.entry.metadata.id,
        number: selection.set?.number ?? selection.entry.sets.length + 1,
        weight: weight === null ? null : { value: weight, unit: text(values, 'unit') === 'LB' ? 'LB' : 'KG' },
        repetitions: number(values, 'reps'), durationSeconds: number(values, 'duration'), restSeconds: number(values, 'rest'),
        completed: values.has('completed') };
      return { ...value, entries: value.entries.map(entry => entry.metadata.id !== selection.entry.metadata.id ? entry : {
        ...entry, metadata: revise(entry.metadata), sets: selection.set ? entry.sets.map(previous => previous.metadata.id === set.metadata.id ? set : previous) : [...entry.sets, set],
      }) };
    }));
  }

  if (reportOpen) return <WorkoutReport sessions={sessions} exercises={exercises} close={() => setReportOpen(false)} />;

  return <section>
    <h1>{historyOnly ? 'Workout history' : 'Workouts'}</h1>
    <p>Saved in this browser. Workout changes stay on this device.</p>
    {error && <p role="alert">{error}</p>}
    {!records && !error && <p role="status">Loading workouts…</p>}
    {records && (session ? <>
      <button disabled={busy} onClick={() => { setActive(null); setEditor(null); }}>Back to workouts</button>
      <h2>{session.date} · {session.completed ? 'Completed' : 'In progress'}</h2>
      <button disabled={busy} onClick={() => setEditor({ kind: 'details' })}>Edit workout details</button>
      <p role="status">{busy ? 'Saving…' : error ? 'Changes need attention' : 'Saved on this device'}</p>
      {!session.completed && <button disabled={busy} onClick={() => setEditor({ kind: 'finish' })}>Finish workout</button>}
      <form onSubmit={event => {
        event.preventDefault();
        const id = text(new FormData(event.currentTarget), 'exercise');
        if (id) change(value => ({ ...value, entries: [...value.entries, entryFor(value.metadata.id, id, value.entries.length)] }));
      }}>
        <label>Exercise<select name="exercise" required>{available.map(value =>
          <option key={value.metadata.id} value={value.metadata.id}>{value.name}</option>)}</select></label>
        <button disabled={busy || !available.length}>Add exercise entry</button>
      </form>
      {session.entries.map(entry => <article key={entry.metadata.id}>
        <h3>{byId(entry.exerciseId)}</h3>
        <ul>{entry.sets.map(set => <li key={set.metadata.id}>
          {editor?.kind === 'set' && editor.entry.metadata.id === entry.metadata.id && editor.set?.metadata.id === set.metadata.id
            ? setForm(editor)
            : <button disabled={busy} onClick={() => setEditor({ kind: 'set', entry, set })}>Set {set.number} · {setSummary(set)}</button>}
        </li>)}</ul>
        {editor?.kind === 'set' && editor.entry.metadata.id === entry.metadata.id && !editor.set
          ? setForm(editor)
          : <button disabled={busy} onClick={() => setEditor({ kind: 'set', entry })}>Add set</button>}

      </article>)}
    </> : <>
      <p>{weekly.length} completed this week · {weekly.reduce((sum, value) => sum + (value.durationSeconds ?? 0), 0)} seconds</p>
      {!historyOnly && <button disabled={busy} onClick={() => start()}>Start workout</button>}
      <button disabled={busy} onClick={() => setReportOpen(true)}>Export monthly PDF</button>
      <ul className="record-list">{sessions.map(value => <li key={value.metadata.id}><button disabled={busy}
        onClick={() => { setActive(value.metadata.id); setEditor(null); }}>{value.date} ·
        {value.completed ? ` ${value.durationSeconds ?? 0} seconds` : ' In progress'} · {value.entries.length} exercises</button></li>)}</ul>
      {!historyOnly && <>
        <h2>Exercise library</h2><button disabled={busy} onClick={() => setEditor({ kind: 'exercise' })}>New exercise</button>
        {exercises.map(exercise => <article key={exercise.metadata.id}>
          <h3>{exercise.name}{exercise.archived ? ' (archived)' : ''}</h3>
          <p>{[exercise.category, exercise.equipment].filter(Boolean).join(' · ')}</p>
          <button disabled={busy} onClick={() => setEditor({ kind: 'exercise', exercise })}>Edit exercise</button>
          <button disabled={busy} onClick={() => void save(() => ({ kind: 'exercise', value: {
            ...exercise, metadata: revise(exercise.metadata), archived: !exercise.archived,
          } }), exercise.metadata.revision)}>{exercise.archived ? 'Restore' : 'Archive'}</button>
          <details><summary>Exercise history</summary><ul>{completed.flatMap(workout =>
            workout.entries.filter(entry => entry.exerciseId === exercise.metadata.id)
              .flatMap(entry => entry.sets.filter(set => set.completed).map(set =>
                <li key={set.metadata.id}>{workout.date} · {setSummary(set)}</li>)))}</ul></details>
        </article>)}
        <h2>Routines</h2><button disabled={busy || !available.length} onClick={() => setEditor({ kind: 'routine' })}>New routine</button>
        {routines.map(routine => <article key={routine.metadata.id}>
          <h3>{routine.name}</h3><p>{routine.exerciseOrder.map(byId).join(' · ')}</p>
          <button disabled={busy} onClick={() => setEditor({ kind: 'routine', routine })}>Edit routine</button>
          <button disabled={busy || routine.exerciseOrder.some(id => !available.some(exercise => exercise.metadata.id === id))}
            onClick={() => start(routine)}>Start routine</button>
        </article>)}
      </>}
    </>)}
    {editor?.kind === 'exercise' && form(editor.exercise ? 'Edit exercise' : 'New exercise', <>
      <label>Name<input name="name" required maxLength={120} defaultValue={editor.exercise?.name} /></label>
      <label>Category or body part<input name="category" maxLength={120} defaultValue={editor.exercise?.category ?? ''} /></label>
      <label>Equipment<input name="equipment" maxLength={120} defaultValue={editor.exercise?.equipment ?? ''} /></label>
    </>, values => void save(() => ({ kind: 'exercise', value: {
      metadata: revise(editor.exercise?.metadata), name: text(values, 'name'),
      category: text(values, 'category') || null, equipment: text(values, 'equipment') || null,
      archived: editor.exercise?.archived ?? false,
    } }), editor.exercise?.metadata.revision ?? null))}
    {editor?.kind === 'routine' && <RoutineEditor key={editor.routine?.metadata.id ?? 'new'} routine={editor.routine}
      exercises={exercises} busy={busy} close={() => setEditor(null)} save={(name, exerciseOrder, plannedSetCounts) => void save(() => ({
        kind: 'routine', value: { metadata: revise(editor.routine?.metadata), name, exerciseOrder, plannedSetCounts },
      }), editor.routine?.metadata.revision ?? null)} />}
    {(editor?.kind === 'details' || editor?.kind === 'finish') && session && form(
      editor.kind === 'finish' ? 'Finish workout' : 'Workout details', <>
      {editor.kind === 'finish' && <p>{session.entries.filter(entry => !entry.sets.length || entry.sets.some(set => !set.completed)).length}
        {' '}incomplete exercises will remain in this session.</p>}
      <label>Workout duration (minutes)<input type="number" name="minutes" min="0" max="35791394"
        inputMode="numeric" defaultValue={session.durationSeconds === null ? '' : Math.floor(session.durationSeconds / 60)} /></label>
      <label>Average heart rate (BPM)<input type="number" name="bpm" min="20" max="250"
        inputMode="numeric" defaultValue={session.averageHeartRateBpm ?? ''} /></label>
      <label>Date<input type="date" name="date" required defaultValue={session.date} /></label>
      <label>Calories (kcal)<input type="number" name="calories" min="0" defaultValue={session.caloriesKcal ?? ''} /></label>
      <label>Notes<textarea name="notes" maxLength={4000} defaultValue={session.notes ?? ''} /></label>
    </>, values => change(value => {
      const minutes = number(values, 'minutes');
      const bpm = number(values, 'bpm');
      const durationChanged = minutes !== (value.durationSeconds === null ? null : Math.floor(value.durationSeconds / 60));
      const finishing = editor.kind === 'finish';
      const startedAt = finishing ? value.startedAt ?? now() : value.startedAt;
      const endedAt = finishing && startedAt ? (Date.parse(now()) < Date.parse(startedAt) ? startedAt : now()) : value.endedAt;
      const elapsed = endedAt && startedAt ? Math.floor((Date.parse(endedAt) - Date.parse(startedAt)) / 1000) : null;
      const updated = { ...value, date: text(values, 'date'), caloriesKcal: number(values, 'calories'),
        notes: text(values, 'notes') || null, startedAt, endedAt, completed: finishing || value.completed,
        durationSeconds: durationChanged ? (minutes === null ? elapsed : minutes * 60) : value.durationSeconds ?? elapsed,
        durationManuallyEntered: durationChanged ? minutes !== null : value.durationManuallyEntered ?? false,
      };
      if (bpm === null) delete updated.averageHeartRateBpm;
      else updated.averageHeartRateBpm = bpm;
      return updated;
    }))}

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
      <button type="button" onClick={() => setPlan(plan.filter((_, position) => position !== index))}>Remove</button></li>)}</ol>
    {exercises.filter(exercise => !exercise.archived).map(exercise => <button type="button" key={exercise.metadata.id}
      onClick={() => setPlan([...plan, { id: exercise.metadata.id, count: null }])}>Add {exercise.name}</button>)}
    <button type="submit" disabled={busy || !plan.length}>Save routine</button>
    <button type="button" disabled={busy} onClick={close}>Cancel</button>
  </form>;
}

const text = (data: FormData, name: string) => String(data.get(name) ?? '').trim();
const number = (data: FormData, name: string) => text(data, name) === '' ? null : Number(text(data, name));
const setSummary = (set: RecordedSet) => [
  set.weight && `${set.weight.value} ${set.weight.unit}`, set.repetitions !== null && `${set.repetitions} reps`,
  set.durationSeconds !== null && `${set.durationSeconds}s active`, set.restSeconds !== null && `${set.restSeconds}s rest`,
  set.completed ? 'Completed' : 'Open',
].filter(Boolean).join(' · ');
