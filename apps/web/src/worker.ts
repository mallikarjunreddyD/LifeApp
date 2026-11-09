import { useEffect, useState } from 'react';

export function useWorkerUpdate(): (() => void) | null {
  const [update, setUpdate] = useState<(() => void) | null>(null);
  useEffect(() => {
    if (import.meta.env.DEV || !('serviceWorker' in navigator)) return;
    let registration: ServiceWorkerRegistration | undefined;
    let installing: ServiceWorker | null = null;
    let mounted = true;
    let requested = false;
    let reloading = false;
    let activating: ServiceWorker | null = null;
    const reload = () => {
      if (requested && !reloading) {
        reloading = true;
        window.location.reload();
      }
    };
    const controller = () => {
      if (!activating || navigator.serviceWorker.controller === activating) reload();
    };
    const activated = () => { if (activating?.state === 'activated') reload(); };
    const showWaiting = () => {
      if (!mounted || !registration?.waiting || !navigator.serviceWorker.controller) return;
      setUpdate(() => () => {
        requested = true;
        activating?.removeEventListener('statechange', activated);
        activating = registration?.waiting ?? null;
        if (!activating) {
          reload();
          return;
        }
        activating.addEventListener('statechange', activated);
        activating.postMessage('LIFEAPP_ACTIVATE');
      });
    };
    const discover = () => {
      installing?.removeEventListener('statechange', showWaiting);
      installing = registration?.installing ?? null;
      installing?.addEventListener('statechange', showWaiting);
    };
    const check = () => { void registration?.update().catch(() => {}); };
    navigator.serviceWorker.addEventListener('controllerchange', controller);
    window.addEventListener('online', check);
    void navigator.serviceWorker.register('/sw.js').then(found => {
      if (!mounted) return;
      registration = found;
      registration.addEventListener('updatefound', discover);
      discover();
      showWaiting();
      check();
    }).catch(() => { /* The online app remains usable when shell caching is unavailable. */ });
    return () => {
      mounted = false;
      window.removeEventListener('online', check);
      navigator.serviceWorker.removeEventListener('controllerchange', controller);
      registration?.removeEventListener('updatefound', discover);
      installing?.removeEventListener('statechange', showWaiting);
      activating?.removeEventListener('statechange', activated);
    };
  }, []);
  return update;
}
