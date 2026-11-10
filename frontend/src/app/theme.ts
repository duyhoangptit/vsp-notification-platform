import {
  Badge,
  Code,
  Drawer,
  Table,
  Tooltip,
  createTheme,
  rem,
  type CSSVariablesResolver,
  type MantineColorsTuple,
} from '@mantine/core';

/**
 * "Dispatch" — cobalt primary. Saturated enough to read as an action color next to the
 * status palette (green/red/orange), cool enough not to compete with it.
 */
const dispatch: MantineColorsTuple = [
  '#edf2ff',
  '#d9e2fd',
  '#b1c2f5',
  '#87a0ee',
  '#6383e8',
  '#4c71e5',
  '#3f67e4',
  '#3157cb',
  '#284db6',
  '#1a41a1',
];

/**
 * Cool graphite replacing Mantine's neutral `dark`. dark[7] is the body background,
 * dark[6] raised surfaces, dark[4] borders — a slight blue cast keeps JSON and charts legible.
 */
const graphite: MantineColorsTuple = [
  '#c8ccd4',
  '#a6abb6',
  '#878d9a',
  '#5d6371',
  '#3c414d',
  '#2d313b',
  '#23262e',
  '#1b1d23',
  '#15171b',
  '#0f1013',
];

export const theme = createTheme({
  primaryColor: 'dispatch',
  primaryShade: { light: 7, dark: 5 },
  colors: { dispatch, dark: graphite },
  autoContrast: true,

  // Be Vietnam Pro: designed for Vietnamese diacritics, which operators type into templates.
  fontFamily: '"Be Vietnam Pro", system-ui, -apple-system, "Segoe UI", sans-serif',
  fontFamilyMonospace: '"JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, monospace',
  // One step denser than Mantine defaults: a console, not a landing page.
  fontSizes: { xs: rem(11.5), sm: rem(13), md: rem(14), lg: rem(16), xl: rem(18) },
  lineHeights: { xs: '1.4', sm: '1.45', md: '1.5', lg: '1.5', xl: '1.45' },
  headings: {
    fontWeight: '600',
    sizes: {
      h1: { fontSize: rem(26), lineHeight: '1.25' },
      h2: { fontSize: rem(21), lineHeight: '1.3' },
      h3: { fontSize: rem(17), lineHeight: '1.35' },
      h4: { fontSize: rem(15), lineHeight: '1.4' },
    },
  },

  // Radius encodes hierarchy: controls are tight (xs), panels sm, overlays md.
  defaultRadius: 'sm',
  radius: { xs: rem(3), sm: rem(5), md: rem(8), lg: rem(12), xl: rem(16) },
  shadows: {
    xs: '0 1px 2px rgb(15 16 19 / 0.06)',
    sm: '0 1px 3px rgb(15 16 19 / 0.08), 0 1px 2px rgb(15 16 19 / 0.04)',
    md: '0 4px 12px rgb(15 16 19 / 0.10)',
    lg: '0 12px 32px rgb(15 16 19 / 0.16)',
    xl: '0 24px 56px rgb(15 16 19 / 0.22)',
  },

  components: {
    Badge: Badge.extend({
      defaultProps: { variant: 'light', radius: 'xs' },
      // Mantine's Badge is uppercase by default; statuses read faster in sentence case.
      styles: { root: { textTransform: 'none', fontWeight: 600, letterSpacing: 0 } },
    }),
    Code: Code.extend({ defaultProps: { fz: 'xs' } }),
    Drawer: Drawer.extend({
      defaultProps: {
        position: 'right',
        radius: 'md',
        offset: 8,
        overlayProps: { backgroundOpacity: 0.35, blur: 2 },
      },
    }),
    Table: Table.extend({
      defaultProps: { verticalSpacing: 6, horizontalSpacing: 'sm', highlightOnHover: true },
    }),
    Tooltip: Tooltip.extend({ defaultProps: { openDelay: 300, withArrow: true } }),
  },

  other: {
    /** Latency thresholds (ms) used by the delivery journey to colour gaps between steps. */
    latency: { slowMs: 2_000, criticalMs: 30_000 },
  },
});

/** App-level tokens Mantine doesn't ship, resolved per color scheme. */
export const cssVariablesResolver: CSSVariablesResolver = (t) => ({
  variables: {
    '--nh-mono-id-size': rem(12),
  },
  light: {
    '--nh-surface-sunken': t.colors.gray[0],
    '--nh-latency-ok': t.colors.gray[4],
    '--nh-latency-slow': t.colors.yellow[6],
    '--nh-latency-critical': t.colors.orange[7],
  },
  dark: {
    '--nh-surface-sunken': t.colors.dark[8],
    '--nh-latency-ok': t.colors.dark[3],
    '--nh-latency-slow': t.colors.yellow[5],
    '--nh-latency-critical': t.colors.orange[5],
  },
});
