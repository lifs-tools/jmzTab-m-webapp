import { ValidationMessage } from '../../api/models/validation-message';

export type Level = 'error' | 'warn' | 'info';
export type DocTab = 'metadata' | 'summary' | 'features' | 'evidence';

export interface DocTarget {
  tab: DocTab;
  group?: string;
  key?: string;
  rowIndex?: number;
  column?: string;
  header?: boolean;
}

export interface MessageGroup {
  key: string;
  level: Level;
  typeCode: string | null;
  ruleId: string | null;
  category: ValidationMessage['category'];
  /** The suggestion of the first message, else its message. */
  headline: string;
  /** The message of the first message when it has a suggestion. */
  diagnosis: string | null;
  messages: ValidationMessage[];
}

const LEVEL_ORDER: Level[] = ['error', 'warn', 'info'];

const TAB_OF: Record<string, DocTab> = {
  mtd: 'metadata',
  sml: 'summary',
  smh: 'summary',
  smf: 'features',
  sfh: 'features',
  sme: 'evidence',
  seh: 'evidence',
};
const ROW_SECTIONS = new Set(['sml', 'smf', 'sme']);
const HEADER_SECTIONS = new Set(['smh', 'sfh', 'seh']);

function levelOf(m: ValidationMessage): Level {
  return m.message_type ?? 'info';
}

/** "Error-2009" from "[Error-2009] line 5: …"; null for codes without the prefix. */
export function typeCode(m: ValidationMessage): string | null {
  return /^\s*\[([^\]]+)\]/.exec(m.code ?? '')?.[1] ?? null;
}

export function groupKey(m: ValidationMessage): string {
  return `${levelOf(m)}|${typeCode(m) ?? 'msg:' + m.message}|${m.rule_id ?? ''}`;
}

/** Groups by level, type code and rule id: errors, warnings, infos; within a level by first appearance. */
export function groupMessages(ms: readonly ValidationMessage[]): MessageGroup[] {
  const groups = new Map<string, MessageGroup>();
  for (const m of ms) {
    const key = groupKey(m);
    const existing = groups.get(key);
    if (existing) {
      existing.messages.push(m);
      continue;
    }
    const suggestion = m.suggestion || null;
    groups.set(key, {
      key,
      level: levelOf(m),
      typeCode: typeCode(m),
      ruleId: m.rule_id ?? null,
      category: m.category,
      headline: suggestion ?? m.message,
      diagnosis: suggestion ? m.message : null,
      messages: [m],
    });
  }
  const all = [...groups.values()];
  return LEVEL_ORDER.flatMap((level) => all.filter((g) => g.level === level));
}

/** The prefix before the first `[`, else `general`. */
function groupOf(key: string): string {
  const bracket = key.indexOf('[');
  return bracket > 0 ? key.slice(0, bracket) : 'general';
}

export function targetOf(m: ValidationMessage): DocTarget | null {
  const loc = m.location;
  const section = loc?.section;
  if (!loc || !section) return null;
  const tab = TAB_OF[section];
  if (!tab) return null;
  const column = loc.column ?? undefined;
  if (section === 'mtd') {
    if (loc.metadata_key) {
      return { tab, group: loc.metadata_group ?? groupOf(loc.metadata_key), key: loc.metadata_key };
    }
    return loc.metadata_group ? { tab, group: loc.metadata_group } : null;
  }
  if (ROW_SECTIONS.has(section) && loc.row_index != null) {
    return { tab, rowIndex: loc.row_index, column };
  }
  if ((HEADER_SECTIONS.has(section) || ROW_SECTIONS.has(section)) && column) {
    return { tab, column, header: true };
  }
  return null;
}

export function occurrenceLabel(m: ValidationMessage): string {
  const t = targetOf(m);
  if (t) {
    const section = m.location!.section!.toUpperCase();
    if (t.key) return t.key;
    if (t.rowIndex != null) {
      const row = `${section} row ${t.rowIndex + 1}`;
      return t.column ? `${row} · ${t.column}` : row;
    }
    if (t.header) return `${section} · ${t.column}`;
    return `metadata group: ${t.group}`;
  }
  return m.line_number != null && m.line_number > 0 ? `line ${m.line_number}` : '—';
}
