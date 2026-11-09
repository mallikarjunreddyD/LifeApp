import { createRoot } from 'react-dom/client';
import { App } from './App.tsx';
import { openStore } from './store.ts';
import './styles.css';

const root = createRoot(document.getElementById('root')!);
openStore().then(store => root.render(<App store={store} />)).catch(() => {
  root.render(<main><h1>LifeApp</h1><p role="alert">Browser storage is unavailable. Open LifeApp in a browser with IndexedDB enabled.</p></main>);
});
