const icons = {
  'To-do': 'M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Zm2 7 3 3 6-6',
  Workouts: 'M3 9v6m3-9v12m0-6h12m0-6v12m3-9v6',
  History: 'M4 10a8 8 0 1 1 1 7m-1-7V5m0 5h5m3-2v5l3 2',
  Habits: 'M20 12a8 8 0 1 1-16 0 8 8 0 0 1 16 0Zm-12 0 3 3 5-6',
  Expenses: 'M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Zm16 4h-5a2 2 0 0 0-2 2v2a2 2 0 0 0 2 2h5m-4-3h.01',
  Settings: 'M4 6h3m4 0h9M4 12h10m4 0h2M4 18h5m4 0h7M7 4v4m7 2v4m-5 2v4',
} as const;

export function NavigationIcon({ name }: { name: keyof typeof icons }) {
  return <svg aria-hidden="true" width="22" height="22" viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round">
    <path d={icons[name]} />
  </svg>;
}
