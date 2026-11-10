import { CodeHighlightAdapterProvider } from '@mantine/code-highlight';
import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import { RouterProvider } from 'react-router';
import { highlightJsAdapter } from './codeHighlight';
import { createRouter } from './router';
import { cssVariablesResolver, theme } from './theme';

export function App() {
  const [queryClient] = useState(() => new QueryClient({ defaultOptions: { queries: { refetchOnWindowFocus: false } } }));
  const [router] = useState(createRouter);
  return (
    <MantineProvider theme={theme} cssVariablesResolver={cssVariablesResolver} defaultColorScheme="auto">
      <Notifications />
      <CodeHighlightAdapterProvider adapter={highlightJsAdapter}>
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </CodeHighlightAdapterProvider>
    </MantineProvider>
  );
}
