export type Theme = 'light' | 'dark'

const KEY = 'rp.theme'

export function currentTheme(): Theme {
  return (document.documentElement.getAttribute('data-theme') as Theme) ?? 'light'
}

export function applyTheme(theme: Theme) {
  document.documentElement.setAttribute('data-theme', theme)
  document.documentElement.classList.toggle('dark', theme === 'dark')
  localStorage.setItem(KEY, theme)
}

export function toggleTheme(): Theme {
  const next: Theme = currentTheme() === 'dark' ? 'light' : 'dark'
  applyTheme(next)
  return next
}