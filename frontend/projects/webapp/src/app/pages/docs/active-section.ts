/**
 * The section in view: the last one whose heading has scrolled up to `threshold` (px from the top of the
 * viewport), else the first one, else null.
 */
export function activeSection(
  sections: readonly { id: string; top: number }[],
  threshold: number,
): string | null {
  const passed = sections.filter((s) => s.top <= threshold);
  return (passed.length ? passed[passed.length - 1] : sections[0])?.id ?? null;
}
