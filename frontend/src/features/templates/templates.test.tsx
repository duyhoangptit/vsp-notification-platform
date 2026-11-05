import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { API, clockHandler, meHandler } from '../../tests/handlers';
import { renderApp } from '../../tests/render';
import { server } from '../../tests/server';
import { scanPlaceholders, smsInfo } from './placeholders';

const smsTemplate = {
  template_code: 'OTP_SMS',
  template_name: 'OTP',
  channel_type: 'SMS',
  category: 'STANDARD',
  is_critical: false,
  body_template: 'Ma ${otp_code}',
  status: 'ACTIVE',
  required_variables: ['otp_code'],
  version: 3,
  updated_by: 'editor',
  updated_at: '2026-11-03T10:00:00Z',
};

describe('placeholder helpers', () => {
  it('finds valid and invalid placeholders', () => {
    expect(scanPlaceholders('Hi ${name} ${name} ${T(x)} ${otp_code}')).toEqual({
      valid: ['name', 'otp_code'],
      invalid: ['${T(x)}'],
    });
  });

  it('counts SMS segments for GSM-7 and UCS-2', () => {
    expect(smsInfo('a'.repeat(160))).toMatchObject({ segments: 1, encoding: 'GSM-7' });
    expect(smsInfo('a'.repeat(161)).segments).toBe(2);
    expect(smsInfo('ữ'.repeat(70))).toMatchObject({ segments: 1, encoding: 'UCS-2' });
    expect(smsInfo('ữ'.repeat(71)).segments).toBe(2);
  });
});

describe('templates screens', () => {
  it('list sends filters as query parameters', async () => {
    const seen: URLSearchParams[] = [];
    server.use(
      meHandler('TEMPLATE_EDITOR'),
      clockHandler('SYSTEM'),
      http.get(API('/console/api/templates'), ({ request }) => {
        seen.push(new URL(request.url).searchParams);
        return HttpResponse.json({ items: [smsTemplate], total: 1 });
      }),
    );
    renderApp('/templates');
    expect(await screen.findByText('OTP_SMS')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Search'), 'otp');
    await waitFor(() => expect(seen.some((p) => p.get('q') === 'otp')).toBe(true));
  });

  it('SMS editor shows variables, character count and segments; preview renders values', async () => {
    server.use(
      meHandler('TEMPLATE_EDITOR'),
      clockHandler('SYSTEM'),
      http.get(API('/console/api/templates/OTP_SMS'), () => HttpResponse.json(smsTemplate)),
      http.post(API('/console/api/templates/preview'), async ({ request }) => {
        const body = (await request.json()) as { variables: Record<string, string> };
        return HttpResponse.json({
          body: `Ma ${body.variables.otp_code}`,
          character_count: 9,
          sms_segments: 1,
          missing_variables: [],
        });
      }),
    );
    renderApp('/templates/OTP_SMS');
    const body = await screen.findByLabelText('Body');
    await waitFor(() => expect(body).toHaveValue('Ma ${otp_code}'));
    expect(within(screen.getByTestId('required-variables')).getByText('otp_code')).toBeInTheDocument();
    expect(screen.getByText(/14 characters · 1 segment\(s\) · GSM-7/)).toBeInTheDocument();

    fireEvent.change(body, { target: { value: 'Ma ${otp_code} ${bad name}' } });
    expect(screen.getByText('invalid ${bad name}')).toBeInTheDocument();

    fireEvent.change(body, { target: { value: 'Ma ${otp_code}' } });
    await userEvent.click(screen.getByRole('button', { name: 'Preview' }));
    await userEvent.type(await screen.findByLabelText('otp_code'), '654321');
    await userEvent.click(screen.getByRole('button', { name: 'Render preview' }));
    expect(await screen.findByText('Ma 654321')).toBeInTheDocument();
  });

  it('409 VERSION_CONFLICT shows the conflict message with reload', async () => {
    server.use(
      meHandler('ADMIN'),
      clockHandler('SYSTEM'),
      http.get(API('/console/api/templates/OTP_SMS'), () => HttpResponse.json(smsTemplate)),
      http.put(API('/console/api/templates/OTP_SMS'), () =>
        HttpResponse.json(
          { type: 'x', title: 'Conflict', status: 409, code: 'VERSION_CONFLICT', current: { ...smsTemplate, version: 4 } },
          { status: 409 },
        ),
      ),
    );
    renderApp('/templates/OTP_SMS');
    await waitFor(() => expect(screen.getByLabelText('Body')).toHaveValue('Ma ${otp_code}'));
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Modified by someone else')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reload latest version' })).toBeInTheDocument();
  });
});
