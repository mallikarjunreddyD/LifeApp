import { useCallback, useEffect, useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { ApiError, api, synchronize } from './api.ts';
import type { BrowserSession } from './api.ts';
import { sameScope, today } from './protocol.ts';
import type { Todo } from './protocol.ts';
import type { LifeStore, LocalView, TaskView } from './store.ts';
import { resolveWorkoutConflict, syncWorkouts } from './workoutSync.ts';
import type { WorkoutConflict } from './workoutSync.ts';
import { syncHabits } from './habitSync.ts';
import { Habits } from './Habits.tsx';
import { Workouts } from './Workouts.tsx';
import { Expenses } from './Expenses.tsx';
import { useExpenseSync } from './useExpenseSync.ts';
import { useWorkerUpdate } from './worker.ts';
import packageInfo from '../package.json';
import { browserExport, downloadExport } from './export.ts';

type Tab = 'To-do' | 'Workouts' | 'History' | 'Habits' | 'Expenses' | 'Settings';
const tabs: Tab[] = ['To-do', 'Workouts', 'History', 'Habits', 'Expenses', 'Settings'];
const ACTIVE_SYNC_INTERVAL = 5 * 60 * 1000;
const blank: LocalView = { tasks: [], pending: 0, checkpoint: null, lastSync: null };

export function App({ store }: { store: LifeStore }) {
  const [tab, setTab] = useState<Tab>('To-do');
  const [view, setView] = useState(blank);
  const [loaded, setLoaded] = useState(false);
  const [session, setSession] = useState<BrowserSession | null>(null);
  const [online, setOnline] = useState(navigator.onLine);
  const [busy, setBusy] = useState(false);
  const [synced, setSynced] = useState(false);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const running = useRef(false);
  const again = useRef(false);
  const failures = useRef(0);
  const retryDeadline = useRef(0);
  const serverDeadline = useRef(0);
  const [retryAt, setRetryAt] = useState(0);
  const [visible, setVisible] = useState(!document.hidden);
  const expenseSync = useExpenseSync(store, session, online, visible);
  const [habitGeneration, setHabitGeneration] = useState(0);
  const [workoutGeneration, setWorkoutGeneration] = useState(0);
  const [workoutConnected, setWorkoutConnected] = useState(false);
  const [workoutConflicts, setWorkoutConflicts] = useState<WorkoutConflict[]>([]);
  const [workoutStatus, setWorkoutStatus] = useState('');
  const [structuredBusy, setStructuredBusy] = useState(false);

  async function syncStructured(current: BrowserSession) {
    setStructuredBusy(true);
    try {
      await Promise.all([sync(current, true), expenseSync.syncNow()]);
    } finally {
      setStructuredBusy(false);
    }
  }
  const scheduleRetry = useCallback((at: number) => {
    retryDeadline.current = at;
    setRetryAt(at);
  }, []);
  const channel = useRef<BroadcastChannel | null>(null);
  const worker = useWorkerUpdate();
  const scope = view.checkpoint?.scope ?? null;
  const matches = session !== null && sameScope(scope, session.scope);
  const habitScope = session?.scope ?? scope;

  const refresh = useCallback(async () => {
    const [local, workout] = await Promise.all([store.view(), store.workoutBinding()]);
    setView(local);
    setWorkoutConnected(workout !== null);
  }, [store]);

  const sync = useCallback(async (current: BrowserSession, manual = false) => {
    if (!navigator.onLine || Date.now() < (manual ? serverDeadline.current : retryDeadline.current)) return;
    if (running.current) { again.current = true; return; }
    running.current = true;
    setBusy(true);
    setSynced(false);
    setError('');
    setNotice('');
    try {
      const verified = await api.session();
      setSession(verified);
      if (!sameScope(current.scope, verified.scope)) {
        throw new Error('Review your connection before syncing again. Your local changes were kept.');
      }
      const complete = await synchronize(store, verified);
      setSynced(complete);
      failures.current = 0;
      serverDeadline.current = 0;
      scheduleRetry(complete ? 0 : Date.now() + 1000);
      setNotice(complete ? 'To-do synchronization complete.' : 'Checking the remaining To-do changes.');
      try { await syncHabits(store, verified); }
      catch { setNotice('To-do checked. Habit changes are saved locally and waiting to sync.'); }
      finally { setHabitGeneration(generation => generation + 1); }
      try {
        if (await store.workoutBinding()) {
          const conflicts = await syncWorkouts(store, verified);
          setWorkoutConflicts(conflicts);
          setWorkoutStatus(conflicts.length ? `${conflicts.length} workout records need review. Both copies were kept.` : 'Workouts checked with the server.');
        }
      } catch (failure) { setWorkoutStatus(message(failure)); }
      finally { setWorkoutGeneration(generation => generation + 1); }

    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) {
        setSession(null);
        scheduleRetry(0);
      } else if (failure instanceof ApiError ? failure.status === 429 || failure.status >= 500
        : failure instanceof Error && failure.cause !== undefined) {
        const delay = Math.min(300_000, 5000 * 2 ** Math.min(failures.current++, 6)) + Math.random() * 1500;
        serverDeadline.current = failure instanceof ApiError ? failure.retryAfter ?? 0 : 0;
        scheduleRetry(Math.max(Date.now() + delay, serverDeadline.current));
      }
      setError(message(failure));
    } finally {
      if (again.current && !retryDeadline.current) scheduleRetry(Date.now() + 1000);
      again.current = false;
      try { await refresh(); } catch (failure) { setError(message(failure)); }
      running.current = false;
      setBusy(false);
      channel.current?.postMessage('refresh');
    }
  }, [refresh, scheduleRetry, store]);

  useEffect(() => {
    let active = true;
    async function hydrate() {
      try {
        const [local, workout] = await Promise.all([store.view(), store.workoutBinding()]);
        if (!active) return;
        setView(local);
        setWorkoutConnected(workout !== null);
        setLoaded(true);
        if (!navigator.onLine) return;
        const current = await api.session();
        if (!active) return;
        setSession(current);
        if (sameScope(local.checkpoint?.scope ?? null, current.scope)) await sync(current);
      } catch (failure) {
        if (!active) return;
        if (failure instanceof ApiError && failure.status === 401) {
          setSession(null);
          setSynced(false);
        } else setError(message(failure));
      }
    }
    void hydrate();
    const bus = typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel('lifeapp-local');
    channel.current = bus;
    if (bus) bus.onmessage = () => {
      void refresh().catch(failure => setError(message(failure)));
      setSynced(false);
    };
    const network = () => {
      setOnline(navigator.onLine);
      if (navigator.onLine) void hydrate();
      else setSynced(false);
    };
    const visibility = () => {
      setVisible(!document.hidden);
      if (!document.hidden) void hydrate();
    };
    document.addEventListener('visibilitychange', visibility);
    window.addEventListener('online', network);
    window.addEventListener('offline', network);
    return () => {
      active = false;
      bus?.close();
      channel.current = null;
      document.removeEventListener('visibilitychange', visibility);
      window.removeEventListener('online', network);
      window.removeEventListener('offline', network);
    };
  }, [refresh, store, sync]);

  useEffect(() => {
    if (!session || !matches || !visible || !online) return;
    const timeout = window.setTimeout(() => void sync(session), retryAt
      ? Math.min(2_147_483_647, Math.max(0, retryAt - Date.now()))
      : ACTIVE_SYNC_INTERVAL);
    return () => window.clearTimeout(timeout);
  }, [matches, online, retryAt, session, sync, visible]);

  async function change(action: () => Promise<void>) {
    setError('');
    setNotice('');
    try {
      await action();
      setSynced(false);
      await refresh();
      channel.current?.postMessage('refresh');
      if (session && matches && navigator.onLine) void sync(session);
      return true;
    } catch (failure) {
      setError(message(failure));
      return false;
    }
  }

  async function login(username: string, password: string, secondFactor?: string) {
    setBusy(true);
    setError('');
    try {
      const current = await api.login(username, password, secondFactor);
      setSession(current);
      failures.current = 0;
      serverDeadline.current = 0;
      scheduleRetry(0);
      setSynced(false);
      if (sameScope(scope, current.scope)) await sync(current);
    } catch (failure) { setError(message(failure)); }
    finally { setBusy(false); }
  }

  async function bind() {
    if (!session) return;
    setStructuredBusy(true);
    try {
      await store.seedExpenses();
      const [records, workoutRecords] = await Promise.all([store.expenseRecords(), store.workoutRecords()]);
      const serverCount = await api.expenseInventory(session);
      const localCount = records.filter(record => record.kind === 'expense').length;
      if (!window.confirm(`Connect synchronization? This browser has ${view.tasks.length} tasks, ${localCount} expenses and ${workoutRecords.length} workout records. The server has ${serverCount} expense records and labels. Queued changes will upload to this account.`)) return;
      if (await api.expenseInventory(session) !== serverCount) throw new Error('Server expenses changed. Review again.');
      await store.bindStructured(session.scope, records, workoutRecords);
      await Promise.all([refresh(), expenseSync.refresh()]);
      await syncStructured(session);
    } catch (failure) { setError(message(failure)); }
    finally { setStructuredBusy(false); }
  }

  async function logout() {
    if (!session) return;
    setBusy(true);
    try {
      await api.logout(session);
      setSession(null);
      scheduleRetry(0);
      setSynced(false);
      setNotice('Signed out. Local tasks and queued changes are still on this browser.');
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) setSession(null);
      else setError(message(failure));
    } finally { setBusy(false); }
  }

  const status = !online ? 'Offline' : busy ? 'Working…' : view.pending > 0 ? 'Pending sync' :
    view.tasks.some(task => task.conflicts.length > 0) ? 'Conflict needs review' :
    !session ? 'Signed out' : !matches ? 'Review connection' : synced ? 'To-do up to date' : 'Ready to sync';

  return <div className="shell">
    <header className="masthead">
      <div><p className="brand">LifeApp</p><p className="subtitle">Personal journal</p></div>
      <div className="sync-status" aria-live="polite"><strong>{status}</strong><span>{view.pending} pending</span></div>
    </header>
    {worker && <aside className="banner">An app update is ready. <button onClick={worker}>Reload app</button></aside>}
    {(error || notice) && <div className={error ? 'message error' : 'message'} role={error ? 'alert' : 'status'}>{error || notice}</div>}
    <main>
      {tab === 'To-do' && (loaded ? <Todos view={view} change={change} store={store} /> : <p role="status">Loading local tasks…</p>)}
      {tab === 'Settings' && <Settings view={view} session={session} busy={busy || structuredBusy} online={online}
        onLogin={login} onBind={bind} onLogout={logout} status={status} expensePending={expenseSync.state?.pending.length ?? 0} expenseConnected={expenseSync.state !== null} workoutConnected={workoutConnected} workoutStatus={workoutStatus} onExport={async () => {
          if (session && matches && navigator.onLine) await sync(session);
          downloadExport(await browserExport(store));
        }} onConflicts={() => setTab('To-do')} onSync={() => session && void syncStructured(session)} />}
      {(tab === 'Workouts' || tab === 'History') && <>
        <Workouts key={tab} store={store} historyOnly={tab === 'History'} generation={workoutGeneration}
          syncStatus={workoutStatus} conflicts={matches ? workoutConflicts : []}
          onResolve={async (conflict, keepLocal) => {
            if (!session || !matches || !online || busy) throw new Error('Wait for synchronization and try again.');
            await resolveWorkoutConflict(store, session, conflict, keepLocal);
            await sync(session, true);
          }}
          onSaved={() => { if (session && matches && navigator.onLine) void sync(session); }} />
        {matches && <details><summary>Read-only server records</summary><ReadRecords key={tab}
          kind={tab === 'Workouts' ? 'exercises' : 'workouts'} session={session} online={online} /></details>}
      </>}
      {tab === 'Expenses' && <Expenses store={store} sync={expenseSync} />}
      {tab === 'Habits' && <section><h1>Habits</h1>
        {habitScope ? <Habits key={habitScope.dataset + '/' + habitScope.epoch} scope={habitScope}
          session={session} online={online} store={store} generation={habitGeneration} />
          : <p>{online ? 'Sign in to view server habit months.' : 'Connect to view server habit months.'}</p>}
      </section>}
    </main>
    <nav aria-label="Main navigation">
      {tabs.map(item => <button key={item} aria-current={tab === item ? 'page' : undefined} onClick={() => setTab(item)}>{item}</button>)}
    </nav>
  </div>;
}

function Todos({ view, change, store }: { view: LocalView; store: LifeStore; change: (write: () => Promise<void>) => Promise<boolean> }) {
  const [editor, setEditor] = useState<TaskView | 'new' | null>(null);
  const date = today();
  const sorted = view.tasks.filter(task => !task.record.deleted && task.conflicts.length === 0)
    .sort((a, b) => a.record.deadline.localeCompare(b.record.deadline) || a.record.title.localeCompare(b.record.title));
  const groups = [
    ['Overdue', sorted.filter(task => !task.record.completedAt && task.record.deadline < date)],
    ['Pending', sorted.filter(task => !task.record.completedAt && task.record.deadline >= date)],
    ['Completed', sorted.filter(task => task.record.completedAt)],
  ] as const;
  const conflicts = view.tasks.filter(task => task.conflicts.length > 0);
  return <section>
    <div className="section-heading"><h1>To-do</h1><button className="primary" onClick={() => setEditor('new')}>Add task</button></div>
    {editor && <TaskForm key={editor === 'new' ? 'new' : editor.record.metadata.id} task={editor === 'new' ? undefined : editor.record}
      cancel={() => setEditor(null)} save={async (name, deadline) => {
        const saved = await change(() => editor === 'new' ? store.add(name, deadline) :
          store.edit(editor.record.metadata.id, { title: name, deadline }, editor.head));
        if (saved) setEditor(null);
      }} />}
    {conflicts.length > 0 && <section aria-label="Task conflicts" className="conflicts">
      <h2>Conflicts to review</h2>
      {conflicts.map(task => <Conflict key={task.record.metadata.id} task={task} change={change} store={store} />)}
    </section>}
    {!sorted.length && !conflicts.length && <p className="empty">No tasks yet. Add one to get started.</p>}
    {groups.map(([heading, tasks]) => tasks.length > 0 && <section key={heading}>
      <h2>{heading}</h2>
      <ul className="tasks">{tasks.map(task => { const { record, resolving, head } = task; return <li key={record.metadata.id}>
        <label className="check"><input type="checkbox" aria-label={(record.completedAt ? 'Reopen ' : 'Complete ') + record.title} checked={!!record.completedAt}
          disabled={resolving} onChange={event => { const completed = event.currentTarget.checked;
            void change(() => store.edit(record.metadata.id, { completed }, head)); }} /></label>
        <div className="task-label"><strong>{record.title}</strong><time dateTime={record.deadline}>{record.deadline}</time></div>
        <button disabled={resolving} aria-label={'Edit ' + record.title} onClick={() => setEditor(task)}>Edit</button>
        <button disabled={resolving} aria-label={'Delete ' + record.title} onClick={() => {
          if (window.confirm('Delete this task?')) void change(() => store.edit(record.metadata.id, { delete: true }, head));
        }}>Delete</button>
      </li>; })}</ul>
    </section>)}
  </section>;
}

function TaskForm({ task, cancel, save }: { task?: Todo; cancel: () => void; save: (name: string, deadline: string) => Promise<void> }) {
  const [name, setName] = useState(task?.title ?? '');
  const [deadline, setDeadline] = useState(task?.deadline ?? today());
  const [saving, setSaving] = useState(false);
  return <form className="card" onSubmit={async event => {
    event.preventDefault();
    setSaving(true);
    try { await save(name, deadline); } finally { setSaving(false); }
  }}>
    <h2>{task ? 'Edit task' : 'New task'}</h2>
    <label>Task title<input required autoFocus value={name} maxLength={200} onChange={event => setName(event.currentTarget.value)} /></label>
    <label>Deadline<input required type="date" min="0001-01-01" max="9999-12-31" value={deadline}
      onChange={event => setDeadline(event.currentTarget.value)} /></label>
    <div className="actions"><button className="primary" disabled={saving} type="submit">Save task</button>
      <button disabled={saving} type="button" onClick={cancel}>Cancel</button></div>
  </form>;
}

function Conflict({ task, change, store }: { task: TaskView; change: (write: () => Promise<void>) => Promise<boolean>; store: LifeStore }) {
  const ids = task.conflicts.map(operation => operation.operationId);
  return <article className="card">
    <p>Review the competing versions of this task.</p>
    {task.conflicts.map(operation => <div className="variant" key={operation.operationId}>
      <span>{operation.record.title} · {operation.record.deadline} · {operation.record.deleted ? 'Deleted' : operation.record.completedAt ? 'Completed' : 'Pending'}</span>
      <button disabled={task.record.deleted && !operation.record.deleted}
        onClick={() => void change(() => store.resolve(task.record.metadata.id, operation.operationId, ids))}>
        {operation.record.deleted ? 'Keep deletion' : 'Keep this version'}</button>
    </div>)}
  </article>;
}

const settingsPages = ['Account & Security', 'Synchronization', 'Backup & Recovery', 'Appearance', 'About LifeApp'] as const;

function Settings({ view, session, busy, online, status, onLogin, onBind, onLogout, onSync, onConflicts, onExport, expensePending, expenseConnected, workoutConnected, workoutStatus }: {
  view: LocalView; session: BrowserSession | null; busy: boolean; online: boolean; status: string; expensePending: number; expenseConnected: boolean; workoutConnected: boolean; workoutStatus: string;
  onLogin: (username: string, password: string, secondFactor?: string) => Promise<void>; onBind: () => Promise<void>;
  onLogout: () => Promise<void>; onSync: () => void; onConflicts: () => void; onExport: () => Promise<void>;
}) {
  const [page, setPage] = useState<typeof settingsPages[number] | null>(null);
  const [diagnostics, setDiagnostics] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [exportMessage, setExportMessage] = useState('');
  async function exportData() {
    setExporting(true);
    setExportMessage('');
    try {
      await onExport();
      setExportMessage('Verified browser export. Your browser handles the download.');
    } catch (failure) {
      setExportMessage(message(failure));
    } finally {
      setExporting(false);
    }
  }
  const match = session && sameScope(view.checkpoint?.scope ?? null, session.scope);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const fields = new FormData(form);
    const password = String(fields.get('password') ?? '');
    const input = form.elements.namedItem('password');
    if (input instanceof HTMLInputElement) input.value = '';
    const factor = form.elements.namedItem('secondFactor');
    if (factor instanceof HTMLInputElement) factor.value = '';
    await onLogin(String(fields.get('username') ?? ''), password, String(fields.get('secondFactor') ?? '').trim() || undefined);
  }
  return <section className="settings">
    {page && <button onClick={() => { setPage(null); setDiagnostics(false); }}>Back to Settings</button>}
    <h1>{page ?? 'Settings'}</h1>
    {!page && <>
      <p>{online ? status : 'Offline — changes saved on this device.'}</p>
      {view.pending > 0 && <p>{view.pending} To-do changes waiting to sync.</p>}
      <div className="settings-categories">{settingsPages.map(category =>
        <button key={category} onClick={() => setPage(category)}>{category}</button>)}</div>
    </>}
    {page === 'Account & Security' && <article className="card">
      {!session ? <form onSubmit={submit}>
        <label>Username<input required name="username" autoComplete="username" maxLength={254} /></label>
        <label>Password<input required name="password" type="password" autoComplete="current-password" maxLength={1024} /></label>
        <label>Authenticator or recovery code (if enabled)<input name="secondFactor" type="password"
          autoComplete="one-time-code" maxLength={43} /></label>
        <button className="primary" disabled={busy || !online}>Sign in</button>
      </form> : <>
        <p>Signed in to this server.</p>
        <p>Use your authenticator or recovery code when signing in.</p>
        <button disabled={!online || busy} onClick={() => void onLogout()}>Sign out</button>
      </>}
    </article>}
    {page === 'Synchronization' && <article className="card">
      <p>{online ? status : 'Offline — changes saved on this device.'}</p>
      <p>Automatic To-do sync {match ? 'enabled' : 'awaiting connection review'}.</p>
      <p>Sync now checks connected To-do, Expenses, Workouts and Habits.</p>
      {view.lastSync && <p>Last successful To-do sync <time dateTime={view.lastSync}>{new Date(view.lastSync).toLocaleString()}</time></p>}
      <p>{view.pending} pending To-do changes</p>
      {workoutStatus && <p>{workoutStatus}</p>}
      {expensePending > 0 && <p>{expensePending} expense changes waiting.</p>}
      {view.tasks.some(task => task.conflicts.length > 0) && <button onClick={onConflicts}>Review conflicts</button>}
      {!session ? <button onClick={() => setPage('Account & Security')}>Sign in again</button> : <>
        {(!view.checkpoint || !expenseConnected || !workoutConnected) && <button className="primary" disabled={busy || !online} onClick={() => void onBind()}>Connect synchronization</button>}
        {view.checkpoint && !match && <p role="alert">Reconnect to the original account or contact the server operator for recovery. Local changes were kept.</p>}
        <button disabled={!match || !online || busy} onClick={onSync}>Sync now</button>
      </>}
    </article>}
    {page === 'Backup & Recovery' && <article className="card">
      <ServerBackupStatus key={session?.scope.dataset} account={session?.scope.dataset ?? null} online={online} />
      <button disabled={exporting} onClick={() => void exportData()}>Download My Data</button>
      <p>Includes the To-do and workout data stored in this browser. Restore Android recovery copies from the Android app.</p>
      {exportMessage && <p role="status">{exportMessage}</p>}
    </article>}
    {page === 'Appearance' && <p>LifeApp uses a light paper theme.</p>}
    {page === 'About LifeApp' && <article className="card">
      <p>Web version {packageInfo.version}</p>
      <a href="https://github.com/mallikarjunreddyD/LifeApp">LifeApp support and source</a>
      <p><button onClick={() => setDiagnostics(!diagnostics)}>Advanced diagnostics</button></p>
      {diagnostics && <dl>
        <dt>Server origin</dt><dd>{location.origin}</dd>
        {session && <><dt>Dataset</dt><dd>{session.scope.dataset}</dd><dt>Recovery epoch</dt><dd>{session.scope.epoch}</dd></>}
        {view.checkpoint && <><dt>Checkpoint</dt><dd>{view.checkpoint.sequence}</dd></>}
      </dl>}
    </article>}
  </section>;
}

function ServerBackupStatus({ account, online }: { account: string | null; online: boolean }) {
  const [status, setStatus] = useState<Awaited<ReturnType<typeof api.backupStatus>> | null>(null);
  const [unavailable, setUnavailable] = useState(false);
  useEffect(() => {
    if (!account || !online) return;
    let active = true;
    void api.backupStatus().then(value => { if (active) setStatus(value); })
      .catch(() => { if (active) setUnavailable(true); });
    return () => { active = false; };
  }, [account, online]);
  if (!account || !online) return <p>Sign in online to check server backups.</p>;
  if (unavailable) return <p>Server backup status is unavailable.</p>;
  if (!status) return <p>Checking server backups…</p>;
  return <>
    {status.lastVerifiedAt ? <p>Last verified server Drive backup <time dateTime={status.lastVerifiedAt}>
      {new Date(status.lastVerifiedAt).toLocaleString()}</time></p> : <p>No verified server Drive backup reported.</p>}
    {status.state === 'failed' && <p>The latest server backup attempt needs attention.</p>}
  </>;
}

function ReadRecords({ kind, session, online }: { kind: 'exercises' | 'workouts'; session: BrowserSession | null; online: boolean }) {
  const [items, setItems] = useState<ReactNode[]>([]);
  const [next, setNext] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [problem, setProblem] = useState('');
  const generation = useRef(0);
  const load = useCallback(async (cursor?: string) => {
    if (!session || !online) return;
    const request = ++generation.current;
    setLoading(true);
    setProblem('');
    try {
      const current = await api.session();
      if (!sameScope(current.scope, session.scope)) throw new Error('The signed-in dataset changed.');
      const page = await api.read(kind, cursor);
      if (request !== generation.current) return;
      const list = page.items.map((item, index) => renderRecord(kind, item, (cursor ?? '') + ':' + index));
      setItems(previous => cursor ? [...previous, ...list] : list);
      setNext(page.next);
    } catch (failure) { if (request === generation.current) setProblem(message(failure)); }
    finally { if (request === generation.current) setLoading(false); }
  }, [kind, online, session]);
  useEffect(() => {
    setItems([]);
    setNext(null);
    void load();
    return () => { generation.current++; };
  }, [load]);
  return <section>
    <h1>{kind === 'exercises' ? 'Workouts' : 'History'}</h1>
    <p>Server records are separate from the workout log saved in this browser.</p>
    {!online || !session ? <p>Connect and sign in to the bound dataset to view these records.</p> : <>
      {loading && <p role="status">Loading records…</p>}
      {problem && <p role="alert">{problem}</p>}
      {!loading && !problem && !items.length && <p>No records found.</p>}
      <ul className="record-list">{items}</ul>
      {next && <button disabled={loading} onClick={() => void load(next)}>Load more</button>}
    </>}
  </section>;
}

function renderRecord(kind: 'exercises' | 'workouts', value: unknown, key: string) {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('Invalid server record.');
  const row = value as Record<string, unknown>;
  if (kind === 'exercises' && typeof row.name === 'string') {
    return <li key={key}><strong>{row.name}</strong><span>{row.archived ? 'Archived' :
      [row.category, row.equipment].filter(part => typeof part === 'string').join(' · ')}</span></li>;
  }
  if (kind === 'workouts' && typeof row.date === 'string' && Array.isArray(row.entries)) {
    const sets = row.entries.reduce((count: number, entry: { sets?: unknown[] }) => count + (entry?.sets?.length ?? 0), 0);
    return <li key={key}><strong>{row.date}</strong><span>{row.entries.length} exercises · {sets} sets</span></li>;
  }
  throw new Error('Invalid server record.');
}

function message(failure: unknown) { return failure instanceof Error ? failure.message : 'LifeApp could not complete that action.'; }
