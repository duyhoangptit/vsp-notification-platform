import { expect, type Page } from '@playwright/test';

/** Seeded operators of the backend `local` profile (quickstart.md). */
export const USERS = {
  ADMIN: { username: 'admin', password: 'admin123' },
  TEMPLATE_EDITOR: { username: 'editor', password: 'editor123' },
  SUPPORT_VIEWER: { username: 'support', password: 'support123' },
} as const;

export const API = process.env.PNH_API_URL ?? 'http://localhost:8080';
export const CLIENT_AUTH = 'Basic ' + Buffer.from('demo-client:demo-secret').toString('base64');

export async function login(page: Page, role: keyof typeof USERS) {
  await page.goto('/login');
  await page.getByLabel('Username').fill(USERS[role].username);
  await page.getByLabel('Password').fill(USERS[role].password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/dashboard/);
}

export function uid(prefix: string) {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
}
