/** The pages of Understanding mzTab-M: the route segment ('' for the chapter's own page) and the tour chapter it shows. */
export const FORMAT_PAGES: readonly string[] = [
  '',
  'metadata',
  'summary',
  'features',
  'evidence',
  'references',
  'cv-parameters',
  'study-design',
];

/** The route data of a page of the chapter: the tour chapter and the teaching file its excerpts come from. */
export function formatRouteData(segment: string): { chapter: string; exampleId: string } {
  return {
    chapter: segment || 'overview',
    exampleId:
      segment === 'study-design' ? 'study-design-2.1.mztab' : 'teaching-lipidomics-2.0.mztab',
  };
}
