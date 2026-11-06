/**
 * Declared mzTab-M version of a chosen file, found in the browser the way the server's
 * `MzTabVersionDetector` (engine-api) finds it, and its version line (`MzTabVersions.lineOf`).
 * The form uses it to offer the profiles of the file's line; the server's answer stays
 * authoritative.
 */

/** Bytes read per step; a file is never read as a whole. */
export const DETECTION_CHUNK_BYTES = 64 * 1024;

/** A tab separated file must declare its version within its first MiB. */
const TSV_LIMIT_BYTES = 1024 * 1024;

const LINE = /^\s*(\d+)\.(\d+)(\.|$)/;
const VERSION_PREFIX = 'MTD\tmzTab-version\t';
const BOM = '﻿';
/** Longest key or value kept while scanning JSON; longer ones cannot be the ones looked for. */
const MAX_CAPTURE = 256;

/**
 * The version line, `MAJOR.MINOR`, of a declared version: `2.0.0-M` -> `2.0`, `2.1` -> `2.1`.
 * Same rule as the server: `^\s*(\d+)\.(\d+)(\.|$)`.
 */
export function lineOf(version: string | null | undefined): string | null {
  if (version == null) return null;
  const m = LINE.exec(version);
  return m ? `${m[1]}.${m[2]}` : null;
}

/**
 * The declared `mzTab-version` of an mzTab-M file, or null if it declares none.
 *
 * - Tab separated: the first `MTD mzTab-version` line within the first MiB, as long as only
 *   `MTD`, `COM` and blank lines come before it. A byte order mark and CRLF are accepted.
 * - JSON (first significant character `{`): the `mzTab-version` of the top-level `metadata`
 *   object, read chunk by chunk, so `metadata` may come after the tables of a large file.
 */
export async function detectMzTabVersion(file: Blob): Promise<string | null> {
  const first = await firstSignificantChar(file);
  if (first === '{') return detectJson(file);
  if (first === null) return null;
  return detectTsv(file);
}

async function readChunk(file: Blob, offset: number, length: number): Promise<Uint8Array> {
  return new Uint8Array(await file.slice(offset, offset + length).arrayBuffer());
}

async function firstSignificantChar(file: Blob): Promise<string | null> {
  const decoder = new TextDecoder('utf-8');
  for (
    let offset = 0;
    offset < Math.min(file.size, TSV_LIMIT_BYTES);
    offset += DETECTION_CHUNK_BYTES
  ) {
    const text = decoder.decode(await readChunk(file, offset, DETECTION_CHUNK_BYTES), {
      stream: true,
    });
    for (const c of text) {
      if (c !== BOM && c.trim() !== '') return c;
    }
  }
  return null;
}

async function detectTsv(file: Blob): Promise<string | null> {
  const decoder = new TextDecoder('utf-8');
  const end = Math.min(file.size, TSV_LIMIT_BYTES);
  let pending = '';
  let first = true;
  for (let offset = 0; offset < end; offset += DETECTION_CHUNK_BYTES) {
    const length = Math.min(DETECTION_CHUNK_BYTES, end - offset);
    pending += decoder.decode(await readChunk(file, offset, length), { stream: true });
    const lines = pending.split('\n');
    pending = lines.pop() ?? '';
    for (const raw of lines) {
      const result = tsvLine(raw, first);
      first = false;
      if (result !== undefined) return result;
    }
  }
  // the last line counts only if the file ends within the limit (no cut-off line)
  if (file.size <= TSV_LIMIT_BYTES) {
    const result = tsvLine(pending + decoder.decode(), first);
    if (result !== undefined) return result;
  }
  return null;
}

/** The version (string), the end of the metadata block (null), or undefined to read on. */
function tsvLine(raw: string, first: boolean): string | null | undefined {
  let line = raw.endsWith('\r') ? raw.slice(0, -1) : raw;
  if (first && line.startsWith(BOM)) line = line.slice(BOM.length);
  if (line.startsWith(VERSION_PREFIX)) {
    const value = line.slice(VERSION_PREFIX.length).trim();
    const tab = value.indexOf('\t');
    return tab >= 0 ? value.slice(0, tab).trim() : value;
  }
  if (!isMetadataOrComment(line) && line.trim() !== '') return null;
  return undefined;
}

/** An `MTD` or `COM` line, also one without a tab (an empty comment). */
function isMetadataOrComment(line: string): boolean {
  if (line.startsWith('MTD\t') || line.startsWith('COM\t')) return true;
  const bare = line.trimEnd();
  return bare === 'MTD' || bare === 'COM';
}

async function detectJson(file: Blob): Promise<string | null> {
  const decoder = new TextDecoder('utf-8');
  const scanner = new MetadataVersionScanner();
  for (let offset = 0; offset < file.size; offset += DETECTION_CHUNK_BYTES) {
    const result = scanner.feed(
      decoder.decode(await readChunk(file, offset, DETECTION_CHUNK_BYTES), { stream: true }),
    );
    if (result !== undefined) return result;
  }
  return null;
}

const ESCAPES: Readonly<Record<string, string>> = { b: '\b', f: '\f', n: '\n', r: '\r', t: '\t' };

/**
 * A minimal streaming JSON scanner that keeps only the nesting stack and the strings it needs:
 * keys of the top-level object, keys of `metadata`, and the value of `metadata.mzTab-version`.
 * Malformed JSON ends in null (or undefined, "not decided yet") rather than an error.
 */
class MetadataVersionScanner {
  private readonly stack: ('{' | '[')[] = [];
  private started = false;
  private expectKey = false;
  private inMetadata = false;
  /** The key whose value comes next, at the levels that are tracked. */
  private key: string | null = null;
  private inString = false;
  private stringIsKey = false;
  private escape = false;
  private unicode: string | null = null;
  /** The string being read, or null if it is skipped. */
  private capture: string | null = null;

  /** @returns the version, null if there is none, or undefined to read on */
  feed(text: string): string | null | undefined {
    for (const c of text) {
      const result = this.inString ? this.stringChar(c) : this.structuralChar(c);
      if (result !== undefined) return result;
    }
    return undefined;
  }

  private stringChar(c: string): string | null | undefined {
    if (this.unicode !== null) {
      this.unicode += c;
      if (this.unicode.length === 4) {
        this.append(String.fromCharCode(parseInt(this.unicode, 16)));
        this.unicode = null;
      }
    } else if (this.escape) {
      this.escape = false;
      if (c === 'u') this.unicode = '';
      else this.append(ESCAPES[c] ?? c);
    } else if (c === '\\') {
      this.escape = true;
    } else if (c === '"') {
      this.inString = false;
      if (this.stringIsKey) {
        this.key = this.capture;
        this.expectKey = false;
      } else if (this.capture !== null) {
        return this.capture;
      } else if (this.atMetadataValue()) {
        return null;
      }
      this.capture = null;
    } else {
      this.append(c);
    }
    return undefined;
  }

  private append(c: string): void {
    if (this.capture !== null && this.capture.length < MAX_CAPTURE) this.capture += c;
  }

  private structuralChar(c: string): string | null | undefined {
    if (c === BOM || c.trim() === '') return undefined;
    if (!this.started) {
      if (c !== '{') return null;
      this.started = true;
      this.stack.push('{');
      this.expectKey = true;
      return undefined;
    }
    const depth = this.stack.length;
    const top = this.stack[depth - 1];
    switch (c) {
      case '{':
      case '[':
        if (this.atMetadataValue()) {
          if (c === '[') return null;
          this.inMetadata = true;
        }
        this.stack.push(c);
        this.expectKey = c === '{';
        this.key = null;
        return undefined;
      case '}':
      case ']':
        this.stack.pop();
        if (this.stack.length === 0) return null;
        if (this.stack.length === 1 && this.inMetadata) return null;
        this.expectKey = false;
        this.key = null;
        return undefined;
      case ',':
        this.expectKey = top === '{';
        this.key = null;
        return undefined;
      case ':':
        return undefined;
      case '"':
        this.inString = true;
        this.stringIsKey = top === '{' && this.expectKey;
        this.capture = this.wantsString() ? '' : null;
        return undefined;
      default:
        // a number, true, false or null
        return this.atMetadataValue() ? null : undefined;
    }
  }

  /** The value of the top-level `metadata` key comes next. */
  private atMetadataValue(): boolean {
    return this.stack.length === 1 && !this.expectKey && this.key === 'metadata';
  }

  private wantsString(): boolean {
    const depth = this.stack.length;
    if (this.stringIsKey) return depth === 1 || (depth === 2 && this.inMetadata);
    return depth === 2 && this.inMetadata && this.key === 'mzTab-version';
  }
}
