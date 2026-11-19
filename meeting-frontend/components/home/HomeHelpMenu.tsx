'use client';

import type { ReactNode } from 'react';
import { useEffect, useRef, useState } from 'react';
import styles from '@/styles/Home.module.css';
import { HomeDocumentViewer, type HelpDocument } from './HomeDocumentViewer';

type HelpFilesResponse = {
  files?: HelpDocument[];
};

type HomeHelpMenuProps = {
  icon: ReactNode;
};

function getDisplayName(filename: string) {
  return filename.replace(/\.(pdf|docx)$/i, '').replace(/[-_]+/g, ' ');
}

function DocumentIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M14 2H7a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7Z" />
      <path d="M14 2v5h5" />
      <path d="M9 13h6" />
      <path d="M9 17h4" />
    </svg>
  );
}

export function HomeHelpMenu({ icon }: HomeHelpMenuProps) {
  const menuRef = useRef<HTMLDivElement | null>(null);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [files, setFiles] = useState<HelpDocument[]>([]);
  const [error, setError] = useState(false);
  const [activeDocument, setActiveDocument] = useState<HelpDocument | null>(null);

  useEffect(() => {
    if (!open || loaded) {
      return;
    }

    let cancelled = false;

    async function loadFiles() {
      setLoading(true);
      setError(false);

      try {
        const response = await fetch('/api/help-files');

        if (!response.ok) {
          throw new Error(`Unable to load help files: ${response.status}`);
        }

        const payload = (await response.json()) as HelpFilesResponse;

        if (!cancelled) {
          setFiles(Array.isArray(payload.files) ? payload.files : []);
          setLoaded(true);
        }
      } catch {
        if (!cancelled) {
          setFiles([]);
          setError(true);
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }

    void loadFiles();

    return () => {
      cancelled = true;
    };
  }, [open, loaded]);

  useEffect(() => {
    if (!open) {
      return;
    }

    function handlePointerDown(event: PointerEvent) {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setOpen(false);
      }
    }

    document.addEventListener('pointerdown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);

    return () => {
      document.removeEventListener('pointerdown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [open]);

  function selectDocument(file: HelpDocument) {
    setOpen(false);
    setActiveDocument(file);
  }

  return (
    <>
      <div ref={menuRef} className={styles.helpMenuWrap}>
        <button
          className={`${styles.helpText} ${styles.helpButton}`}
          type="button"
          aria-haspopup="menu"
          aria-expanded={open}
          onClick={() => setOpen((current) => !current)}
        >
          {icon}
          Trợ giúp
        </button>

        {open ? (
          <div className={styles.helpDropdown} role="menu" aria-label="Tài liệu trợ giúp">
            {loading ? <p className={styles.helpMenuState}>Đang tải tài liệu...</p> : null}
            {!loading && error ? (
              <p className={styles.helpMenuState}>Không thể tải danh sách tài liệu.</p>
            ) : null}
            {!loading && !error && files.length === 0 ? (
              <p className={styles.helpMenuState}>Chưa có tài liệu trợ giúp.</p>
            ) : null}
            {!loading && !error
              ? files.map((file) => (
                  <button
                    key={file.url}
                    className={styles.helpDocumentItem}
                    type="button"
                    role="menuitem"
                    onClick={() => selectDocument(file)}
                  >
                    <span className={styles.helpDocumentIcon}>
                      <DocumentIcon />
                    </span>
                    <span className={styles.helpDocumentName}>{getDisplayName(file.name)}</span>
                    <span className={styles.helpDocumentType}>
                      {file.type === 'pdf' ? 'PDF' : 'Word'}
                    </span>
                  </button>
                ))
              : null}
          </div>
        ) : null}
      </div>

      {activeDocument ? (
        <HomeDocumentViewer file={activeDocument} onClose={() => setActiveDocument(null)} />
      ) : null}
    </>
  );
}
