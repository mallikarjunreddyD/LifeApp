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
    let original: ServiceWorker | null = null;
    let retry: ReturnType<typeof setTimeout> | undefined;
    const reload = () => {
      if (requested && !reloading) {
        reloading = true;
        clearTimeout(retry);
        window.location.reload();
      }
    };
    const controller = () => {
      if (navigator.serviceWorker.controller && navigator.serviceWorker.controller !== original) reload();
    };
    const activated = () => {
      if (activating?.state === 'activated') reload();
    };
    const requestActivation = (attempt = 0) => {
      controller();
      if (reloading || !mounted) return;
      const waiting = registration?.waiting;
      if (waiting && waiting !== activating) {
        activating?.removeEventListener('statechange', activated);
        activating = waiting;
        activating.addEventListener('statechange', activated);
      }
      activated();
      if (reloading) return;
      if (waiting?.state === 'installed') {
        try { waiting.postMessage('LIFEAPP_ACTIVATE'); }
        catch { /* The worker can be replaced during update discovery. */ }
      }
      // Update discovery can replace a waiting worker between the click and activation.
      // Re-read registration state instead of waiting indefinitely for that worker.
      if (attempt < 12) retry = setTimeout(() => requestActivation(attempt + 1), 500);
      else {
        requested = false;
        activating?.removeEventListener('statechange', activated);
        activating = null;
      }
    };
    const showWaiting = () => {
      if (!mounted || !registration?.waiting || !navigator.serviceWorker.controller) return;
      setUpdate(() => () => {
        if (requested) return;
        original = navigator.serviceWorker.controller;
        requested = true;
        requestActivation();
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
      clearTimeout(retry);
      window.removeEventListener('online', check);
      navigator.serviceWorker.removeEventListener('controllerchange', controller);
      registration?.removeEventListener('updatefound', discover);
      installing?.removeEventListener('statechange', showWaiting);
      activating?.removeEventListener('statechange', activated);
    };
  }, []);
  return update;
}
