/** A page of the documentation; a chapter's children are its pages. */
export interface DocsNode {
  readonly path: string;
  readonly title: string;
  readonly children?: readonly DocsNode[];
}

/** The documentation's main page. */
export const DOCS_HOME: DocsNode = { path: '/docs', title: 'Documentation' };

/** The chapters, in the order of the side navigation. */
export const DOCS_TREE: readonly DocsNode[] = [
  { path: '/docs/getting-started', title: 'Getting started' },
  {
    path: '/docs/format',
    title: 'Understanding mzTab-M',
    children: [
      { path: '/docs/format/metadata', title: 'Metadata (MTD)' },
      { path: '/docs/format/summary', title: 'Summary (SML)' },
      { path: '/docs/format/features', title: 'Features (SMF)' },
      { path: '/docs/format/evidence', title: 'Evidence (SME)' },
      { path: '/docs/format/references', title: 'How the rows refer to each other' },
      { path: '/docs/format/cv-parameters', title: 'Controlled vocabularies and parameters' },
      { path: '/docs/format/study-design', title: 'Describing your study design' },
    ],
  },
  { path: '/docs/versions', title: 'mzTab-M 2.0 and 2.1' },
  { path: '/docs/validating', title: 'Validating a file' },
  { path: '/docs/fixing', title: 'Fixing a file' },
  { path: '/docs/problems', title: 'Common problems' },
  { path: '/docs/integration', title: 'For tools and services' },
  { path: '/docs/about', title: 'About this service' },
];

/** Every page in reading order: the main page, then the chapters depth-first. */
export function docsOrder(): readonly DocsNode[] {
  const pages: DocsNode[] = [DOCS_HOME];
  const visit = (nodes: readonly DocsNode[]) =>
    nodes.forEach((n) => {
      pages.push(n);
      visit(n.children ?? []);
    });
  visit(DOCS_TREE);
  return pages;
}

/** The pages before and after `path` in reading order; both null for a path outside the tree. */
export function docsNeighbours(path: string): { prev: DocsNode | null; next: DocsNode | null } {
  const pages = docsOrder();
  const i = pages.findIndex((n) => n.path === path);
  if (i < 0) return { prev: null, next: null };
  return { prev: pages[i - 1] ?? null, next: pages[i + 1] ?? null };
}

export function docsTitle(path: string): string | null {
  return docsOrder().find((n) => n.path === path)?.title ?? null;
}
