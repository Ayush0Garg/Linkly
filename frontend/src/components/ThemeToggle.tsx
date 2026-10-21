import { useEffect, useState } from 'react';

type Theme = 'auto' | 'light' | 'dark';
const ORDER: Theme[] = ['auto', 'light', 'dark'];
const KEY = 'linkly-theme';

function readStored(): Theme {
  try {
    const value = localStorage.getItem(KEY);
    return value === 'light' || value === 'dark' ? value : 'auto';
  } catch {
    return 'auto'; // storage can be blocked (private windows, strict settings)
  }
}

function apply(theme: Theme) {
  if (theme === 'auto') delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = theme;
}

/** Applied once at startup too (see main.tsx) so there is no flash of the wrong theme. */
export function initTheme() {
  apply(readStored());
}

export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>(readStored);
  useEffect(() => apply(theme), [theme]);

  function next() {
    const following = ORDER[(ORDER.indexOf(theme) + 1) % ORDER.length]!;
    setTheme(following);
    try {
      if (following === 'auto') localStorage.removeItem(KEY);
      else localStorage.setItem(KEY, following);
    } catch {
      /* not persisted; still applied for this session */
    }
  }

  return (
    <button type="button" className="btn btn--text copy" onClick={next} aria-label={`Colour theme: ${theme}. Change theme`}>
      Theme: {theme}
    </button>
  );
}
