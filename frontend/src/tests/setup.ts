import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from './server';

// jsdom's AbortSignal is not accepted by Node's native fetch Request (undici); drop foreign signals in tests.
const NativeRequest = globalThis.Request;
class TestRequest extends NativeRequest {
  constructor(input: RequestInfo | URL, init?: RequestInit) {
    if (init?.signal && !(init.signal instanceof (globalThis as { AbortSignal: typeof AbortSignal }).AbortSignal)) {
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      const { signal, ...rest } = init;
      super(input, rest);
    } else {
      try {
        super(input, init);
      } catch {
        // eslint-disable-next-line @typescript-eslint/no-unused-vars
        const { signal, ...rest } = init ?? {};
        super(input, rest);
      }
    }
  }
}
globalThis.Request = TestRequest as typeof Request;

// Mantine relies on these browser APIs that jsdom lacks.
const { getComputedStyle } = window;
window.getComputedStyle = (elt) => getComputedStyle(elt);
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: !query.includes('prefers-color-scheme'), // behave like a desktop viewport
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
});
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver;
window.HTMLElement.prototype.scrollIntoView = () => {};

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  cleanup();
  server.resetHandlers();
});
afterAll(() => server.close());
