import { useState } from 'react';
import { today } from './protocol.ts';
import { setSummary } from './workout.ts';
import type { Exercise, Session } from './workout.ts';

const format = new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 });

function measured(values: (number | null | undefined)[]) {
  return values.flatMap(value => value === null || value === undefined ? [] : [value]);
}

export function WorkoutReport({ sessions, exercises, close }: {
  sessions: Session[];
  exercises: Exercise[];
  close: () => void;
}) {
  const [month, setMonth] = useState(today().slice(0, 7));
  const [compact, setCompact] = useState(false);
  const rows = sessions.filter(session => !session.deleted && (session.completed || session.logged) && session.date.slice(0, 7) === month);
  const names = new Map(exercises.map(exercise => [exercise.metadata.id, exercise.name]));
  const minutes = measured(rows.map(session => session.durationSeconds === null ? null : session.durationSeconds / 60));
  const heartRates = measured(rows.map(session => session.averageHeartRateBpm));
  const calories = measured(rows.map(session => session.caloriesKcal));
  const total = (values: number[]) => values.reduce((sum, value) => sum + value, 0);
  const show = (value: number | null, unit: string) => value === null ? 'Unavailable' : format.format(value) + ' ' + unit;
  const setText = (set: Session['entries'][number]['sets'][number]) => `Set ${set.number} · ${setSummary(set)}`;

  return <section className="workout-report">
    <div className="workout-report-controls">
      <button onClick={close}>Back to workouts</button>
      <label>Month<input type="month" value={month} onChange={event => setMonth(event.currentTarget.value)} /></label>
      <label><input type="checkbox" checked={compact} onChange={event => setCompact(event.currentTarget.checked)} /> Compact landscape</label>
      <button disabled={!month} onClick={() => window.print()}>Print or save PDF</button>
    </div>
    <style>{`@media print { @page { size: A4 ${compact ? 'landscape' : 'portrait'}; margin: 13mm; } }`}</style>
    <h1>LifeApp Workout Report</h1>
    <p>{month}</p>
    <dl>
      <dt>Recorded workouts</dt><dd>{rows.length}</dd>
      <dt>Total duration</dt><dd>{show(minutes.length ? total(minutes) : null, 'min')}</dd>
      <dt>Average duration</dt><dd>{show(minutes.length ? total(minutes) / minutes.length : null, 'min')} · {minutes.length} measured sessions</dd>
      <dt>Average heart rate</dt><dd>{show(heartRates.length ? total(heartRates) / heartRates.length : null, 'BPM')} · {heartRates.length} measured sessions</dd>
      <dt>Recorded calories</dt><dd>{show(calories.length ? total(calories) : null, 'kcal')} · {calories.length} measured sessions</dd>
    </dl>
    {!rows.length && <p>No recorded workouts this month.</p>}
    {rows.map(session => <article key={session.metadata.id}>
      <h2>{session.date} · {session.routineName ?? 'Workout'}</h2>
      <p>{show(session.durationSeconds === null ? null : session.durationSeconds / 60, 'min')} ·
        {' '}{show(session.averageHeartRateBpm ?? null, 'BPM')} · {show(session.caloriesKcal, 'kcal')}</p>
      {compact ? <table>
        <thead><tr><th scope="col">Exercise</th><th scope="col">Recorded sets</th><th scope="col">Remarks</th></tr></thead>
        <tbody>{session.entries.map(entry => <tr key={entry.metadata.id}>
          <th scope="row">{names.get(entry.exerciseId) ?? 'Exercise'}</th>
          <td>{entry.sets.map(setText).join(' / ') || 'No sets recorded'}</td><td>{entry.remarks}</td>
        </tr>)}</tbody>
      </table> : session.entries.map(entry => <section key={entry.metadata.id} className="workout-report-exercise">
        <h3>{names.get(entry.exerciseId) ?? 'Exercise'}</h3>
        {entry.sets.length ? <ul>{entry.sets.map(set => <li key={set.metadata.id}>{setText(set)}</li>)}</ul> : <p>No sets recorded</p>}
        {entry.remarks && <p>{entry.remarks}</p>}
      </section>)}
      {session.notes && <p className="workout-report-notes">{session.notes}</p>}
    </article>)}
  </section>;
}
