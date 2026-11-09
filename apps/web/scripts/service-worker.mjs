import { createHash } from 'node:crypto';
import { readdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const root = new URL('../dist/', import.meta.url);
const entries = (await readdir(root, { recursive: true, withFileTypes: true }))
  .filter(entry => entry.isFile() && entry.name !== 'sw.js')
  .map(entry => join(entry.parentPath, entry.name).slice(root.pathname.length))
  .sort();

const digest = createHash('sha256');
const worker = await readFile(new URL('./worker-template.js', import.meta.url), 'utf8');
digest.update(worker);
for (const name of entries) digest.update(name).update(await readFile(new URL(name, root)));

const shell = entries.map(name => '/' + name);
await writeFile(new URL('sw.js', root), worker
  .replace("/* VERSION */ ''", JSON.stringify('lifeapp-shell-' + digest.digest('hex').slice(0, 24)))
  .replace('/* SHELL */ []', JSON.stringify(shell)));
