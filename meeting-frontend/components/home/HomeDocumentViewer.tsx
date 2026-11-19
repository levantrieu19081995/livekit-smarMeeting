'use client';

import { useEffect, useRef, useState } from 'react';
import styles from '@/styles/Home.module.css';

export type HelpDocument = {
  name: string;
  type: 'pdf' | 'docx';
  url: string;
};

type HomeDocumentViewerProps = {
  file: HelpDocument;
  onClose: () => void;
};

export function HomeDocumentViewer({ file, onClose }: HomeDocumentViewerProps) {
  const closeButtonRef = useRef<HTMLButtonElement | null>(null);
  const docxContainerRef = useRef<HTMLDivElement | null>(null);
  const [docxState, setDocxState] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle');

  useEffect(() => {
    closeButtonRef.current?.focus();
  }, [file]);

  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onClose();
      }
    }

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  useEffect(() => {
    if (file.type !== 'docx' || !docxContainerRef.current) {
      return;
    }

    const container = docxContainerRef.current;
    let cancelled = false;

    async function renderDocx() {
      setDocxState('loading');
      container.replaceChildren();

      try {
        const [{ renderAsync }, response] = await Promise.all([
          import('docx-preview'),
          fetch(file.url),
        ]);

        if (!response.ok) {
          throw new Error(`Unable to load DOCX: ${response.status}`);
        }

        const buffer = await response.arrayBuffer();

        if (cancelled) {
          return;
        }

        await renderAsync(buffer, container, undefined, {
          className: styles.docxRendered,
          inWrapper: false,
        });

        if (!cancelled) {
          setDocxState('ready');
        }
      } catch {
        if (!cancelled) {
          container.replaceChildren();
          setDocxState('error');
        }
      }
    }

    void renderDocx();

    return () => {
      cancelled = true;
      container.replaceChildren();
    };
  }, [file]);

  return (
    <div className={styles.viewerOverlay} role="presentation">
      <section
        className={styles.viewerPanel}
        role="dialog"
        aria-modal="true"
        aria-labelledby="home-help-viewer-title"
      >
        <header className={styles.viewerHeader}>
          <h2 id="home-help-viewer-title">{file.name}</h2>
          <button
            ref={closeButtonRef}
            className={styles.viewerCloseButton}
            type="button"
            onClick={onClose}
            aria-label="Đóng tài liệu"
          >
            ×
          </button>
        </header>

        <div className={styles.viewerBody}>
          {file.type === 'pdf' ? (
            <iframe className={styles.pdfFrame} src={file.url} title={file.name} />
          ) : (
            <div className={styles.docxViewer}>
              {docxState === 'loading' ? (
                <p className={styles.viewerStatus}>Đang tải tài liệu...</p>
              ) : null}
              {docxState === 'error' ? (
                <p className={styles.viewerStatus}>Không thể hiển thị tài liệu này.</p>
              ) : null}
              <div ref={docxContainerRef} className={styles.docxContent} />
            </div>
          )}
        </div>
      </section>
    </div>
  );
}
