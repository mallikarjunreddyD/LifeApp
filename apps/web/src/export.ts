import { expenseDecimal, type Expense, type ExpenseLabel } from './expense.ts';
import type { LifeStore } from './store.ts';

const encoder = new TextEncoder();
const limit = 64 * 1024 * 1024;
const crcTable = Uint32Array.from({ length: 256 }, (_, index) => {
  let value = index;
  for (let bit = 0; bit < 8; bit++) value = value & 1 ? (value >>> 1) ^ 0xedb88320 : value >>> 1;
  return value;
});

function crc32(bytes: Uint8Array) {
  let crc = 0xffffffff;
  for (const byte of bytes) crc = (crc >>> 8) ^ crcTable[(crc ^ byte) & 0xff]!;
  return (crc ^ 0xffffffff) >>> 0;
}

export async function sha256(bytes: Uint8Array<ArrayBuffer>) {
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)),
    byte => byte.toString(16).padStart(2, '0')).join('');
}

// The small, bounded export uses ZIP's stored format. No compression stream or ZIP64 is needed.
export function storedZip(files: Map<string, Uint8Array<ArrayBuffer>>): Blob {
  const local: ArrayBuffer[] = [];
  const central: ArrayBuffer[] = [];
  let offset = 0;
  let directorySize = 0;
  for (const [path, body] of files) {
    if (!/^[A-Za-z0-9_/-]+\.[a-z]+$/.test(path)) throw new Error('Invalid export path.');
    const name = encoder.encode(path);
    const crc = crc32(body);
    const header = new Uint8Array(30 + name.length);
    const directory = new Uint8Array(46 + name.length);
    const file = new DataView(header.buffer);
    const entry = new DataView(directory.buffer);
    file.setUint32(0, 0x04034b50, true);
    file.setUint16(4, 20, true);
    file.setUint16(12, 0x21, true);
    file.setUint32(14, crc, true);
    file.setUint32(18, body.length, true);
    file.setUint32(22, body.length, true);
    file.setUint16(26, name.length, true);
    header.set(name, 30);

    entry.setUint32(0, 0x02014b50, true);
    entry.setUint16(4, 20, true);
    entry.setUint16(6, 20, true);
    entry.setUint16(14, 0x21, true);
    entry.setUint32(16, crc, true);
    entry.setUint32(20, body.length, true);
    entry.setUint32(24, body.length, true);
    entry.setUint16(28, name.length, true);
    entry.setUint32(42, offset, true);
    directory.set(name, 46);
    offset += header.length + body.length;
    directorySize += directory.length;
    if (offset + directorySize + 22 > limit) throw new Error('This export exceeds 64 MiB.');
    local.push(header.buffer, body.buffer);
    central.push(directory.buffer);
  }
  const tail = new DataView(new ArrayBuffer(22));
  tail.setUint32(0, 0x06054b50, true);
  tail.setUint16(8, files.size, true);
  tail.setUint16(10, files.size, true);
  tail.setUint32(12, directorySize, true);
  tail.setUint32(16, offset, true);
  return new Blob([...local, ...central, tail.buffer], { type: 'application/zip' });
}

export function expenseCsv(items: readonly Expense[], labels: readonly ExpenseLabel[]): string {
  const names = new Map(labels.map(label => [label.metadata.id, label.name]));
  const cell = (text: string) => '"' + (/^[\t\r\n]|^\s*[=+@＝＋－＠-]/u.test(text) ? "'" : '') + text.replaceAll('"', '""') + '"';
  const rows = ['id,date,name,amount,currency,category,payment_method,remarks'];
  for (const item of items.filter(item => !item.deleted).sort((a, b) => a.metadata.id.localeCompare(b.metadata.id))) {
    rows.push([item.metadata.id, item.date, cell(item.name), expenseDecimal(BigInt(item.amountMinor)), item.currency,
      cell(names.get(item.categoryId) ?? ''), cell(names.get(item.paymentMethodId) ?? ''), cell(item.remarks)].join(','));
  }
  return rows.join('\r\n') + '\r\n';
}

export async function browserExport(store: LifeStore): Promise<Blob> {
  const { view, workouts, expenses, expensePending } = await store.exportSnapshot();
  const items = expenses.flatMap(record => record.kind === 'expense' ? [record.value] : []);
  const labels = expenses.flatMap(record => record.kind === 'label' ? [record.value] : []);
  const categories = labels.filter(label => label.kind === 'CATEGORY');
  const payments = labels.filter(label => label.kind === 'PAYMENT');
  const files = new Map<string, Uint8Array<ArrayBuffer>>();
  const pack = (path: string, value: unknown) => files.set(path, encoder.encode(JSON.stringify(value, null, 2)));
  pack('data/todos.json', view.tasks);
  pack('data/workouts.json', workouts);
  pack('expenses/expenses.json', items);
  pack('expenses/categories.json', categories);
  pack('expenses/payment-methods.json', payments);
  files.set('expenses/expenses.csv', encoder.encode(expenseCsv(items, labels)));
  files.set('README.txt', encoder.encode(
    'LifeApp export from this browser. The JSON files contain the local To-do view, available conflict variants, workout records, and the local expense ledger.\n' +
    'The export does not include unsynchronized Android records or online-only handwriting previews.\n' +
    'This ZIP is unencrypted and is not an Android recovery archive. Store it privately.\n',
  ));
  const manifest = {
    format: 'LifeAppMyData', version: 1, createdAt: new Date().toISOString(), source: 'browser-local',
    completeAcrossDevices: false, knownQueuedChanges: view.pending + expensePending,
    coverage: 'Browser-local To-do, workout and expense data. Online-only and other device records are unavailable.',
    recordCounts: { todos: view.tasks.length, workoutRecords: workouts.length, expenses: items.length,
      expenseCategories: categories.length, paymentMethods: payments.length },
    unavailable: ['Android journal, planner, habit and notebook records', 'Server-only records and backup files'],
    files: await Promise.all(Array.from(files, async ([path, bytes]) => ({ path, bytes: bytes.length, sha256: await sha256(bytes) }))),
  };
  pack('manifest.json', manifest);
  const zip = storedZip(files);
  let offset = 0;
  for (const [path, bytes] of files) {
    offset += 30 + encoder.encode(path).length;
    const readback = new Uint8Array(await zip.slice(offset, offset + bytes.length).arrayBuffer());
    if (await sha256(readback) !== await sha256(bytes)) throw new Error('Export verification failed.');
    offset += bytes.length;
  }
  return zip;
}

export function downloadExport(blob: Blob) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = 'LifeApp-MyData.zip';
  document.body.append(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 30_000);
}
