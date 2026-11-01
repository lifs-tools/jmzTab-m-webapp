import { docsTitle } from '../pages/docs/docs-tree';

/** Fixed page titles per route; never the document title (it may name a file). */
export function pageTitle(path: string): string {
  const routes: Record<string, string> = {
    '/': 'Validate',
    '/examples': 'Examples',
  };
  if (path.startsWith('/docs')) {
    return `mzTab-M Validator · ${docsTitle(path) ?? 'Not found'}`;
  }
  if (path.startsWith('/result/') || path.startsWith('/validate/')) {
    return 'mzTab-M Validator · Result';
  }
  if (path.startsWith('/edit/')) {
    return 'mzTab-M Validator · Fix a file';
  }
  return `mzTab-M Validator · ${routes[path] ?? 'Not found'}`;
}
