/**
 * Test fixtures imported as raw bytes, e.g.
 * `import bytes from './version-detection/tsv-first.mztab' with { loader: 'binary' };`
 * TypeScript types a .json import by its content, so specs cast those to Uint8Array.
 * The binary loader keeps a byte order mark and CRLF line ends exactly as in the file.
 */
declare module '*.mztab' {
  const bytes: Uint8Array;
  export default bytes;
}
