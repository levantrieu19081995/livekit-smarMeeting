import { describe, expect, it } from 'vitest';
import { canPreviewDocument, safeDownloadName } from './documentPreviewSafety';

describe('untrusted document previews', () => {
  it.each([
    'text/html',
    'application/xhtml+xml',
    'image/svg+xml',
    'application/javascript',
    'text/xml',
    'application/octet-stream',
  ])('does not open active/unknown content %s in the app origin', (mime) =>
    expect(canPreviewDocument(mime)).toBe(false),
  );
  it.each(['application/pdf', 'image/png', 'image/jpeg', 'text/plain; charset=UTF-8'])(
    'keeps safe preview %s available',
    (mime) => expect(canPreviewDocument(mime)).toBe(true),
  );
  it('removes path/control characters from downloaded names', () => {
    expect(safeDownloadName('../a\\b\u0000.svg')).toBe('.._a_b_.svg');
    expect(safeDownloadName('x'.repeat(300))).toHaveLength(255);
  });
});
