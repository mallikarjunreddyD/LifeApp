import { useEffect, useRef, useState } from 'react';
import { editablePreview, editableVersions } from './api.ts';
import { drawStroke } from './journalInk.ts';
import { object, sameScope, scopeOf, sequence, uuid } from './protocol.ts';
import type { Scope } from './protocol.ts';

const labels = { journal: 'Journal', planner: 'Planner', habits: 'Habits', notebook: 'Notebook' } as const;

function array(value: unknown): unknown[] {
  if (!Array.isArray(value)) throw new Error('Invalid record list.');
  return value as unknown[];
}

function text(value: unknown): string {
  if (typeof value !== 'string') throw new Error('Invalid record text.');
  return value;
}

function number(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error('Invalid record geometry.');
  return value;
}

function flag(value: unknown): boolean {
  if (typeof value !== 'boolean') throw new Error('Invalid record state.');
  return value;
}

function previewOf(value: unknown) {
  const preview = object(value, ['title', 'pages', 'habits']);
  return {
    title: text(preview.title),
    pages: array(preview.pages).map(item => {
      const page = object(item, ['title', 'width', 'height', 'archived', 'cells', 'strokes']);
      const width = number(page.width);
      const height = number(page.height);
      if (width <= 0 || height <= 0) throw new Error('Invalid page dimensions.');
      const cells = array(page.cells).map(item => {
        const [day, left, top, right, bottom] = array(item);
        return { day: number(day), left: number(left), top: number(top), right: number(right), bottom: number(bottom) };
      });
      const strokes = array(page.strokes).map(item => {
        const stroke = object(item, ['color', 'width', 'points', 'day']);
        return { color: number(stroke.color), width: number(stroke.width), points: text(stroke.points),
          day: stroke.day === null ? null : number(stroke.day) };
      });
      return { title: text(page.title), width, height, archived: flag(page.archived), cells, strokes };
    }),
    habits: array(preview.habits).map(item => {
      const habit = object(item, ['name', 'archived', 'completedDays']);
      return { name: text(habit.name), archived: flag(habit.archived), days: array(habit.completedDays).map(number) };
    }),
  };
}

type Preview = ReturnType<typeof previewOf>;

export function EditableRecords({ scope }: { scope: Scope }) {
  const { dataset, epoch } = scope;
  const [open, setOpen] = useState(false);
  const [after, setAfter] = useState('0');
  const [retry, setRetry] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const [failure, setFailure] = useState(false);
  const [listing, setListing] = useState<{
    rows: { id: string; label: string; current: boolean }[]; next: string | null;
  } | null>(null);

  useEffect(() => {
    if (!open) return;
    const cancel = new AbortController();
    const requested = { dataset, epoch };
    void editableVersions(requested, after, cancel.signal).then(value => {
      const response = object(value, ['scope', 'items', 'hasMore']);
      if (!sameScope(requested, scopeOf(response.scope))) throw new Error('The record scope changed.');
      let cursor = after;
      const rows = array(response.items).map(item => {
        const row = object(item, ['id', 'sequence', 'kind', 'key', 'rootId', 'sha256', 'parents', 'current']);
        const kind = text(row.kind);
        if (!Object.hasOwn(labels, kind)) throw new Error('Invalid record kind.');
        const next = sequence(row.sequence);
        if (BigInt(next) <= BigInt(cursor)) throw new Error('Invalid record order.');
        cursor = next;
        return { id: uuid(row.id), label: labels[kind as keyof typeof labels] + ' · ' + text(row.key), current: flag(row.current) };
      });
      if (!cancel.signal.aborted) setListing({ rows, next: flag(response.hasMore) && rows.length ? cursor : null });
    }).catch(() => { if (!cancel.signal.aborted) setFailure(true); });
    return () => cancel.abort();
  }, [after, dataset, epoch, open, retry]);

  function load(cursor: string) {
    setListing(null);
    setFailure(false);
    setSelected(null);
    if (after === cursor) setRetry(value => value + 1);
    else setAfter(cursor);
  }

  const next = listing?.next;
  return <details onToggle={event => {
    setOpen(event.currentTarget.open);
    if (!event.currentTarget.open) load('0');
  }}>
    <summary>Server record versions</summary>
    {open && <>
      <p>Read-only previews of committed journals, planners, habits and notebooks.</p>
      {failure ? <p role="alert">Could not load server record versions.</p> :
        !listing ? <p role="status">Loading record versions…</p> :
        listing.rows.length === 0 ? <p>No committed record versions.</p> :
        <ul>{listing.rows.map(row => <li key={row.id}>
          <button aria-pressed={selected === row.id} onClick={() => setSelected(row.id)}>
            {row.label}{row.current ? '' : ' (history)'}
          </button>
        </li>)}</ul>}
      {after !== '0' && <button onClick={() => load('0')}>First versions</button>}
      {next && <button onClick={() => load(next)}>Next versions</button>}
      {(failure || listing) && <button onClick={() => load(after)}>{failure ? 'Retry' : 'Refresh versions'}</button>}
      {selected && <RecordPreview key={selected} scope={scope} id={selected} />}
    </>}
  </details>;
}

function RecordPreview({ scope: { dataset, epoch }, id }: { scope: Scope; id: string }) {
  const [preview, setPreview] = useState<Preview | null>(null);
  const [failed, setFailed] = useState(false);
  const [pageIndex, setPageIndex] = useState(0);
  useEffect(() => {
    const cancel = new AbortController();
    void editablePreview({ dataset, epoch }, id, cancel.signal).then(value => {
      if (!cancel.signal.aborted) setPreview(previewOf(value));
    }).catch(() => { if (!cancel.signal.aborted) setFailed(true); });
    return () => cancel.abort();
  }, [dataset, epoch, id]);
  if (failed) return <p role="alert">Could not display this version. It may exceed the preview limit.</p>;
  if (!preview) return <p role="status">Loading record preview…</p>;
  const page = preview.pages[pageIndex];
  return <section>
    <h2>{preview.title}</h2>
    {preview.pages.length > 1 && <label>Page <select value={pageIndex} onChange={event => setPageIndex(Number(event.target.value))}>
      {preview.pages.map((item, index) => <option key={index} value={index}>{item.title}{item.archived ? ' (archived)' : ''}</option>)}
    </select></label>}
    {page && <VectorPage key={pageIndex} page={page} />}
    {preview.habits.length > 0 && <ul>{preview.habits.map((habit, index) => <li key={index}>
      {habit.name}{habit.archived ? ' (archived)' : ''} · {habit.days.length ? 'Completed days ' + habit.days.join(', ') : 'No completed days'}
    </li>)}</ul>}
    {!page && preview.habits.length === 0 && <p>This version has no pages or habit definitions.</p>}
  </section>;
}

function VectorPage({ page }: { page: Preview['pages'][number] }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [failed, setFailed] = useState(false);
  const scale = 1536 / Math.max(page.width, page.height);
  useEffect(() => {
    const context = canvas.current?.getContext('2d');
    if (!context) { setFailed(true); return; }
    try {
      context.canvas.width = context.canvas.width;
      context.scale(scale, scale);
      context.beginPath();
      context.rect(0, 0, page.width, page.height);
      context.clip();
      context.lineCap = context.lineJoin = 'round';
      context.strokeStyle = '#b8b2a8';
      context.fillStyle = '#34302d';
      context.font = '20px sans-serif';
      for (const cell of page.cells) {
        context.strokeRect(cell.left, cell.top, cell.right - cell.left, cell.bottom - cell.top);
        context.fillText(String(cell.day), cell.left + 10, cell.top + 25);
      }
      for (const stroke of page.strokes) {
        const bytes = Uint8Array.from(atob(stroke.points), char => char.charCodeAt(0));
        const cell = page.cells.find(item => item.day === stroke.day);
        context.save();
        try {
          if (cell) {
            context.beginPath();
            context.rect(cell.left, cell.top, cell.right - cell.left, cell.bottom - cell.top);
            context.clip();
          }
          drawStroke(context, bytes, stroke, page);
        } finally {
          context.restore();
        }
      }
    } catch {
      setFailed(true);
    }
  }, [page, scale]);
  return <figure className="journal-preview">
    <figcaption>{page.title}{page.archived ? ' (archived)' : ''}</figcaption>
    {failed && <p role="alert">This page could not be drawn.</p>}
    <canvas ref={canvas} hidden={failed} role="img" aria-label={'Handwriting for ' + page.title}
      width={Math.max(1, Math.round(page.width * scale))} height={Math.max(1, Math.round(page.height * scale))} />
  </figure>;
}
