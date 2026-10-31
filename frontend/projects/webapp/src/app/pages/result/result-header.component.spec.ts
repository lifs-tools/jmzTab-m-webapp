import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatTooltip } from '@angular/material/tooltip';
import { By } from '@angular/platform-browser';

import { ValidationRecord } from '../../api/models/validation-record';
import { ResultHeaderComponent } from './result-header.component';

const ENGINE = 'mzTab-M 2.1 · jmzTab-m 2.1.0 (build 2026-10-30T12:00:00Z)';
const RECORD: ValidationRecord = {
  id: 'abc',
  fileName: 'profile-mf.mzTab',
  status: 'FINISHED',
  outcome: 'PASSED',
  level: 'INFO',
  maxErrors: 100,
  mzTabVersion: '2.1.0-M',
  engine: ENGINE,
  defaultVersionApplied: false,
  validationProfile: { kind: 'CATALOG', id: 'mztab-m-2.1-default', version: '1.0.0' },
};

describe('ResultHeaderComponent', () => {
  let fixture: ComponentFixture<ResultHeaderComponent>;

  async function render(record: ValidationRecord) {
    await TestBed.configureTestingModule({ imports: [ResultHeaderComponent] }).compileComponents();
    fixture = TestBed.createComponent(ResultHeaderComponent);
    fixture.componentRef.setInput('record', record);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const badges = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('.lifs-badge')).map((b) => b.textContent?.trim());
  const tooltipOf = (testid: string) =>
    fixture.debugElement.query(By.css(`[data-testid=${testid}]`))?.injector.get(MatTooltip).message;

  it('shows the engine label as tooltip and the default-version badge', async () => {
    const el = await render({ ...RECORD, defaultVersionApplied: true, mzTabVersion: '2.0.0-M' });
    expect(badges(el)).toEqual([
      'mzTab-M 2.0.0-M',
      'default version',
      'mztab-m-2.1-default · v1.0.0',
      'level Info · max 100',
    ]);
    expect(tooltipOf('version-badge')).toBe(ENGINE);
    const badge = el.querySelector<HTMLElement>('[data-testid=version-badge]')!;
    expect(badge.getAttribute('tabindex')).toBe('0');
  });

  it('shows no default-version badge when the file declares its version', async () => {
    const el = await render(RECORD);
    expect(badges(el)).not.toContain('default version');
    expect(tooltipOf('version-badge')).toBe(ENGINE);
  });

  it('puts the engine label on the default-version badge when no version is known', async () => {
    const el = await render({ ...RECORD, mzTabVersion: undefined, defaultVersionApplied: true });
    expect(el.querySelector('[data-testid=version-badge]')).toBeNull();
    expect(badges(el)[0]).toBe('default version');
    expect(tooltipOf('default-version-badge')).toBe(ENGINE);
  });

  it('has no tooltip for records without an engine', async () => {
    const el = await render({ ...RECORD, engine: null });
    expect(tooltipOf('version-badge')).toBe('');
    expect(el.querySelector('[data-testid=version-badge]')!.hasAttribute('tabindex')).toBe(false);
  });

  it('links the resolution report download', async () => {
    const el = await render(RECORD);
    const link = el.querySelector<HTMLAnchorElement>('[data-testid=resolution-report]')!;
    expect(link.textContent).toContain('Download resolution report');
    expect(link.getAttribute('href')).toBe('rest/v2/validations/abc/report?format=md');
  });

  it('offers no resolution report while the validation is running', async () => {
    const el = await render({ ...RECORD, status: 'RUNNING' });
    expect(el.querySelector('[data-testid=resolution-report]')).toBeNull();
  });
});
