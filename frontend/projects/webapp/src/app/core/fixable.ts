import { ValidationRecord } from '../api/models/validation-record';

/**
 * Whether a validation's file can be opened in the editor: once its result is final and it has something to
 * show, i.e. FINISHED, or FAILED with messages (the engine answered about the text). The server decides whether
 * the stored upload is readable as text when the copy is created.
 */
export function canFix(record: ValidationRecord | undefined): boolean {
  if (!record) return false;
  return record.status === 'FINISHED' || (record.status === 'FAILED' && (record.messages?.length ?? 0) > 0);
}
