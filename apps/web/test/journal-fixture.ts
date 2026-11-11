import { createHash, randomUUID } from 'node:crypto';
import { expect } from '@playwright/test';
import type { APIRequestContext } from '@playwright/test';

export async function commitJournalFixture(request: APIRequestContext, origin: string, date: string) {
  const login = await request.get('/v1/session');
  expect(login.status()).toBe(200);
  const session = await login.json();
  const headers = { 'X-CSRF-Token': session.csrfToken, Origin: origin };
  const entryId = randomUUID();
  const pageId = randomUUID();
  const strokeId = randomUUID();
  const uploadId = randomUUID();
  const time = '2026-11-09T12:00:00Z';
  const metadata = (id: string) => ({ id, createdAt: time, updatedAt: time, schemaVersion: 1, revision: 1 });
  const points = Buffer.alloc(52);
  points.write('LJPT');
  points.writeUInt32LE(1, 4);
  points.writeUInt32LE(2, 8);
  for (const [index, x] of [100, 300].entries()) {
    const offset = 12 + index * 20;
    points.writeFloatLE(x, offset);
    points.writeFloatLE(200, offset + 4);
    points.writeFloatLE(-1, offset + 8);
    points.writeBigInt64LE(BigInt(index), offset + 12);
  }
  const sha256 = createHash('sha256').update(points).digest('hex');
  expect((await request.put('/v1/ink/staged/' + uploadId, { data: points, headers: {
    ...headers, 'X-LifeApp-Dataset': session.scope.dataset, 'X-LifeApp-Epoch': session.scope.epoch,
    'X-Content-SHA256': sha256, 'Content-Type': 'application/octet-stream',
  } })).status()).toBe(204);

  // Kotlin's portable format encodes integral Float fields with a decimal point.
  const commit = JSON.stringify({
    scope: session.scope, uploadId, sha256,
    entry: { metadata: metadata(entryId), date, pages: [{
      metadata: metadata(pageId), entryId, order: 0, width: 1000, height: 1000, paper: 'BLANK', archived: false,
    }] },
    stroke: { metadata: metadata(strokeId), surfaceId: pageId, order: 0,
      brush: 'PEN', colorArgb: 0xff000000, width: 10, pointFormatVersion: 1 },
  }).replace(/("(?:width|height)":\d+)(?=[,}])/g, '$1.0');
  expect((await request.post('/v1/journal/ink/commit', {
    data: commit, headers: { ...headers, 'Content-Type': 'application/json' },
  })).status()).toBe(204);
}
