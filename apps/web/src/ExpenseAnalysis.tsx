import { useMemo, useState } from 'react';
import { formatExpensePaise, type Expense, type ExpenseLabel } from './expense.ts';
import { expenseSpending } from './expenseAnalytics.ts';

export function ExpenseAnalysis({ items, labels, initialMonth }: {
  items: Expense[]; labels: ExpenseLabel[]; initialMonth: string;
}) {
  const [yearly, setYearly] = useState(false);
  const [month, setMonth] = useState(initialMonth);
  const [year, setYear] = useState(initialMonth.slice(0, 4));
  const period = yearly ? year : month;
  const valid = (yearly ? /^\d{4}$/ : /^\d{4}-(0[1-9]|1[0-2])$/).test(period) && !period.startsWith('0000');
  const result = useMemo(() => valid ? expenseSpending(items, labels, period) : null, [items, labels, period, valid]);
  let running = 0n;
  const slices = result?.categories.map(({ category, amount }) => {
    const start = running;
    running += amount;
    const color = '#' + category.color.toString(16).padStart(6, '0');
    return color + ' ' + Number(start * 1_000_000n / result.total) / 10_000 + '% ' +
      Number(running * 1_000_000n / result.total) / 10_000 + '%';
  });
  return <section className="card">
    <div className="actions">
      <button aria-pressed={!yearly} onClick={() => setYearly(false)}>Monthly</button>
      <button aria-pressed={yearly} onClick={() => setYearly(true)}>Yearly</button>
    </div>
    {yearly ? <label>Year (YYYY)<input inputMode="numeric" maxLength={4} value={year} onChange={e => setYear(e.currentTarget.value)} /></label>
      : <label>Month (YYYY-MM)<input type="month" value={month} onChange={e => setMonth(e.currentTarget.value)} /></label>}
    {result ? <>
      <h2>Total expenditure</h2>
      <p><strong>{formatExpensePaise(result.total)}</strong></p>
      {slices?.length ? <>
        <div aria-hidden="true" className="expense-pie" style={{ background: 'conic-gradient(' + slices.join(', ') + ')' }} />
        <ul>{result.categories.map(({ category, amount }) =>
          <li key={category.metadata.id}>{category.name} · {formatExpensePaise(amount)}</li>)}</ul>
      </> : <p>No expenses in this period.</p>}
    </> : <p>Enter a valid {yearly ? 'year' : 'month'}.</p>}
  </section>;
}
