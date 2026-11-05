/** Placeholder rules shared with the backend (research R8): `${identifier}` only. */
export const PLACEHOLDER = /\$\{([^}]*)\}/g;
const VALID_NAME = /^[a-zA-Z_][a-zA-Z0-9_]{0,63}$/;

export interface PlaceholderScan {
  valid: string[];
  invalid: string[];
}

export function scanPlaceholders(...texts: (string | undefined | null)[]): PlaceholderScan {
  const valid: string[] = [];
  const invalid: string[] = [];
  for (const text of texts) {
    if (!text) continue;
    for (const m of text.matchAll(PLACEHOLDER)) {
      const name = m[1];
      if (VALID_NAME.test(name)) {
        if (!valid.includes(name)) valid.push(name);
      } else if (!invalid.includes(m[0])) {
        invalid.push(m[0]);
      }
    }
  }
  return { valid, invalid };
}

export function isValidPlaceholderName(name: string): boolean {
  return VALID_NAME.test(name);
}

const GSM7 =
  '@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ ÆæßÉ!"#¤%&\'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà^{}\\[~]|€';

/** Character count and SMS segments (GSM-7 160/153, UCS-2 70/67) — same rule as the backend preview. */
export function smsInfo(text: string): { chars: number; segments: number; encoding: 'GSM-7' | 'UCS-2' } {
  const chars = text.length;
  const gsm = [...text].every((c) => GSM7.includes(c));
  const single = gsm ? 160 : 70;
  const multi = gsm ? 153 : 67;
  const segments = chars === 0 ? 0 : chars <= single ? 1 : Math.ceil(chars / multi);
  return { chars, segments, encoding: gsm ? 'GSM-7' : 'UCS-2' };
}
