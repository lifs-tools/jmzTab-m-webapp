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
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/** "Starting a new file": the three choices, the skeleton, "still missing", and adding from a vocabulary file. */
@Component({
  selector: 'app-new-file-docs-page',
  imports: [RouterLink],
  templateUrl: './new-file.page.html',
  styleUrl: './new-file.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NewFileDocsPage {}
