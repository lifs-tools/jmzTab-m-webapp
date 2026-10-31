/*
 * Source scans: they keep the library free of the host. They read the library's own source tree from disk,
 * which needs node's fs; it is loaded at run time because the spec build has no node typings.
 */
interface Fs {
  readdirSync(dir: string, opts: { withFileTypes: true }): { name: string; isDirectory(): boolean }[];
  readFileSync(file: string, enc: 'utf8'): string;
  existsSync(path: string): boolean;
}
declare const process: { cwd(): string };

async function sourceFiles(ext: RegExp): Promise<{ path: string; text: string }[]> {
  const mod = 'node:fs';
  const fs = (await import(/* @vite-ignore */ mod)) as Fs;
  const cwd = process.cwd();
  const root = [`${cwd}/projects/mztabm-editor/src`, `${cwd}/frontend/projects/mztabm-editor/src`].find((p) => fs.existsSync(p));
  if (!root) throw new Error(`library source tree not found from ${cwd}`);
  const out: { path: string; text: string }[] = [];
  const walk = (dir: string) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const p = `${dir}/${e.name}`;
      if (e.isDirectory()) walk(p);
      else if (ext.test(e.name)) out.push({ path: p.slice(root.length + 1), text: fs.readFileSync(p, 'utf8') });
    }
  };
  walk(root);
  return out;
}

describe('library boundaries', () => {
  it('uses --mztabm tokens, not --lifs tokens, in its styles', async () => {
    const styles = await sourceFiles(/\.scss$/);
    expect(styles.length).toBeGreaterThan(3);
    const offenders = styles.filter((f) => !f.path.endsWith('_mztabm-tokens.scss') && f.text.includes('--lifs-'));
    expect(offenders.map((f) => f.path)).toEqual([]);
    // the tokens file is where the --lifs defaults live
    expect(styles.find((f) => f.path.endsWith('_mztabm-tokens.scss'))?.text).toContain('--mztabm-color-primary: var(--lifs-primary)');
    // and the components do use the library tokens
    expect(styles.some((f) => f.text.includes('var(--mztabm-color-primary)'))).toBe(true);
  });

  it('inlines no --lifs tokens in component templates or styles either', async () => {
    const ts = await sourceFiles(/\.(ts|html)$/);
    const offenders = ts.filter((f) => !f.path.endsWith('.spec.ts') && f.text.includes('--lifs-'));
    expect(offenders.map((f) => f.path)).toEqual([]);
  });

  it('imports nothing from projects/webapp', async () => {
    const files = await sourceFiles(/\.(ts|scss|html)$/);
    expect(files.length).toBeGreaterThan(5);
    const from = /(?:from\s+|import\s*\(\s*|@use\s+|@import\s+|@forward\s+)['"]([^'"]+)['"]/g;
    const offenders: string[] = [];
    for (const f of files.filter((x) => !x.path.endsWith('library-boundaries.spec.ts'))) {
      for (const m of f.text.matchAll(from)) {
        const target = m[1];
        if (/webapp/.test(target) || /\/api\/(models|services|fn)\//.test(target) || target.includes('core/http-error')) {
          offenders.push(`${f.path}: ${target}`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });
});
