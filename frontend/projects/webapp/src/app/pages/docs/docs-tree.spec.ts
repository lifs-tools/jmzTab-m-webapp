import { docsNeighbours, docsOrder, docsTitle } from './docs-tree';

describe('docs tree', () => {
  it('orders home first, then the chapters (getting started, understanding mzTab-M with its pages, versions, validating, fixing, problems, integration, about)', () => {
    expect(docsOrder().map((n) => n.path)).toEqual([
      '/docs',
      '/docs/getting-started',
      '/docs/format',
      '/docs/format/metadata',
      '/docs/format/summary',
      '/docs/format/features',
      '/docs/format/evidence',
      '/docs/format/references',
      '/docs/format/cv-parameters',
      '/docs/format/study-design',
      '/docs/versions',
      '/docs/validating',
      '/docs/fixing',
      '/docs/problems',
      '/docs/integration',
      '/docs/about',
    ]);
  });

  it('gives neighbours in tree order', () => {
    const n = docsNeighbours('/docs/fixing');
    expect(n.prev?.path).toBe('/docs/validating');
    expect(n.next?.path).toBe('/docs/problems');
    expect(docsNeighbours('/docs').prev).toBeNull();
    expect(docsNeighbours('/docs/about').next).toBeNull();
  });

  it('gives the pages of a chapter as neighbours', () => {
    expect(docsNeighbours('/docs/getting-started').next?.path).toBe('/docs/format');
    expect(docsNeighbours('/docs/format').next?.path).toBe('/docs/format/metadata');
    expect(docsNeighbours('/docs/format/study-design').next?.path).toBe('/docs/versions');
  });

  it('puts "For tools and services" before "About this service"', () => {
    const n = docsNeighbours('/docs/integration');
    expect(n.prev?.path).toBe('/docs/problems');
    expect(n.next?.path).toBe('/docs/about');
    expect(docsTitle('/docs/integration')).toBe('For tools and services');
  });

  it('gives no neighbours outside the tree', () => {
    expect(docsNeighbours('/docs/nothing')).toEqual({ prev: null, next: null });
  });

  it('titles a docs path', () => expect(docsTitle('/docs/problems')).toBe('Common problems'));

  it('has no title outside the tree', () => expect(docsTitle('/docs/nothing')).toBeNull());
});
