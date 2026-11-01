import { DOCS_HOME, docsNeighbours, docsOrder, docsTitle } from './docs-tree';

describe('docs tree', () => {
  it('orders home first, then the chapters', () => {
    expect(docsOrder().map((n) => n.path)).toEqual(['/docs', '/docs/fixing', '/docs/problems']);
  });

  it('gives neighbours in tree order', () => {
    const n = docsNeighbours('/docs/fixing');
    expect(n.prev).toBe(DOCS_HOME);
    expect(n.next?.path).toBe('/docs/problems');
    expect(docsNeighbours('/docs').prev).toBeNull();
    expect(docsNeighbours('/docs/problems').next).toBeNull();
  });

  it('gives no neighbours outside the tree', () => {
    expect(docsNeighbours('/docs/nothing')).toEqual({ prev: null, next: null });
  });

  it('titles a docs path', () => expect(docsTitle('/docs/problems')).toBe('Common problems'));

  it('has no title outside the tree', () => expect(docsTitle('/docs/nothing')).toBeNull());
});
