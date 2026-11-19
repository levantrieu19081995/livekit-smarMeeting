const symbols = {
  settings: (
    <>
      <path d="m9.8 3-.6 2.4-2 .9-2.2-.7-2.2 3.8 1.7 1.7v2.4l-1.7 1.7 2.2 3.8 2.3-.7 2 .9.6 2.4h4.4l.6-2.4 2-.9 2.2.7 2.2-3.8-1.7-1.7v-2.4l1.7-1.7-2.2-3.8-2.3.7-2-.9-.6-2.4Z" />
      <circle cx="12" cy="12" r="3" />
    </>
  ),
  history: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </>
  ),
  download: <path d="M12 3v12m-4-4 4 4 4-4M4 16v5h16v-5" />,
  refresh: (
    <>
      <path d="M20 4v6h-6M4 20v-6h6" />
      <path d="M4.5 9a8 8 0 0 1 13-4L20 8M4 16l2.5 3a8 8 0 0 0 13-4" />
    </>
  ),
  send: <path d="m22 2-7 20-4-9-9-4ZM22 2 11 13" />,
  close: <path d="m6 6 12 12M18 6 6 18" />,
};

export function QnaIcon({ name }: { name: keyof typeof symbols }) {
  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {symbols[name]}
    </svg>
  );
}
