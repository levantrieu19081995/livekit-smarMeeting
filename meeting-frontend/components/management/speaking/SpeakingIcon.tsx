export function SpeakingIcon({ name }: { name: 'close' | 'refresh' | 'hand' }) {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {name === 'close' ? (
        <path d="m6 6 12 12M18 6 6 18" />
      ) : name === 'refresh' ? (
        <>
          <path d="M20 7v5h-5M4 17v-5h5" />
          <path d="M6.1 7a7 7 0 0 1 11.55-1L20 9M4 15l2.35 3A7 7 0 0 0 17.9 17" />
        </>
      ) : (
        <>
          <path d="M8 13V5a1.5 1.5 0 0 1 3 0v7M11 5V3a1.5 1.5 0 0 1 3 0v9M14 5a1.5 1.5 0 0 1 3 0v8M17 8a1.5 1.5 0 0 1 3 0v7a7 7 0 0 1-13 3l-3-5a1.7 1.7 0 0 1 2.8-1.9L8 13" />
        </>
      )}
    </svg>
  );
}
