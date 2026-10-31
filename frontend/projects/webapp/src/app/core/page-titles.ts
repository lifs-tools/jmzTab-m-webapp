/** Fixed page titles per route; never the document title (it may name a file). */
export function pageTitle(path: string): string {
  const routes: Record<string, string> = {
    '/': 'Validate',
    '/examples': 'Examples',
    '/about': 'About',
    '/problems': 'Common problems and how to fix them in your tool',
  };
  if (path.startsWith('/result/') || path.startsWith('/validate/')) {
    return 'mzTab-M Validator · Result';
  }
  return `mzTab-M Validator · ${routes[path] ?? 'Not found'}`;
}
