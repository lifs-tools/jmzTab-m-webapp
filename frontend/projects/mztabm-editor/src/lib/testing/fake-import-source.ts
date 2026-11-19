/*
 * Copyright 2026 LIFS Tools Consortium.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { Observable } from 'rxjs';

import { ImportPreviewView, ImportRequestView, ImportSource, StillMissingView } from '../import-source';
import { Pending } from './fake-document-source';

/** An ImportSource that records every request; the test answers them one by one. */
export class FakeImportSource extends ImportSource {
  readonly kinds: Pending<void, string[]>[] = [];
  readonly previews: Pending<ImportRequestView, ImportPreviewView>[] = [];
  readonly applies: Pending<ImportPreviewView, 'applied' | 'stale'>[] = [];
  readonly missing: Pending<void, StillMissingView>[] = [];

  private record<A, T>(list: Pending<A, T>[], args: A): Observable<T> {
    return new Observable<T>((sub) => {
      const p = new Pending(args, sub);
      list.push(p);
      return () => p.release();
    });
  }

  override importKinds(): Observable<string[]> {
    return this.record(this.kinds, undefined);
  }
  override previewImport(req: ImportRequestView): Observable<ImportPreviewView> {
    return this.record(this.previews, req);
  }
  override applyImport(p: ImportPreviewView): Observable<'applied' | 'stale'> {
    return this.record(this.applies, p);
  }
  override stillMissing(): Observable<StillMissingView> {
    return this.record(this.missing, undefined);
  }

  private one<A, T>(list: Pending<A, T>[], what: string): Pending<A, T> {
    const found = list.filter((p) => !p.taken);
    if (found.length !== 1) throw new Error(`expected exactly one ${what} request, found ${found.length}`);
    found[0].taken = true;
    return found[0];
  }
  private none<A, T>(list: Pending<A, T>[], what: string): void {
    const found = list.filter((p) => !p.taken);
    if (found.length) throw new Error(`expected no ${what} request, found ${found.length}`);
  }

  oneKinds(): Pending<void, string[]> {
    return this.one(this.kinds, 'import kinds');
  }
  onePreview(): Pending<ImportRequestView, ImportPreviewView> {
    return this.one(this.previews, 'import preview');
  }
  nonePreview(): void {
    this.none(this.previews, 'import preview');
  }
  oneApply(): Pending<ImportPreviewView, 'applied' | 'stale'> {
    return this.one(this.applies, 'apply');
  }
  noneApply(): void {
    this.none(this.applies, 'apply');
  }
  oneMissing(): Pending<void, StillMissingView> {
    return this.one(this.missing, 'still missing');
  }
  noneMissing(): void {
    this.none(this.missing, 'still missing');
  }
}
