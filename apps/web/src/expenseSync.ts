import { checkExpenseRecord } from './expense.ts';
import type { Expense, ExpenseLabel, ExpenseRecord, Icon, LabelKind } from './expense.ts';
import { calendarDate, instant, instantKey, object, sequence, uuid } from './protocol.ts';
import type { Metadata, Scope } from './protocol.ts';

export type ExpenseMutation = 'PUT' | 'DELETE' | 'RESTORE' | 'RESOLVE';
export type ExpenseOperation = { version: 1; id: string; parents: string[]; mutation: ExpenseMutation; kind: ExpenseRecord['kind']; record: Expense | ExpenseLabel };
export type ExpenseCommit = { sequence: string; operation: ExpenseOperation };
export type ExpenseSyncState = {
  scope: Scope; cursor: string; heads: ExpenseOperation[]; pending: ExpenseOperation[]; acknowledged: string[];
};

export function recordOf(operation: ExpenseOperation): ExpenseRecord {
  return operation.kind === 'expense' ? { kind: 'expense', value: operation.record as Expense }
    : { kind: 'label', value: operation.record as ExpenseLabel };
}

export function deleted(operation: ExpenseOperation) {
  const record = recordOf(operation);
  return record.kind === 'expense' && record.value.deleted;
}

function decodedRecord(kind: unknown, value: unknown): ExpenseRecord {
  const fields = object(value, kind === 'expense'
    ? ['metadata', 'date', 'name', 'amountMinor', 'currency', 'categoryId', 'paymentMethodId', 'remarks', 'deleted']
    : ['metadata', 'kind', 'name', 'icon', 'color', 'archived']);
  const meta = object(fields.metadata, ['id', 'createdAt', 'updatedAt', 'schemaVersion', 'revision']);
  const metadata: Metadata = {
    id: uuid(meta.id), createdAt: instant(meta.createdAt), updatedAt: instant(meta.updatedAt), revision: sequence(meta.revision), schemaVersion: 1,
  };
  if (meta.schemaVersion !== 1 || metadata.revision === '0' || typeof fields.name !== 'string' ||
      instantKey(metadata.createdAt) > instantKey(metadata.updatedAt)) throw new Error('Invalid expense metadata.');
  let record: ExpenseRecord;
  if (kind === 'expense') {
    if (fields.currency !== 'INR' || typeof fields.amountMinor !== 'string' ||
        typeof fields.remarks !== 'string' || typeof fields.deleted !== 'boolean') throw new Error('Invalid expense.');
    record = { kind, value: { metadata, date: calendarDate(fields.date), name: fields.name, amountMinor: fields.amountMinor,
      currency: fields.currency, categoryId: uuid(fields.categoryId), paymentMethodId: uuid(fields.paymentMethodId),
      remarks: fields.remarks, deleted: fields.deleted } };
  } else {
    if (kind !== 'label' || typeof fields.color !== 'number' || typeof fields.icon !== 'string' ||
        typeof fields.archived !== 'boolean') throw new Error('Invalid expense label.');
    record = { kind, value: { metadata, kind: fields.kind as LabelKind, name: fields.name, icon: fields.icon as Icon,
      color: fields.color, archived: fields.archived } };
  }
  checkExpenseRecord(record);
  return record;
}

export function decodeExpenseCommit(value: unknown): ExpenseCommit {
  const commit = object(value, ['sequence', 'operation']);
  const op = object(commit.operation, ['version', 'id', 'parents', 'mutation', 'kind', 'record']);
  const id = uuid(op.id);
  const mutation = op.mutation;
  if (op.version !== 1 || !Array.isArray(op.parents) || op.parents.length > 32 ||
      (mutation !== 'PUT' && mutation !== 'DELETE' && mutation !== 'RESTORE' && mutation !== 'RESOLVE')) {
    throw new Error('Invalid expense operation.');
  }
  const parents = op.parents.map(uuid).sort();
  if (parents.includes(id) || new Set(parents).size !== parents.length) throw new Error('Invalid expense parents.');
  const record = decodedRecord(op.kind, op.record);
  return { sequence: sequence(commit.sequence), operation: { version: 1, id, parents, mutation, kind: record.kind, record: record.value } };
}

export function sameOperation(a: ExpenseOperation, b: ExpenseOperation): boolean {
  const normalize = (operation: ExpenseOperation) =>
    decodeExpenseCommit({ sequence: '1', operation }).operation;
  return JSON.stringify(normalize(a)) === JSON.stringify(normalize(b));
}

export function applyExpenseOperation(heads: ExpenseOperation[], operation: ExpenseOperation): ExpenseOperation[] {
  if (heads.some(head => head.id === operation.id)) return heads;
  return [...heads.filter(head => !operation.parents.includes(head.id)), operation];
}

export function effectiveExpenseHeads(state: ExpenseSyncState): ExpenseOperation[][] {
  const effective = new Map(state.heads.map(head => [head.id, head]));
  for (const operation of state.pending) {
    if (effective.has(operation.id)) continue;
    for (const parent of operation.parents) effective.delete(parent);
    effective.set(operation.id, operation);
  }
  const grouped = new Map<string, ExpenseOperation[]>();
  for (const version of effective.values()) {
    const key = version.record.metadata.id;
    const versions = grouped.get(key) ?? [];
    versions.push(version);
    grouped.set(key, versions);
  }
  return [...grouped.values()];
}

export function competingExpenseHeads(state: ExpenseSyncState): ExpenseOperation[][] {
  return effectiveExpenseHeads(state).filter(versions =>
    new Set(versions.map(version => JSON.stringify(decodedRecord(version.kind, version.record)))).size > 1);
}

export function newExpenseOperation(record: ExpenseRecord, mutation: ExpenseMutation, parents: string[] = []): ExpenseOperation {
  return { version: 1, id: crypto.randomUUID(), parents: [...parents].sort(), mutation, kind: record.kind, record: record.value };
}
