import { calendarDate } from './protocol.ts';
import type { Metadata } from './protocol.ts';

export const maxExpensePaise = 99_999_999_999n;
export const expenseNameKey = (name: string) => name.trim().replace(/[\s\p{Z}]+/gu, ' ').toLowerCase();
export const expenseIcons = {
  BASKET: '▤', FOOD: '◇', TRANSPORT: '↗', SHOPPING: '▣', BILL: '≡', HEALTH: '+',
  TRAVEL: '✦', EDUCATION: '▱', ENTERTAINMENT: '♪', PAYMENT: '▰', OTHER: '○',
} as const;
export type Icon = keyof typeof expenseIcons;
export type LabelKind = 'CATEGORY' | 'PAYMENT';
export type ExpenseLabel = { metadata: Metadata; kind: LabelKind; name: string; icon: Icon; color: number; archived: boolean };
export type Expense = {
  metadata: Metadata; date: string; name: string; amountMinor: string; currency: 'INR';
  categoryId: string; paymentMethodId: string; remarks: string; deleted: boolean;
};
export type ExpenseRecord = { kind: 'expense'; value: Expense } | { kind: 'label'; value: ExpenseLabel };

export function parseExpensePaise(input: string): string {
  const text = input.trim();
  if (text.length > 20 || !/^(?:[0-9]+|[0-9]{1,2}(?:,[0-9]{2})*,[0-9]{3}|[0-9]{1,3},[0-9]{3})(?:\.[0-9]{1,2})?$/.test(text)) {
    throw new Error('Enter a rupee amount with up to two decimal places.');
  }
  const [whole, fraction = ''] = text.replaceAll(',', '').split('.');
  const value = BigInt(whole!) * 100n + BigInt(fraction.padEnd(2, '0'));
  if (value <= 0n || value > maxExpensePaise) throw new Error('Enter a positive amount within the limit.');
  return value.toString();
}

export function expenseDecimal(minor: bigint): string {
  return `${minor / 100n}.${(minor % 100n).toString().padStart(2, '0')}`;
}

export function formatExpensePaise(minor: bigint): string {
  const [whole, fraction] = expenseDecimal(minor).split('.');
  const suffix = whole!.slice(-3);
  const leading = whole!.slice(0, -3).replace(/\B(?=(?:\d{2})+$)/g, ',');
  return `₹${leading ? leading + ',' : ''}${suffix}.${fraction}`;
}

export function expenseDefaults(): ExpenseLabel[] {
  const categories = ['Groceries', 'Eating Out', 'Transport', 'Shopping', 'Bills & Utilities', 'Health',
    'Travel', 'Education', 'Entertainment', 'Other'];
  const payments = ['Google Pay', 'PhonePe', 'Cash', 'Credit Card', 'Debit Card', 'Bank Transfer', 'Other'];
  const icons = (Object.keys(expenseIcons) as Icon[]).filter(icon => icon !== 'PAYMENT');
  const colors = [0x527062, 0x97583b, 0x415f83, 0x795683, 0x79613d, 0x965761, 0x3f7474, 0x58648a, 0x88603f, 0x636860];
  const time = '2026-01-01T00:00:00Z';
  return [...categories, ...payments].map((name, index) => ({
    metadata: { id: '00000000-0000-480a-8000-' + String(index + 1).padStart(12, '0'),
      createdAt: time, updatedAt: time, schemaVersion: 1, revision: '1' },
    kind: index < categories.length ? 'CATEGORY' : 'PAYMENT',
    name, icon: index < categories.length ? icons[index]! : 'PAYMENT',
    color: colors[index % colors.length]!, archived: false,
  }));
}

export function checkExpenseRecord(record: ExpenseRecord) {
  const { name, metadata } = record.value;
  if (metadata.schemaVersion !== 1 || !name || name !== name.trim() || name.length > 120) {
    throw new Error('Enter a name of up to 120 characters.');
  }
  if (record.kind === 'label') {
    const { kind, color, icon } = record.value;
    if ((kind !== 'CATEGORY' && kind !== 'PAYMENT') || !Object.hasOwn(expenseIcons, icon) ||
      !Number.isInteger(color) || color < 0 || color > 0xffffff) throw new Error('Invalid expense label.');
  } else {
    const { date, amountMinor, currency, remarks } = record.value;
    calendarDate(date);
    if (date.startsWith('0000-')) throw new Error('Enter a valid expense date.');
    if (currency !== 'INR' || typeof amountMinor !== 'string' || !/^[1-9][0-9]{0,10}$/.test(amountMinor) ||
        BigInt(amountMinor) > maxExpensePaise) throw new Error('Enter a positive INR amount within the limit.');
    if (remarks.length > 4000) throw new Error('Remarks may contain up to 4,000 characters.');
  }
}
