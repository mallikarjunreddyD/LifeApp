import { journalHeaders, withRequestTimeout } from './api.ts';
import { object, uuid } from './protocol.ts';
import type { Scope } from './protocol.ts';

const maxStrokeBytes = 20_000_012;

export function strokeSummary(value: unknown, pageId: string) {
  const item = object(value, ['stroke', 'sha256']);
  const stroke = object(item.stroke, ['metadata', 'surfaceId', 'order', 'brush', 'colorArgb', 'width', 'pointFormatVersion']);
  const metadata = object(stroke.metadata, ['id', 'createdAt', 'updatedAt', 'schemaVersion', 'revision']);
  if (typeof item.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(item.sha256) ||
      uuid(stroke.surfaceId) !== pageId || stroke.pointFormatVersion !== 1 ||
      (stroke.brush !== 'PEN' && stroke.brush !== 'HIGHLIGHTER') ||
      typeof stroke.colorArgb !== 'number' || !Number.isInteger(stroke.colorArgb) ||
      stroke.colorArgb < 0 || stroke.colorArgb > 0xffffffff ||
      typeof stroke.width !== 'number' || !Number.isFinite(stroke.width) || stroke.width < 0.1 || stroke.width > 100) {
    throw new Error('Invalid journal stroke header.');
  }
  return { id: uuid(metadata.id), digest: item.sha256, color: stroke.colorArgb, width: stroke.width };
}

export async function downloadStroke(scope: Scope, stroke: ReturnType<typeof strokeSummary>, budget: number, signal: AbortSignal) {
  return withRequestTimeout(30_000, signal, async requestSignal => {
    const response = await fetch('/v1/journal/ink/' + stroke.id, {
      credentials: 'same-origin', cache: 'no-store', redirect: 'error', headers: journalHeaders(scope),
      signal: requestSignal,
    });
    const limit = Math.min(budget, maxStrokeBytes);
    const length = Number(response.headers.get('content-length'));
    if (!response.ok || response.headers.get('content-type') !== 'application/octet-stream' ||
        response.headers.get('X-Content-SHA256') !== stroke.digest ||
        !Number.isSafeInteger(length) || length < 32 || length > limit || !response.body) {
      void response.body?.cancel().catch(() => {});
      throw new Error('Could not download journal stroke within preview limits.');
    }
    const bytes = new Uint8Array(length);
    const reader = response.body.getReader();
    let offset = 0;
    try {
      for (;;) {
        const chunk = await reader.read();
        if (chunk.done) break;
        if (offset + chunk.value.byteLength > length) throw new Error('Invalid journal stroke length.');
        bytes.set(chunk.value, offset);
        offset += chunk.value.byteLength;
      }
    } finally {
      void reader.cancel().catch(() => {});
      reader.releaseLock();
    }
    const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
    if (offset !== length || Array.from(digest, byte => byte.toString(16).padStart(2, '0')).join('') !== stroke.digest) {
      throw new Error('Journal stroke failed checksum verification.');
    }
    return bytes;
  });
}

export function drawStroke(context: CanvasRenderingContext2D, bytes: Uint8Array,
  stroke: ReturnType<typeof strokeSummary>, page: { width: number; height: number }) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const count = view.getUint32(8, true);
  if (view.getUint32(0, true) !== 0x54504a4c || view.getUint32(4, true) !== 1 ||
      count < 1 || count > 1_000_000 || view.byteLength !== 12 + count * 20) {
    throw new Error('Invalid LJPT stroke.');
  }
  const color = stroke.color.toString(16).padStart(8, '0');
  context.strokeStyle = context.fillStyle = '#' + color.slice(2) + color.slice(0, 2);
  context.lineWidth = stroke.width;
  context.beginPath();
  let previous = -1n;
  for (let index = 0; index < count; index++) {
    const offset = 12 + index * 20;
    const x = view.getFloat32(offset, true);
    const y = view.getFloat32(offset + 4, true);
    const pressure = view.getFloat32(offset + 8, true);
    const elapsed = view.getBigInt64(offset + 12, true);
    if (!(x >= 0 && x <= page.width && y >= 0 && y <= page.height) ||
        !(pressure === -1 || pressure >= 0 && pressure <= 1) || elapsed <= previous) {
      throw new Error('Invalid LJPT point.');
    }
    previous = elapsed;
    if (count === 1) context.arc(x, y, stroke.width / 2, 0, Math.PI * 2);
    else if (index === 0) context.moveTo(x, y);
    else context.lineTo(x, y);
  }
  if (count === 1) context.fill();
  else context.stroke();
}
