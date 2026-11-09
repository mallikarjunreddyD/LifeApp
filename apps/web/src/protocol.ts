export interface Scope { dataset: string; epoch: string }
export interface Checkpoint { scope: Scope; sequence: string }
export interface Metadata { id: string; createdAt: string; updatedAt: string; schemaVersion: 1; revision: string }
export interface Todo { metadata: Metadata; title: string; deadline: string; completedAt: string | null; deleted: boolean }
export interface Operation {
  format: 'LifeAppOperation';
  protocolVersion: 1;
  operationId: string;
  deviceId: string;
  sequence: string;
  payloadVersion: 1;
  recordType: 'todo';
  recordId: string;
  type: 'put' | 'delete';
  parents: string[];
  record: Todo;
}
export interface Commit { sequence: string; operation: Operation }
export interface Page { scope: Scope; entries: Commit[]; hasMore: boolean }
export interface Receipt { scope: Scope; commit: Commit }

const MAX_SEQUENCE = 9_223_372_036_854_775_807n;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function invalid(): never { throw new Error('The server response does not match LifeApp protocol v1.'); }

export function object(value: unknown, fields: string[]): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return invalid();
  const result = value as Record<string, unknown>;
  if (Object.keys(result).length !== fields.length || !fields.every(field => Object.hasOwn(result, field))) return invalid();
  return result;
}

export function uuid(value: unknown): string {
  return typeof value === 'string' && uuidPattern.test(value) ? value : invalid();
}

export function sequence(value: unknown, positive = true): string {
  if (typeof value !== 'string' || !/^(0|[1-9][0-9]{0,18})$/.test(value)) return invalid();
  const number = BigInt(value);
  if ((positive && number === 0n) || number > MAX_SEQUENCE) return invalid();
  return value;
}

export function nextSequence(value: string): string {
  const next = BigInt(value) + 1n;
  if (next > MAX_SEQUENCE) throw new Error('The operation counter is exhausted.');
  return next.toString();
}

export function calendarDate(value: unknown): string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new Error('Enter a valid calendar date.');
  const year = Number(value.slice(0, 4));
  const month = Number(value.slice(5, 7));
  const day = Number(value.slice(8, 10));
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (year < 1 || day < 1 || day > (days[month - 1] ?? 0)) throw new Error('Enter a valid calendar date.');
  return value;
}

export function today(date = new Date()): string {
  return [date.getFullYear().toString().padStart(4, '0'), (date.getMonth() + 1).toString().padStart(2, '0'),
    date.getDate().toString().padStart(2, '0')].join('-');
}

export function title(value: unknown): string {
  if (typeof value !== 'string' || value.trim().length < 1 || value.trim().length > 200) {
    throw new Error('Enter a task title of 1 to 200 characters.');
  }
  return value.trim();
}

export function instant(value: unknown): string {
  if (typeof value !== 'string' ||
      !/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.(?:\d{3}|\d{6}|\d{9}))?Z$/.test(value)) return invalid();
  calendarDate(value.slice(0, 10));
  return value;
}

export function instantKey(value: string): string {
  const [base, fraction = ''] = value.slice(0, -1).split('.');
  return base + '.' + fraction.padEnd(9, '0');
}

export function now(): string {
  return new Date().toISOString().replace(/\.000Z$/, 'Z');
}

export function scopeOf(value: unknown): Scope {
  const scope = object(value, ['dataset', 'epoch']);
  return { dataset: uuid(scope.dataset), epoch: uuid(scope.epoch) };
}

export function sameScope(a: Scope | null, b: Scope): boolean {
  return a !== null && a.dataset === b.dataset && a.epoch === b.epoch;
}

export function decodeOperation(value: unknown): Operation {
  const op = object(value, ['format', 'protocolVersion', 'operationId', 'deviceId', 'sequence', 'payloadVersion',
    'recordType', 'recordId', 'type', 'parents', 'record']);
  if (op.format !== 'LifeAppOperation' || op.protocolVersion !== 1 || op.payloadVersion !== 1 || op.recordType !== 'todo') return invalid();
  const operationId = uuid(op.operationId);
  const deviceId = uuid(op.deviceId);
  const number = sequence(op.sequence);
  const recordId = uuid(op.recordId);
  if (!Array.isArray(op.parents) || op.parents.length > 128) return invalid();
  const parents = op.parents.map(uuid).sort();
  if (parents.includes(operationId) || new Set(parents).size !== parents.length) return invalid();
  const record = object(op.record, ['metadata', 'title', 'deadline', 'completedAt', 'deleted']);
  const meta = object(record.metadata, ['id', 'createdAt', 'updatedAt', 'schemaVersion', 'revision']);
  const createdAt = instant(meta.createdAt);
  const updatedAt = instant(meta.updatedAt);
  const completedAt = record.completedAt === null ? null : instant(record.completedAt);
  if (meta.id !== recordId || meta.schemaVersion !== 1 || typeof record.deleted !== 'boolean' ||
      op.type !== (record.deleted ? 'delete' : 'put') || instantKey(updatedAt) < instantKey(createdAt) ||
      (completedAt !== null && (instantKey(completedAt) < instantKey(createdAt) || instantKey(completedAt) > instantKey(updatedAt)))) return invalid();
  const name = title(record.title);
  if (name !== record.title) return invalid();
  return { format: 'LifeAppOperation', protocolVersion: 1, operationId, deviceId, sequence: number, payloadVersion: 1,
    recordType: 'todo', recordId, type: record.deleted ? 'delete' : 'put', parents,
    record: { metadata: { id: recordId, createdAt, updatedAt, schemaVersion: 1, revision: sequence(meta.revision) },
      title: name, deadline: calendarDate(record.deadline), completedAt, deleted: record.deleted } };
}

function commitOf(value: unknown): Commit {
  const commit = object(value, ['sequence', 'operation']);
  return { sequence: sequence(commit.sequence), operation: decodeOperation(commit.operation) };
}

export function pageOf(value: unknown): Page {
  const page = object(value, ['scope', 'entries', 'hasMore']);
  if (!Array.isArray(page.entries) || page.entries.length > 100 || typeof page.hasMore !== 'boolean' ||
      (page.hasMore && page.entries.length === 0)) return invalid();
  return { scope: scopeOf(page.scope), entries: page.entries.map(commitOf), hasMore: page.hasMore };
}

export function receiptOf(value: unknown): Receipt {
  const receipt = object(value, ['scope', 'commit']);
  return { scope: scopeOf(receipt.scope), commit: commitOf(receipt.commit) };
}

export function advance(from: Checkpoint, page: Page): Checkpoint {
  if (!sameScope(from.scope, page.scope) || page.entries.length > 100 || (page.hasMore && !page.entries.length)) return invalid();
  let cursor = from.sequence;
  for (const entry of page.entries) {
    if (entry.sequence !== nextSequence(cursor)) return invalid();
    cursor = entry.sequence;
  }
  return { scope: from.scope, sequence: cursor };
}

export function sameOperation(a: Operation, b: Operation): boolean {
  return JSON.stringify(decodeOperation(a)) === JSON.stringify(decodeOperation(b));
}
