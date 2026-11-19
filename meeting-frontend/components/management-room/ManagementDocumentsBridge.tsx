'use client';

import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { ManagementRoomToolbarIcon } from '@/components/management-room/ManagementRoomToolbarIcon';
import toast from 'react-hot-toast';
import { uploadMeetingDocument } from '@/services/management/managementDocumentService';
import styles from '@/styles/management/ManagementRoom.module.css';

interface ManagementDocumentsBridgeProps {
  meetingId: string;
  roomRoot: HTMLDivElement | null;
  active: boolean;
  onToggleDocuments: () => void;
  onUploaded: () => void;
}

export function ManagementDocumentsBridge({
  meetingId,
  roomRoot,
  active,
  onToggleDocuments,
  onUploaded,
}: ManagementDocumentsBridgeProps) {
  const chatInputRef = useRef<HTMLInputElement>(null);
  const [controlMount, setControlMount] = useState<HTMLElement | null>(null);
  const [chatMount, setChatMount] = useState<HTMLElement | null>(null);
  const [uploading, setUploading] = useState(false);

  useEffect(() => {
    if (!roomRoot) return;
    const root = roomRoot;

    function ensureMount(parent: Element, id: string, before?: Element | null) {
      let mount = parent.querySelector<HTMLElement>(`[data-management-documents-mount="${id}"]`);
      if (!mount) {
        mount = window.document.createElement('span');
        mount.dataset.managementDocumentsMount = id;
        if (before) parent.insertBefore(mount, before);
        else parent.appendChild(mount);
      }
      return mount;
    }

    function syncTargets() {
      const controlBar = root.querySelector('.lk-control-bar');
      const chatButton =
        controlBar?.querySelector('[aria-label*="chat" i]') ??
        controlBar?.querySelector('.lk-chat-toggle');
      setControlMount(controlBar ? ensureMount(controlBar, 'toolbar', chatButton) : null);

      const chatForm = root.querySelector('.lk-chat-form');
      const submitButton = chatForm?.querySelector('button[type="submit"]');
      setChatMount(chatForm ? ensureMount(chatForm, 'chat', submitButton) : null);
    }

    syncTargets();
    const observer = new MutationObserver(syncTargets);
    observer.observe(root, { childList: true, subtree: true });
    return () => {
      observer.disconnect();
      root.querySelectorAll('[data-management-documents-mount]').forEach((node) => node.remove());
    };
  }, [roomRoot]);

  useEffect(() => {
    if (!roomRoot || !active) return;

    function handleClick(event: MouseEvent) {
      const target = event.target as HTMLElement | null;
      const chatButton = target?.closest('[aria-label*="chat" i], .lk-chat-toggle');
      if (chatButton) onToggleDocuments();
    }

    roomRoot.addEventListener('click', handleClick, true);
    return () => roomRoot.removeEventListener('click', handleClick, true);
  }, [active, onToggleDocuments, roomRoot]);

  async function uploadFiles(files: File[]) {
    if (files.length === 0) return;
    setUploading(true);
    const results = await Promise.allSettled(
      files.map((file) => uploadMeetingDocument(meetingId, file)),
    );
    const successCount = results.filter((result) => result.status === 'fulfilled').length;
    if (successCount > 0) {
      toast.success('Đã tải tài liệu lên cuộc họp.');
      onUploaded();
    }
    if (successCount < results.length) {
      toast.error('Một số tài liệu tải lên không thành công.');
    }
    setUploading(false);
  }

  function handleDocumentsClick() {
    if (!active) {
      const chatButton = controlMount?.parentElement?.querySelector<HTMLButtonElement>(
        '[aria-label*="chat" i], .lk-chat-toggle',
      );
      const chatPressed = chatButton?.getAttribute('aria-pressed') === 'true';
      if (chatPressed) chatButton?.click();
    }
    onToggleDocuments();
  }

  const documentsButton = controlMount
    ? createPortal(
        <>
          <button
            type="button"
            className={`lk-button ${styles.roomToolbarButton}`}
            aria-label="Tài liệu"
            title="Tài liệu"
            onClick={handleDocumentsClick}
            aria-pressed={active}
          >
            <ManagementRoomToolbarIcon name="document" />
            <span className={styles.roomToolbarLabel}>Tài liệu</span>
          </button>
        </>,
        controlMount,
      )
    : null;

  const attachmentButton = chatMount
    ? createPortal(
        <>
          <button
            type="button"
            className={styles.chatAttachmentButton}
            disabled={uploading}
            onClick={() => chatInputRef.current?.click()}
            aria-label="Đính kèm tài liệu"
            title="Đính kèm tài liệu"
          >
            📎
          </button>
          <input
            ref={chatInputRef}
            type="file"
            multiple
            className={styles.hiddenFileInput}
            onChange={(event) => {
              const files = Array.from(event.target.files ?? []);
              event.target.value = '';
              void uploadFiles(files);
            }}
          />
        </>,
        chatMount,
      )
    : null;

  return (
    <>
      {documentsButton}
      {attachmentButton}
    </>
  );
}
