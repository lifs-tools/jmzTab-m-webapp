import { docsNeighbours, docsOrder, docsTitle } from './docs-tree';

describe('docs tree', () => {
  it('orders home first, then the chapters (getting started, versions, validating, fixing, problems, about)', () => {
    expect(docsOrder().map((n) => n.path)).toEqual([
      '/docs',
      '/docs/getting-started',
      '/docs/versions',
      '/docs/validating',
      '/docs/fixing',
      '/docs/problems',
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

  it('gives no neighbours outside the tree', () => {
    expect(docsNeighbours('/docs/nothing')).toEqual({ prev: null, next: null });
  });

  it('titles a docs path', () => expect(docsTitle('/docs/problems')).toBe('Common problems'));

  it('has no title outside the tree', () => expect(docsTitle('/docs/nothing')).toBeNull());
});
