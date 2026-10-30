import jsonMetadataFirst from '../../testing/version-detection/json-metadata-first.json' with {
  loader: 'binary',
};
import jsonMetadataLast from '../../testing/version-detection/json-metadata-last.json' with {
  loader: 'binary',
};
import jsonMissing from '../../testing/version-detection/json-missing.json' with {
  loader: 'binary',
};
import tsvBomCrlf from '../../testing/version-detection/tsv-bom-crlf.mztab' with {
  loader: 'binary',
};
import tsvFirst from '../../testing/version-detection/tsv-first.mztab' with { loader: 'binary' };
import tsvLegacy from '../../testing/version-detection/tsv-legacy.mztab' with { loader: 'binary' };
import tsvMissing from '../../testing/version-detection/tsv-missing.mztab' with {
  loader: 'binary',
};
import { DETECTION_CHUNK_BYTES, detectMzTabVersion, lineOf } from './mztab-version';

// The same fixtures as engine-api's MzTabVersionDetectorTest (src/test/resources/version-detection/).
// The binary loader yields bytes; TypeScript still types a .json import by its content.
const blob = (bytes: Uint8Array | string | object) => new Blob([bytes as BlobPart]);

describe('detectMzTabVersion', () => {
  it('keeps the fixture bytes as they are', () => {
    expect(Array.from(tsvBomCrlf.slice(0, 3))).toEqual([0xef, 0xbb, 0xbf]);
    expect(new TextDecoder().decode(tsvBomCrlf)).toContain('\r\n');
  });

  it('detects the TSV version', async () => {
    expect(await detectMzTabVersion(blob(tsvFirst))).toBe('2.0.0-M');
  });

  it('detects the TSV version after a BOM with CRLF', async () => {
    expect(await detectMzTabVersion(blob(tsvBomCrlf))).toBe('2.1.0-M');
  });

  it('detects a legacy TSV version', async () => {
    expect(await detectMzTabVersion(blob(tsvLegacy))).toBe('1.0.0');
  });

  it('detects the JSON version when metadata comes first', async () => {
    expect(await detectMzTabVersion(blob(jsonMetadataFirst))).toBe('2.1.0-M');
  });

  it('detects the JSON version when metadata comes last', async () => {
    expect(await detectMzTabVersion(blob(jsonMetadataLast))).toBe('2.0.0-M');
  });

  it('returns null without a version', async () => {
    expect(await detectMzTabVersion(blob(tsvMissing))).toBeNull();
    expect(await detectMzTabVersion(blob(jsonMissing))).toBeNull();
    expect(await detectMzTabVersion(blob(''))).toBeNull();
    expect(await detectMzTabVersion(blob(new Uint8Array([0xff, 0xfe, 0x00])))).toBeNull();
  });

  it('skips bare comment lines and blank lines before the version', async () => {
    const text =
      'COM\tExample\nCOM\nCOM\r\nMTD\nCOM\tx\n\nMTD\tmzTab-version\t2.1.0-M\nMTD\tmzTab-ID\ta\n';
    expect(await detectMzTabVersion(blob(text))).toBe('2.1.0-M');
  });

  it('trims the TSV value and ignores further columns', async () => {
    expect(
      await detectMzTabVersion(blob('MTD\tmzTab-version\t2.0.0-M \t\nMTD\tmzTab-ID\ta\n')),
    ).toBe('2.0.0-M');
  });

  it('reads only until the metadata block ends', async () => {
    const text = 'COM\tx\nMTD\tmzTab-ID\ta\nSMH\tx\nMTD\tmzTab-version\t2.0.0-M\n';
    expect(await detectMzTabVersion(blob(text))).toBeNull();
  });

  it('reads at most the first MiB of a TSV file', async () => {
    const filler = 'COM\t' + 'x'.repeat(1000) + '\n';
    const head = filler.repeat(Math.ceil(1048576 / filler.length));
    expect(await detectMzTabVersion(blob(head + 'MTD\tmzTab-version\t2.0.0-M\n'))).toBeNull();
    expect(await detectMzTabVersion(blob('MTD\tmzTab-version\t2.0.0-M\n' + head))).toBe('2.0.0-M');
  });

  it('finds the version of a TSV line split across reads', async () => {
    const pad = 'COM\t' + 'x'.repeat(DETECTION_CHUNK_BYTES - 10) + '\n';
    expect(await detectMzTabVersion(blob(pad + 'MTD\tmzTab-version\t2.1.0-M\n'))).toBe('2.1.0-M');
  });

  it('ignores an mzTab-version key outside the top-level metadata object', async () => {
    const json = '{"comment":[{"mzTab-version":"9.9"}],"metadata":{"mzTab-version":"2.0.0-M"}}';
    expect(await detectMzTabVersion(blob(json))).toBe('2.0.0-M');
    expect(await detectMzTabVersion(blob('{"metadata":{"x":{"mzTab-version":"9.9"}}}'))).toBeNull();
  });

  it('handles escapes, whitespace and a leading BOM in JSON', async () => {
    const json =
      '﻿ \n {"a":"\\"metadata\\"","metadata" : { "b" : "x\\\\", "mzTab-version" : "2.1.0-M" }}';
    expect(await detectMzTabVersion(blob(json))).toBe('2.1.0-M');
  });

  it('streams a large JSON file whose metadata comes last', async () => {
    const row =
      '{"smlId":1,"databaseIdentifier":"CHEBI:1-padding-padding","chemicalName":"a lipid species"},';
    const rows = row.repeat(Math.ceil((3 * DETECTION_CHUNK_BYTES) / row.length));
    const json = `{"smallMoleculeSummary":[${rows}{}],"metadata":{"mzTab-version":"2.1.0-M"}}`;
    expect(await detectMzTabVersion(blob(json))).toBe('2.1.0-M');
  });
});

describe('lineOf', () => {
  it('maps real-world spellings to lines', () => {
    const table: [string, string][] = [
      ['2.0.0-M', '2.0'],
      ['2.0.0-M ', '2.0'],
      ['2.1.0-m', '2.1'],
      ['2.1', '2.1'],
      ['2.0.1-M', '2.0'],
      ['2.2.0-M', '2.2'],
    ];
    for (const [declared, line] of table) {
      expect(lineOf(declared), declared).toBe(line);
    }
  });

  it('rejects garbage', () => {
    expect(lineOf('M-2')).toBeNull();
    expect(lineOf('2.10x')).toBeNull();
    expect(lineOf(null)).toBeNull();
  });
});
