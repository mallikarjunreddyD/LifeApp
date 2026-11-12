import { useEffect, useRef, useState } from 'react';
import type { LifeStore } from './store.ts';
import { expenseDecimal, expenseIcons, expenseNameKey, formatExpensePaise, parseExpensePaise } from './expense.ts';
import type { Expense, ExpenseLabel, ExpenseRecord, Icon, LabelKind } from './expense.ts';
import { competingExpenseHeads, recordOf } from './expenseSync.ts';
import type { useExpenseSync } from './useExpenseSync.ts';
import { revise } from './workout.ts';
import { instantKey, today } from './protocol.ts';

import { ExpenseAnalysis } from './ExpenseAnalysis.tsx';

type Page = 'analysis' | 'history' | 'new' | 'CATEGORY' | 'PAYMENT' | Expense;

export function Expenses({ store, sync }: { store: LifeStore; sync: ReturnType<typeof useExpenseSync> }) {
  const [records, setRecords] = useState<ExpenseRecord[] | null>(null);
  const [page, setPage] = useState<Page>('history');
  const [month, setMonth] = useState(today().slice(0, 7));
  const [query, setQuery] = useState('');
  const [category, setCategory] = useState('');
  const [payment, setPayment] = useState('');
  const [reviewConflicts, setReviewConflicts] = useState(false);
  const [deleted, setDeleted] = useState(false);
  const [confirm, setConfirm] = useState<Expense | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const reveal = useRef<string | null>(null);
  useEffect(() => {
    if (page !== 'history' || !reveal.current) return;
    const card = document.getElementById('expense-' + reveal.current);
    if (card) {
      card.scrollIntoView({ block: 'nearest' });
      reveal.current = null;
    }
  }, [page, records]);

  useEffect(() => {
    let live = true;
    void store.seedExpenses().then(() => store.expenseRecords()).then(value => {
      if (live) setRecords(value);
    }).catch(() => { if (live) setError('Could not open expenses on this device.'); });
    return () => { live = false; };
  }, [store, sync.generation]);

  async function write(action: () => Promise<void>, done?: () => void) {
    if (busy) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await action();
      sync.committed();
      setRecords(await store.expenseRecords());
      done?.();
      setNotice('Saved on this device.');
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not save the expense.');
    } finally { setBusy(false); }
  }

  function navigate(next: Page) {
    setError('');
    setNotice('');
    setPage(next);
  }

  const labels = (records ?? []).flatMap(record => record.kind === 'label' ? [record.value] : []);
  const names = new Map(labels.map(label => [label.metadata.id, label]));
  const items = (records ?? []).flatMap(record => record.kind === 'expense' ? [record.value] : [])
    .sort((a, b) => b.date.localeCompare(a.date) || instantKey(b.metadata.createdAt).localeCompare(instantKey(a.metadata.createdAt)) ||
      a.metadata.id.localeCompare(b.metadata.id));
  const active = items.filter(item => !item.deleted);
  const period = active.filter(item => item.date.startsWith(month + '-'));
  const visible = deleted ? items.filter(item => item.deleted) : period.filter(item =>
    expenseNameKey(item.name).includes(expenseNameKey(query)) && (!category || item.categoryId === category) &&
    (!payment || item.paymentMethodId === payment));
  const saveLabel = (label: ExpenseLabel, expected: string | null, done?: () => void) =>
    void write(() => store.saveExpense({ kind: 'label', value: label }, expected), done);

  return <section className="expense-module">
    {page !== 'history' && <button disabled={busy} onClick={() => navigate('history')}>Back to Expenses</button>}
    <h1>{page === 'history' ? 'Expenses' : page === 'new' ? 'Add Expense' : page === 'CATEGORY' ? 'Manage Categories' :
      page === 'PAYMENT' ? 'Manage Payment Methods' : page === 'analysis' ? 'Spending Analysis' : 'Edit Expense'}</h1>
    {error && <p role="alert">{error}</p>}
    {notice && <p role="status">{notice}</p>}
    {!records && !error && <p role="status">Loading expenses…</p>}
    {records && (page === 'history' ? <>
      <article className="card expense-summary">
        <p>{month}</p>
        <strong>{formatExpensePaise(period.reduce((total, item) => total + BigInt(item.amountMinor), 0n))}</strong>
        <p>{period.length} {period.length === 1 ? 'transaction' : 'transactions'} · Saved on this device</p>
      </article>
      {sync.status && <p role="status">{sync.status}</p>}
      {sync.state ? <div className="actions">
        <button onClick={sync.syncNow}>Sync Expenses Now</button>
        {!!sync.state.pending.length && <span>{sync.state.pending.length} expense changes waiting.</span>}
        {!!competingExpenseHeads(sync.state).length && <button onClick={() => setReviewConflicts(!reviewConflicts)}>
          Review Expense Conflicts
        </button>}
      </div> : <button onClick={() => void sync.reviewConnection()}>Review Expense Connection</button>}
      {sync.review && <div role="alertdialog" aria-label="Connect Expenses" className="card">
        <p>{sync.review.local.filter(record => record.kind === 'expense').length} local expenses and {sync.review.count} server expense records and labels.
          Independent records will be kept, and competing versions can be reviewed.</p>
        <button onClick={() => void sync.bind()}>Connect Expenses</button>
        <button onClick={sync.dismissReview}>Cancel</button>
      </div>}
      {reviewConflicts && sync.state && <section>
        <h2>Expense conflicts</h2>
        {competingExpenseHeads(sync.state).map(heads => <article className="card" key={heads[0]!.record.metadata.id}>
          {heads.map(version => {
            const record = recordOf(version);
            return <p key={version.id}><button onClick={() => void sync.resolve(version.id, heads.map(head => head.id))}>
              Keep {record.value.name}{record.kind === 'expense'
                ? ` · ${formatExpensePaise(BigInt(record.value.amountMinor))} · ${record.value.date}${record.value.deleted ? ' · Deleted' : ''}`
                : record.value.archived ? ' · Archived' : ''}
            </button></p>;
          })}
        </article>)}
      </section>}
      <button className="primary" disabled={busy} onClick={() => navigate('new')}>Add Expense</button>
      <div className="actions expense-secondary">
        <button onClick={() => navigate('analysis')}>Spending Analysis</button>
        <button onClick={() => navigate('CATEGORY')}>Manage Categories</button>
        <button onClick={() => navigate('PAYMENT')}>Manage Payment Methods</button>
        <button onClick={() => setDeleted(!deleted)}>{deleted ? 'Show History' : 'Recently Deleted'}</button>
      </div>
      {!deleted && <details>
        <summary>Filter Expenses</summary>
        <label>Month<input type="month" value={month} onChange={event => {
          if (event.currentTarget.value) setMonth(event.currentTarget.value);
        }} /></label>
        <label>Search expense names<input value={query} onChange={event => setQuery(event.currentTarget.value)} /></label>
        <LabelFilter title="Category filter" value={category} labels={labels.filter(label => label.kind === 'CATEGORY')} change={setCategory} />
        <LabelFilter title="Payment method filter" value={payment} labels={labels.filter(label => label.kind === 'PAYMENT')} change={setPayment} />
      </details>}
      <h2>{deleted ? 'Recently Deleted' : 'Recent Expenses'}</h2>
      {!visible.length && <p>{deleted ? 'No deleted expenses.' : 'No expenses match this period or filter.'}</p>}
      <div className="expense-history">
        {visible.map(item => {
          const label = names.get(item.categoryId);
          return <article id={'expense-' + item.metadata.id} key={item.metadata.id} className="card expense-card">
            <div className="expense-card-heading">
              <h3><span aria-hidden="true" style={{ color: label ? '#' + label.color.toString(16).padStart(6, '0') : undefined }}>
                {label ? expenseIcons[label.icon] : '○'}</span> {item.name}</h3>
              <strong>{formatExpensePaise(BigInt(item.amountMinor))}</strong>
            </div>
            <p>{item.date} · {names.get(item.paymentMethodId)?.name}</p>
            <p>{label?.name}</p>
            {item.remarks && <details><summary>Remarks</summary><p>{item.remarks}</p></details>}
            <div className="actions">{item.deleted ?
              <button disabled={busy} onClick={() => void write(() => store.setExpenseDeleted(item.metadata.id, false, item.metadata.revision))}>Restore Expense</button> :
              <><button disabled={busy} onClick={() => navigate(item)}>Edit</button>
                <button disabled={busy} onClick={() => setConfirm(item)}>Delete</button></>}</div>
          </article>;
        })}
      </div>
      {confirm && <div role="alertdialog" aria-label="Delete Expense" className="card">
        <p>Move {confirm.name} · {formatExpensePaise(BigInt(confirm.amountMinor))} · {confirm.date} to Recently Deleted?</p>
        <button disabled={busy} onClick={() => void write(
          () => store.setExpenseDeleted(confirm.metadata.id, true, confirm.metadata.revision), () => setConfirm(null))}>Confirm Delete</button>
        <button disabled={busy} onClick={() => setConfirm(null)}>Cancel</button>
      </div>}
    </> : page === 'analysis' ? <ExpenseAnalysis items={items} labels={labels} initialMonth={month} /> : page === 'CATEGORY' || page === 'PAYMENT' ? <LabelManager kind={page} labels={labels} busy={busy} save={saveLabel} /> :
      <Editor expense={page === 'new' ? undefined : page} labels={labels} history={active} busy={busy} saveLabel={saveLabel}
        save={(build, expected) => void write(async () => {
          const value = build();
          await store.saveExpense({ kind: 'expense', value }, expected);
          setMonth(value.date.slice(0, 7));
          setQuery('');
          setCategory('');
          setPayment('');
          setDeleted(false);
          reveal.current = value.metadata.id;
          setPage('history');
        })} />)}
  </section>;
}

type SaveLabel = (label: ExpenseLabel, revision: string | null, done?: () => void) => void;

function Editor({ expense, labels, history, busy, saveLabel, save }: {
  expense?: Expense; labels: ExpenseLabel[]; history: Expense[]; busy: boolean; saveLabel: SaveLabel;
  save: (build: () => Expense, revision: string | null) => void;
}) {
  const recent = [...history].sort((a, b) => instantKey(b.metadata.updatedAt).localeCompare(instantKey(a.metadata.updatedAt)));
  const [amount, setAmount] = useState(expense ? expenseDecimal(BigInt(expense.amountMinor)) : '');
  const [name, setName] = useState(expense?.name ?? '');
  const [date, setDate] = useState(expense?.date ?? today());
  const [category, setCategory] = useState(expense?.categoryId ?? '');
  const [payment, setPayment] = useState(expense?.paymentMethodId ??
    (labels.some(label => label.metadata.id === recent[0]?.paymentMethodId && !label.archived) ? recent[0]!.paymentMethodId : ''));
  const [remarks, setRemarks] = useState(expense?.remarks ?? '');
  return <form className="editor" onSubmit={event => {
    event.preventDefault();
    save(() => ({ metadata: revise(expense?.metadata), date, name: name.trim(), amountMinor: parseExpensePaise(amount),
      currency: 'INR', categoryId: category, paymentMethodId: payment, remarks: remarks.trim(), deleted: false }),
    expense?.metadata.revision ?? null);
  }}>
    <label>Amount (₹)<input inputMode="decimal" required maxLength={20} value={amount} onChange={event => setAmount(event.currentTarget.value)} /></label>
    <label>Expense name<input required maxLength={120} value={name} onChange={event => setName(event.currentTarget.value)} /></label>
    <label>Date<input type="date" required max="9999-12-31" value={date} onChange={event => setDate(event.currentTarget.value)} /></label>
    <LabelPicker title="Category" kind="CATEGORY" labels={labels} recent={recent.map(item => item.categoryId)}
      value={category} choose={setCategory} busy={busy} save={saveLabel} />
    <LabelPicker title="Paid From" kind="PAYMENT" labels={labels} recent={recent.map(item => item.paymentMethodId)}
      value={payment} choose={setPayment} busy={busy} save={saveLabel} />
    <details open={expense?.remarks ? true : undefined}><summary>Remarks (optional)</summary>
      <label>Expense remarks<textarea maxLength={4000} value={remarks} onChange={event => setRemarks(event.currentTarget.value)} /></label>
    </details>
    <button className="primary" disabled={busy || !category || !payment}>Save Expense</button>
  </form>;
}

function LabelPicker({ title, kind, labels, recent, value, choose, busy, save }: {
  title: string; kind: LabelKind; labels: ExpenseLabel[]; recent: string[];
  value: string; choose: (id: string) => void; busy: boolean; save: SaveLabel;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const options = labels.filter(label => label.kind === kind);
  const rank = new Map([...new Set(recent)].map((id, index) => [id, index]));
  const matches = options.filter(label => !label.archived && expenseNameKey(label.name).includes(expenseNameKey(query)))
    .sort((a, b) => (rank.get(a.metadata.id) ?? Infinity) - (rank.get(b.metadata.id) ?? Infinity) || a.name.localeCompare(b.name));
  function select(id: string) { choose(id); setOpen(false); setQuery(''); }
  return <div>
    <button type="button" aria-expanded={open} onClick={() => setOpen(!open)}>
      {title} · {options.find(label => label.metadata.id === value)?.name ?? 'Choose'}
    </button>
    {open && <>
      <label>Search {title}<input maxLength={120} value={query} onChange={event => setQuery(event.currentTarget.value)} /></label>
      <div className="actions">{matches.slice(0, 6).map(label =>
        <button key={label.metadata.id} type="button" onClick={() => select(label.metadata.id)}>{label.name}</button>)}</div>
      {query.trim() && !options.some(label => expenseNameKey(label.name) === expenseNameKey(query)) &&
        <button type="button" disabled={busy} onClick={() => {
          const label = makeLabel(kind, query);
          save(label, null, () => select(label.metadata.id));
        }}>Create "{query.trim()}"</button>}
    </>}
  </div>;
}

function LabelFilter({ title, value, labels, change }: {
  title: string; value: string; labels: ExpenseLabel[]; change: (value: string) => void;
}) {
  return <label>{title}<select value={value} onChange={event => change(event.currentTarget.value)}>
    <option value="">All</option>{labels.map(label => <option key={label.metadata.id} value={label.metadata.id}>{label.name}</option>)}
  </select></label>;
}

function makeLabel(kind: LabelKind, name: string): ExpenseLabel {
  return { metadata: revise(), kind, name: name.trim(), icon: kind === 'CATEGORY' ? 'OTHER' : 'PAYMENT', color: 0x527062, archived: false };
}

function LabelManager({ kind, labels, busy, save }: { kind: LabelKind; labels: ExpenseLabel[]; busy: boolean; save: SaveLabel }) {
  const [editing, setEditing] = useState<ExpenseLabel | null>(null);
  const [name, setName] = useState('');
  const [icon, setIcon] = useState<Icon>(kind === 'CATEGORY' ? 'OTHER' : 'PAYMENT');
  const [color, setColor] = useState('#527062');
  const activeNames = labels.filter(label => label.kind === kind && !label.archived).map(label => expenseNameKey(label.name));
  return <>
    {new Set(activeNames).size < activeNames.length &&
      <p>Some names were created on different devices. Rename or archive one to distinguish them. Historical expenses keep their labels.</p>}
    <form className="editor" onSubmit={event => {
      event.preventDefault();
      save({ ...makeLabel(kind, name), metadata: revise(editing?.metadata), icon, color: Number.parseInt(color.slice(1), 16),
        archived: editing?.archived ?? false }, editing?.metadata.revision ?? null, () => { setEditing(null); setName(''); });
    }}>
      <label>Name<input required maxLength={120} value={name} onChange={event => setName(event.currentTarget.value)} /></label>
      {kind === 'CATEGORY' && <>
        <label>Icon<select value={icon} onChange={event => setIcon(event.currentTarget.value as Icon)}>
          {Object.entries(expenseIcons).map(([key, symbol]) => <option key={key} value={key}>{symbol} {key.toLowerCase()}</option>)}
        </select></label>
        <label>Color<input type="color" value={color} onChange={event => setColor(event.currentTarget.value)} /></label>
      </>}
      <button disabled={busy}>{editing ? 'Save Changes' : 'Add'}</button>
      {editing && <button type="button" onClick={() => { setEditing(null); setName(''); }}>Cancel Edit</button>}
    </form>
    {labels.filter(label => label.kind === kind).sort((a, b) => a.name.localeCompare(b.name)).map(label =>
      <article key={label.metadata.id} className="card">
        <h3>{label.name}{label.archived ? ' (archived)' : ''}</h3>
        <div className="actions">
          <button onClick={() => {
            setEditing(label); setName(label.name); setIcon(label.icon); setColor('#' + label.color.toString(16).padStart(6, '0'));
          }}>Edit</button>
          <button disabled={busy} onClick={() => save({ ...label, metadata: revise(label.metadata), archived: !label.archived },
            label.metadata.revision)}>{label.archived ? 'Restore' : 'Archive'}</button>
        </div>
      </article>)}
  </>;
}
