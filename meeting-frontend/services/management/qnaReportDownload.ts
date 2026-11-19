export function saveQnaReportFile(
  file: { blob: Blob; filename: string },
  canDownload: () => boolean,
): boolean {
  if (!canDownload()) return false;
  const objectUrl = URL.createObjectURL(file.blob);
  let anchor: HTMLAnchorElement | null = null;
  let downloadStarted = false;
  try {
    anchor = document.createElement('a');
    anchor.href = objectUrl;
    anchor.download = file.filename;
    document.body.appendChild(anchor);
    if (!canDownload()) return false;
    anchor.click();
    downloadStarted = true;
    return true;
  } finally {
    try {
      anchor?.remove();
    } finally {
      if (downloadStarted) {
        setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
      } else {
        URL.revokeObjectURL(objectUrl);
      }
    }
  }
}
