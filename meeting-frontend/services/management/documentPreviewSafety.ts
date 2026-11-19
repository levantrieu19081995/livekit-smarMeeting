// Opening a same-origin blob as HTML/SVG would let a document execute app scripts.
const inlineTypes = new Set([
  'application/pdf',
  'image/png',
  'image/jpeg',
  'image/gif',
  'image/webp',
  'image/bmp',
  'image/avif',
  'text/plain',
]);
export function canPreviewDocument(type: string) {
  return inlineTypes.has(type.split(';', 1)[0].trim().toLowerCase());
}
export function safeDownloadName(name: string) {
  return name.replace(/[\u0000-\u001f\u007f/\\]/g, '_').slice(0, 255) || 'document';
}
