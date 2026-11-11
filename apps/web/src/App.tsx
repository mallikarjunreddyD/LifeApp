import { useCallback, useEffect, useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { ApiError, api, synchronize } from './api.ts';
import type { BrowserSession } from './api.ts';
import { sameScope, today } from './protocol.ts';
import type { Todo } from './protocol.ts';
import type { LifeStore, LocalView, TaskView } from './store.ts';
import { EditableRecords } from './EditableRecords.tsx';
import { Journal } from './Journal.tsx';
import { Workouts } from './Workouts.tsx';
import { useWorkerUpdate } from './worker.ts';

type Tab = 'To-do' | 'Workouts' | 'History' | 'Journal' | 'Settings';
const tabs: Tab[] = ['To-do', 'Workouts', 'History', 'Journal', 'Settings'];
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
  const channel = useRef<BroadcastChannel | null>(null);
  const worker = useWorkerUpdate();
  const scope = view.checkpoint?.scope ?? null;
  const matches = session !== null && sameScope(scope, session.scope);

  const refresh = useCallback(async () => setView(await store.view()), [store]);

  const sync = useCallback(async (current: BrowserSession) => {
    if (running.current) return;
    running.current = true;
    setBusy(true);
    setSynced(false);
    setError('');
    setNotice('');
    try {
      const verified = await api.session();
      setSession(verified);
      if (!sameScope(current.scope, verified.scope)) {
        throw new Error('The signed-in dataset or recovery epoch changed. Local changes were kept.');
      }
      const complete = await synchronize(store, verified);
      setSynced(complete);
      setNotice(complete ? 'Synchronization complete.' : 'More changes are available. Use Sync now to continue.');
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) setSession(null);
      setError(message(failure));
    } finally {
      running.current = false;
      setBusy(false);
      try { await refresh(); } catch (failure) { setError(message(failure)); }
      channel.current?.postMessage('refresh');
    }
  }, [refresh, store]);

  useEffect(() => {
    let active = true;
    async function hydrate() {
      try {
        const local = await store.view();
        if (!active) return;
        setView(local);
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
    window.addEventListener('online', network);
    window.addEventListener('offline', network);
    return () => {
      active = false;
      bus?.close();
      channel.current = null;
      window.removeEventListener('online', network);
      window.removeEventListener('offline', network);
    };
  }, [refresh, store, sync]);

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
      setSynced(false);
      if (sameScope(scope, current.scope)) await sync(current);
    } catch (failure) { setError(message(failure)); }
    finally { setBusy(false); }
  }

  async function bind() {
    if (!session || !window.confirm('Bind this browser to the displayed dataset and recovery epoch? Queued tasks will upload to this account.')) return;
    try {
      await store.bind(session.scope);
      await refresh();
      await sync(session);
    } catch (failure) { setError(message(failure)); }
  }

  async function logout() {
    if (!session) return;
    setBusy(true);
    try {
      await api.logout(session);
      setSession(null);
      setSynced(false);
      setNotice('Signed out. Local tasks and queued changes are still on this browser.');
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) setSession(null);
      else setError(message(failure));
    } finally { setBusy(false); }
  }

  const status = !online ? 'Offline' : busy ? 'Working…' : view.pending > 0 ? 'Pending sync' :
    view.tasks.some(task => task.conflicts.length > 0) ? 'Conflict needs review' :
    !session ? 'Signed out' : !matches ? 'Dataset confirmation needed' : synced ? 'Synced' : 'Ready to sync';

  return <div className="shell">
    <header className="masthead">
      <div><p className="brand">LifeApp</p><p className="subtitle">Personal journal</p></div>
      <div className="sync-status" aria-live="polite"><strong>{status}</strong><span>{view.pending} pending</span></div>
    </header>
    {worker && <aside className="banner">An app update is ready. <button onClick={worker}>Reload app</button></aside>}
    {(error || notice) && <div className={error ? 'message error' : 'message'} role={error ? 'alert' : 'status'}>{error || notice}</div>}
    <main>
      {tab === 'To-do' && (loaded ? <Todos view={view} change={change} store={store} /> : <p role="status">Loading local tasks…</p>)}
      {tab === 'Settings' && <Settings view={view} session={session} busy={busy} online={online}
        onLogin={login} onBind={bind} onLogout={logout} onSync={() => session && void sync(session)} />}
      {(tab === 'Workouts' || tab === 'History') && <>
        <Workouts key={tab} store={store} historyOnly={tab === 'History'} />
        {matches && <details><summary>Read-only server records</summary><ReadRecords key={tab}
          kind={tab === 'Workouts' ? 'exercises' : 'workouts'} session={session} online={online} /></details>}
      </>}
      {tab === 'Journal' && <section><h1>Journal</h1>
        {online && session ? <div key={session.scope.dataset + '/' + session.scope.epoch}>
          <Journal scope={session.scope} />
          <EditableRecords scope={session.scope} />
        </div> : <p>{online ? 'Sign in to view committed journal pages.' : 'Connect to view committed journal pages.'}</p>}
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

function Settings({ view, session, busy, online, onLogin, onBind, onLogout, onSync }: {
  view: LocalView; session: BrowserSession | null; busy: boolean; online: boolean;
  onLogin: (username: string, password: string, secondFactor?: string) => Promise<void>; onBind: () => Promise<void>;
  onLogout: () => Promise<void>; onSync: () => void;
}) {
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
  return <section>
    <h1>Settings</h1>
    <article className="card"><h2>Server synchronization</h2>
      <p>Offline edits stay in this browser. Keep important records backed up on another device.</p>
      {view.lastSync && <p>Last successful sync <time dateTime={view.lastSync}>{new Date(view.lastSync).toLocaleString()}</time></p>}
      {!session ? <form onSubmit={submit}>
        <label>Username<input required name="username" autoComplete="username" maxLength={254} /></label>
        <label>Password<input required name="password" type="password" autoComplete="current-password" maxLength={1024} /></label>
        <label>Authenticator or recovery code (if enabled)<input name="secondFactor" type="password"
          autoComplete="one-time-code" maxLength={43} /></label>
        <button className="primary" disabled={busy || !online}>Sign in</button>
      </form> : <>
        <p>Signed in to this server.</p>
        <dl><dt>Dataset</dt><dd>{session.scope.dataset}</dd><dt>Recovery epoch</dt><dd>{session.scope.epoch}</dd></dl>
        {!view.checkpoint && <button className="primary" disabled={busy || !online} onClick={() => void onBind()}>Use this dataset</button>}
        {view.checkpoint && !match && <p role="alert">This browser is bound to a different dataset or recovery epoch. Local changes are preserved.</p>}
        <div className="actions"><button disabled={!match || !online || busy} onClick={onSync}>Sync now</button>
          <button disabled={!online || busy} onClick={() => void onLogout()}>Sign out</button></div>
      </>}
    </article>
  </section>;
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
