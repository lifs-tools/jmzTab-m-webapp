/*
 * Public API of the mztabm-editor library: the document views and the DocumentSource a host provides to them.
 * The design tokens are in lib/styles/_mztabm-tokens.scss (not part of this TypeScript surface).
 */
export * from './lib/document-source';
export { ColumnPickerComponent, MAX_COLUMNS } from './lib/column-picker.component';
export { MetadataGroupComponent, MetadataViewComponent } from './lib/metadata-view.component';
export type { MetadataHighlight } from './lib/metadata-view.component';
export { TableViewComponent } from './lib/table-view.component';
export type { TableHighlight } from './lib/table-view.component';
export { RawLineEditorComponent } from './lib/raw-line-editor.component';
export { DuplicateColumnDialogComponent } from './lib/duplicate-column-dialog.component';
export type { DuplicateColumnData } from './lib/duplicate-column-dialog.component';
