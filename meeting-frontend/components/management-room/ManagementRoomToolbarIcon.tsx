import type { ReactNode } from 'react';

const symbols = {
  speaking: (
    <>
      <path d="M8 13V5a1.5 1.5 0 0 1 3 0v7M11 5V3a1.5 1.5 0 0 1 3 0v9M14 5a1.5 1.5 0 0 1 3 0v8M17 8a1.5 1.5 0 0 1 3 0v7a7 7 0 0 1-13 3l-3-5a1.7 1.7 0 0 1 2.8-1.9L8 13" />
    </>
  ),
  poll: (
    <>
      <rect x="4" y="4" width="16" height="16" rx="2" />
      <path d="m8 12 3 3 5-6" />
    </>
  ),
  qna: (
    <>
      <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2Z" />
      <path d="M9.5 8a2.5 2.5 0 0 1 5 0c0 1.5-2.5 1.5-2.5 3M12 14h.01" />
    </>
  ),
  document: (
    <>
      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z" />
      <path d="M14 2v6h6M8 13h8M8 17h6" />
    </>
  ),
  endMeeting: (
    <>
      <path d="M12 3v9M6.5 5.5a9 9 0 1 0 11 0" />
    </>
  ),
  record: <circle cx="12" cy="12" r="6" fill="currentColor" stroke="none" />,
  stopRecording: (
    <rect x="6" y="6" width="12" height="12" rx="1" fill="currentColor" stroke="none" />
  ),
} satisfies Record<string, ReactNode>;

export function ManagementRoomToolbarIcon({
  name,
  className,
}: {
  name: keyof typeof symbols;
  className?: string;
}) {
  return (
    <svg
      className={className}
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {symbols[name]}
    </svg>
  );
}
