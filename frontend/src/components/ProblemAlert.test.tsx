import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ApiError, TRACE_ID_HEADER, unwrap } from '../api/client';
import { renderWithProviders } from '../tests/render';
import { ProblemAlert } from './ProblemAlert';

const TRACE = '09751c214f483f66a8cfe4be09d97ba9';

describe('ProblemAlert', () => {
  it('shows the trace id from the problem body', () => {
    const error = new ApiError(500, { title: 'Internal error', code: 'INTERNAL_ERROR', trace_id: TRACE });
    renderWithProviders(<ProblemAlert error={error} />);
    expect(screen.getByRole('alert')).toHaveTextContent(`Trace ID:${TRACE}`);
    expect(screen.getByRole('button', { name: 'Copy trace ID' })).toBeInTheDocument();
  });

  it('omits the trace line when there is none', () => {
    renderWithProviders(<ProblemAlert error={new ApiError(422, { title: 'Validation failed' })} />);
    expect(screen.getByRole('alert')).not.toHaveTextContent('Trace ID');
  });
});

describe('unwrap', () => {
  it('falls back to the trace header when the body is not a problem document', async () => {
    const response = new Response('Bad Gateway', { status: 502, headers: { [TRACE_ID_HEADER]: TRACE } });
    const failed = unwrap(Promise.resolve({ error: 'Bad Gateway', response }));
    await expect(failed).rejects.toMatchObject({ status: 502, traceId: TRACE });
  });
});
