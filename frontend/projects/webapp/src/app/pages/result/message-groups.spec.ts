import { ValidationMessage } from '../../api/models/validation-message';
import { groupMessages, occurrenceLabel, targetOf, typeCode } from './message-groups';

function msg(over: Partial<ValidationMessage> = {}): ValidationMessage {
  return { category: 'format', code: '', message: 'Some message', message_type: 'error', line_number: -1, ...over };
}

describe('message-groups', () => {
  it('extracts the type code from jmzTab-m codes', () => {
    expect(typeCode(msg({ code: '[Error-2009] line 5: x\r\n' }))).toBe('Error-2009');
    expect(typeCode(msg({ code: '' }))).toBeNull();
  });

  it('groups by level, type code and rule id, errors first', () => {
    const g = groupMessages([
      msg({ message_type: 'info', code: '[Info-3010] line -1: a', rule_id: 'sample_species_may' }),
      msg({ message_type: 'error', code: '[Error-2009] line 5: b' }),
      msg({ message_type: 'info', code: '[Info-3010] line -1: c', rule_id: 'sample_species_may' }),
      msg({ message_type: 'info', code: '[Info-3010] line -1: d', rule_id: 'sample_tissue_may' }),
    ]);
    expect(g.map((x) => x.messages.length)).toEqual([1, 2, 1]);
    expect(g[0].level).toBe('error');
  });

  it('treats a missing message_type as info', () => {
    const [g] = groupMessages([msg({ message_type: undefined })]);
    expect(g.level).toBe('info');
  });

  it('uses the suggestion as headline and keeps the message as diagnosis', () => {
    const [g] = groupMessages([msg({ suggestion: 'Add the column.', message: 'x MUST be defined' })]);
    expect(g.headline).toBe('Add the column.');
    expect(g.diagnosis).toBe('x MUST be defined');
  });

  it('falls back to the message for old records', () => {
    const [g] = groupMessages([msg({ code: '[Warn-3005] line -1: old', message: 'old' })]);
    expect(g.headline).toBe('old');
    expect(g.diagnosis).toBeNull();
    expect(targetOf(g.messages[0])).toBeNull();
    expect(occurrenceLabel(g.messages[0])).toBe('—');
  });

  it('maps locations to tabs', () => {
    expect(targetOf(msg({ location: { section: 'mtd', metadata_key: 'sample[3]-species[1]', metadata_group: 'sample' } })))
      .toEqual({ tab: 'metadata', group: 'sample', key: 'sample[3]-species[1]' });
    expect(targetOf(msg({ location: { section: 'sml', row_index: 1, column: 'reliability' } })))
      .toEqual({ tab: 'summary', rowIndex: 1, column: 'reliability' });
    expect(targetOf(msg({ location: { section: 'smh', column: 'abundance_assay[1]' } })))
      .toEqual({ tab: 'summary', column: 'abundance_assay[1]', header: true });
    expect(targetOf(msg({ location: { section: 'com' } }))).toBeNull();
  });

  it('derives the metadata group from the key when none is given, and treats null as absent', () => {
    expect(targetOf(msg({ location: { section: 'mtd', metadata_key: 'sample[3]-species[1]', metadata_group: null } })))
      .toEqual({ tab: 'metadata', group: 'sample', key: 'sample[3]-species[1]' });
    expect(targetOf(msg({ location: { section: 'mtd', metadata_key: 'title' } })))
      .toEqual({ tab: 'metadata', group: 'general', key: 'title' });
    expect(targetOf(msg({ location: { section: 'mtd', metadata_group: 'sample' } })))
      .toEqual({ tab: 'metadata', group: 'sample' });
    expect(targetOf(msg({ location: { section: 'smf', row_index: 0, column: null } })))
      .toEqual({ tab: 'features', rowIndex: 0, column: undefined });
    expect(targetOf(msg({ location: { section: 'sme', column: 'x' } })))
      .toEqual({ tab: 'evidence', column: 'x', header: true });
    expect(targetOf(msg({ location: { section: 'sfh' } }))).toBeNull();
  });

  it('labels occurrences', () => {
    expect(occurrenceLabel(msg({ location: { section: 'sml', row_index: 1, column: 'reliability' } }))).toBe('SML row 2 · reliability');
    expect(occurrenceLabel(msg({ location: { section: 'mtd', metadata_group: 'sample' } }))).toBe('metadata group: sample');
    expect(occurrenceLabel(msg({ location: { section: 'mtd', metadata_key: 'sample[1]-description' } }))).toBe('sample[1]-description');
    expect(occurrenceLabel(msg({ location: { section: 'smh', column: 'abundance_assay[1]' } }))).toBe('SMH · abundance_assay[1]');
    expect(occurrenceLabel(msg({ line_number: 12 }))).toBe('line 12');
  });
});
