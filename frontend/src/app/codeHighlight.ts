import { createHighlightJsAdapter } from '@mantine/code-highlight';
import hljs from 'highlight.js/lib/core';
import json from 'highlight.js/lib/languages/json';
import './codeHighlight.css';

// Register only what the console renders, to keep highlight.js out of the main bundle's weight.
hljs.registerLanguage('json', json);

export const highlightJsAdapter = createHighlightJsAdapter(hljs);
