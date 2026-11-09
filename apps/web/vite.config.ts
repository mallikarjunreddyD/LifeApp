import { readFileSync } from 'node:fs';
import { defineConfig } from 'vite';

const backend = process.env.LIFEAPP_WEB_API;
const key = process.env.LIFEAPP_WEB_TLS_KEY;
const cert = process.env.LIFEAPP_WEB_TLS_CERT;
if (Boolean(key) !== Boolean(cert)) throw new Error('Set both local TLS file paths.');
const https = key && cert ? { key: readFileSync(key), cert: readFileSync(cert) } : undefined;
const proxy = backend ? Object.fromEntries(['/v1', '/health', '/ready'].map(path => [path, {
  target: backend,
  changeOrigin: false,
}])) : undefined;

export default defineConfig({
  server: { host: '127.0.0.1', https, proxy },
  preview: { host: '127.0.0.1', https, proxy },
  build: { target: ['safari16', 'chrome107', 'firefox104'], sourcemap: false },
});
