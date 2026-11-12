import type { Expense, ExpenseLabel } from './expense.ts';

export function expenseSpending(expenses: readonly Expense[], labels: readonly ExpenseLabel[], period: string) {
  const totals = new Map<string, bigint>();
  for (const expense of expenses) {
    if (expense.deleted || !expense.date.startsWith(period + '-')) continue;
    totals.set(expense.categoryId, (totals.get(expense.categoryId) ?? 0n) + BigInt(expense.amountMinor));
  }
  const categories = labels.flatMap(category => {
    const amount = totals.get(category.metadata.id);
    return amount === undefined ? [] : [{ category, amount }];
  }).sort((a, b) => a.amount === b.amount ? a.category.metadata.id.localeCompare(b.category.metadata.id)
    : a.amount > b.amount ? -1 : 1);
  return { categories, total: categories.reduce((sum, part) => sum + part.amount, 0n) };
}
