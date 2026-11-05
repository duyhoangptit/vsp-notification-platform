import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';

dayjs.extend(utc);

export function formatDateTime(value?: string | null): string {
  if (!value) return '—';
  return dayjs(value).format('YYYY-MM-DD HH:mm:ss');
}

export function formatNumber(value?: number | null): string {
  if (value === undefined || value === null) return '—';
  return new Intl.NumberFormat('en-US').format(value);
}

export function formatPercent(value?: number | null): string {
  if (value === undefined || value === null || Number.isNaN(value)) return '—';
  return `${(value * 100).toFixed(1)}%`;
}

/** Display masking for recipients (server already masks list views; this is a fallback for client-side display). */
export function maskRecipient(value?: string | null): string {
  if (!value) return '—';
  const at = value.indexOf('@');
  if (at > 0) return `${value[0]}***${value.substring(at)}`;
  if (value.length < 7) return '*****';
  if (value.startsWith('+') || /^\d+$/.test(value)) return `${value.substring(0, 3)}*****${value.substring(value.length - 3)}`;
  return value.length <= 12 ? '*****' : `${value.substring(0, 8)}…${value.substring(value.length - 4)}`;
}
