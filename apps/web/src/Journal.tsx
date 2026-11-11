import { useEffect, useRef, useState } from 'react';
import { journalList } from './api.ts';
import { downloadStroke, drawStroke, strokeSummary } from './journalInk.ts';
import { calendarDate, object, uuid } from './protocol.ts';
import type { Scope } from './protocol.ts';

function entryOf(value: unknown) {
  const entry = object(value, ['metadata', 'date', 'pages']);
  if (!Array.isArray(entry.pages) || entry.pages.length !== 1) throw new Error('Invalid journal entry.');
  const page = object(entry.pages[0], ['metadata', 'entryId', 'order', 'width', 'height', 'paper', 'archived']);
  const metadata = object(page.metadata, ['id', 'createdAt', 'updatedAt', 'schemaVersion', 'revision']);
  if (page.archived !== false || typeof page.width !== 'number' || !Number.isFinite(page.width) || page.width <= 0 ||
      typeof page.height !== 'number' || !Number.isFinite(page.height) || page.height <= 0) {
    throw new Error('Invalid journal page.');
  }
  return { id: uuid(metadata.id), date: calendarDate(entry.date), width: page.width, height: page.height };
}

type Entry = ReturnType<typeof entryOf>;

export function Journal({ scope }: { scope: Scope }) {
  const { dataset, epoch } = scope;
  const [cursor, setCursor] = useState<string | null>(null);
  const [retry, setRetry] = useState(0);
  const [result, setResult] = useState<{ entries: Entry[]; next: string | null } | null>(null);
  const [entry, setEntry] = useState<Entry | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const abort = new AbortController();
    void journalList('/v1/journal/entries', { dataset, epoch }, cursor, abort.signal).then(list => {
      if (!abort.signal.aborted) setResult({ entries: list.items.map(entryOf), next: list.next });
    }).catch(() => { if (!abort.signal.aborted) setFailed(true); });
    return () => abort.abort();
  }, [dataset, epoch, cursor, retry]);

  function load(next: string | null) {
    setEntry(null);
    setFailed(false);
    setResult(null);
    if (next === cursor) setRetry(value => value + 1);
    else setCursor(next);
  }

  return <>
    <p>Read-only preview of pages committed to this server. Journal editing remains on Android.</p>
    {failed ? <p role="alert">Could not load journal entries. Check your connection or sign in again.</p> :
      !result ? <p role="status">Loading journal entries…</p> :
      result.entries.length === 0 ? <p>No journal pages have been committed to this server.</p> :
      <ul>{result.entries.map(item => <li key={item.id}>
        <button aria-pressed={entry?.id === item.id} onClick={() => setEntry(item)}>{item.date}</button>
      </li>)}</ul>}
    {cursor && <button onClick={() => load(null)}>First results</button>}
    {result?.next && <button onClick={() => load(result.next)}>Next results</button>}
    {(failed || result) && <button onClick={() => load(cursor)}>{failed ? 'Retry' : 'Refresh entries'}</button>}
    {entry && <JournalPage key={entry.id} entry={entry} scope={scope} />}
  </>;
}

function JournalPage({ entry, scope }: { entry: Entry; scope: Scope }) {
  const { dataset, epoch } = scope;
  const canvas = useRef<HTMLCanvasElement>(null);
  const [status, setStatus] = useState<'loading' | 'ready' | 'failed'>('loading');
  const [retry, setRetry] = useState(0);
  const scale = 1536 / Math.max(entry.width, entry.height);

  useEffect(() => {
    const abort = new AbortController();
    const context = canvas.current?.getContext('2d');
    async function render() {
      if (!context) throw new Error('Canvas is unavailable.');
      context.canvas.width = context.canvas.width;
      context.scale(scale, scale);
      context.beginPath();
      context.rect(0, 0, entry.width, entry.height);
      context.clip();
      context.lineCap = context.lineJoin = 'round';
      const requested = { dataset, epoch };
      let cursor: string | null = null;
      let budget = 64 * 1024 * 1024;
      let count = 0;
      do {
        const list = await journalList('/v1/journal/pages/' + entry.id + '/strokes', requested, cursor, abort.signal);
        for (const value of list.items) {
          if (++count > 10_000) throw new Error('Preview stroke limit exceeded.');
          const stroke = strokeSummary(value, entry.id);
          const bytes = await downloadStroke(requested, stroke, budget, abort.signal);
          abort.signal.throwIfAborted();
          budget -= bytes.byteLength;
          drawStroke(context, bytes, stroke, entry);
        }
        cursor = list.next;
      } while (cursor);
      if (!abort.signal.aborted) setStatus('ready');
    }
    void render().catch(() => { if (!abort.signal.aborted) setStatus('failed'); });
    return () => abort.abort();
  }, [entry, dataset, epoch, scale, retry]);

  return <figure className="journal-preview">
    <figcaption>{entry.date}</figcaption>
    {status === 'loading' && <p role="status">Loading handwriting…</p>}
    {status === 'failed' && <>
      <p role="alert">Could not display this page. It may exceed the preview limits, or the connection may have been interrupted.</p>
      <button onClick={() => { setStatus('loading'); setRetry(value => value + 1); }}>Retry page</button>
    </>}
    <canvas ref={canvas} hidden={status !== 'ready'} aria-label={'Handwriting for ' + entry.date}
      role="img" width={Math.max(1, Math.round(entry.width * scale))} height={Math.max(1, Math.round(entry.height * scale))} />
  </figure>;
}
