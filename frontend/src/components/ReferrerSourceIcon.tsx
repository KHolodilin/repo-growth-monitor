type IconProps = { className?: string };

const SIZE = "h-4 w-4 shrink-0";

export function ReferrerSourceIcon({ source, className }: { source: string; className?: string }) {
  const name = source.toLowerCase();
  const cls = className ?? SIZE;
  if (name === "other") {
    return <OtherIcon className={cls} />;
  }
  if (name.includes("github")) {
    return <GitHubIcon className={cls} />;
  }
  if (name.includes("chatgpt") || name.includes("openai")) {
    return <ChatGptIcon className={cls} />;
  }
  if (name === "google" || name.includes("google.")) {
    return <GoogleIcon className={cls} />;
  }
  if (name.includes("reddit")) {
    return <RedditIcon className={cls} />;
  }
  if (name.includes("linkedin")) {
    return <LinkedInIcon className={cls} />;
  }
  if (name.includes("bing")) {
    return <BingIcon className={cls} />;
  }
  if (name.includes("stackoverflow") || name.includes("stack overflow")) {
    return <StackOverflowIcon className={cls} />;
  }
  if (name.includes("dev.to")) {
    return <DevToIcon className={cls} />;
  }
  if (name.includes("doubao")) {
    return <DoubaoIcon className={cls} />;
  }
  return <GlobeIcon className={cls} />;
}

function GitHubIcon({ className }: IconProps) {
  return (
    <svg viewBox="0 0 16 16" className={className} aria-hidden="true">
      <path
        fill="currentColor"
        d="M8 0a8 8 0 0 0-2.53 15.59c.4.07.55-.17.55-.38v-1.33c-2.23.48-2.7-1.07-2.7-1.07-.36-.92-.89-1.16-.89-1.16-.73-.5.06-.49.06-.49.8.06 1.22.83 1.22.83.72 1.22 1.89.87 2.35.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82a7.5 7.5 0 0 1 4 0c1.53-1.03 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.28.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48v2.2c0 .21.15.46.55.38A8 8 0 0 0 8 0Z"
      />
    </svg>
  );
}

function ChatGptIcon({ className }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" className={className} aria-hidden="true">
      <path
        fill="#10a37f"
        d="M22.28 9.66a5.47 5.47 0 0 0-.47-4.39 5.56 5.56 0 0 0-6-.24A5.5 5.5 0 0 0 10.18 2a5.54 5.54 0 0 0-5.27 3.83 5.47 5.47 0 0 0-3.72 2.65 5.55 5.55 0 0 0 .68 6.5 5.47 5.47 0 0 0 .47 4.39 5.56 5.56 0 0 0 6 .24A5.5 5.5 0 0 0 13.82 22a5.54 5.54 0 0 0 5.27-3.83 5.47 5.47 0 0 0 3.72-2.65 5.55 5.55 0 0 0-.53-5.86ZM13.82 20.3a4.12 4.12 0 0 1-2.64-1l.13-.07 4.47-2.58a.73.73 0 0 0 .36-.63v-6.3l1.89 1.1a.07.07 0 0 1 .04.06v5.25a4.14 4.14 0 0 1-4.25 4.17Zm-9.1-3.88a4.1 4.1 0 0 1-.5-2.76l.13.08 4.47 2.58c.22.13.5.13.72 0l5.45-3.15v2.18a.07.07 0 0 1-.03.06L9.6 18.53a4.14 4.14 0 0 1-4.88-2.11Zm-.84-8.77a4.1 4.1 0 0 1 2.16-1.87v5.3c0 .26.14.5.36.63l5.45 3.15-1.89 1.09a.07.07 0 0 1-.07 0L4.58 12.3a4.14 4.14 0 0 1-1.3-4.65Zm15.65 3.65-5.45-3.15 1.89-1.09a.07.07 0 0 1 .07 0l4.47 2.58a4.14 4.14 0 0 1-1.4 7.47v-5.3a.72.72 0 0 0-.36-.63Zm1.9-2.77-.13-.08-4.47-2.58a.73.73 0 0 0-.72 0L10.66 7.4V5.22a.07.07 0 0 1 .03-.06L15.4 2.47a4.14 4.14 0 0 1 6.03 4.28ZM8.26 13.05 6.37 11.96a.07.07 0 0 1-.04-.06V6.65a4.14 4.14 0 0 1 6.79-3.17l-.13.07-4.47 2.58a.73.73 0 0 0-.36.63Zm1.13-2.44L12 8.86l2.61 1.75v3.5L12 15.86l-2.61-1.75Z"
      />
    </svg>
  );
}

function GoogleIcon({ className }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" className={className} aria-hidden="true">
      <path fill="#4285F4" d="M23.5 12.3c0-.8-.1-1.6-.2-2.3H12v4.4h6.5c-.3 1.5-1.1 2.8-2.4 3.6v3h3.9c2.3-2.1 3.5-5.2 3.5-8.7Z" />
      <path fill="#34A853" d="M12 24c3.2 0 6-1.1 8-2.9l-3.9-3c-1.1.7-2.5 1.2-4.1 1.2-3.2 0-5.8-2.1-6.8-5H1.2v3.1C3.2 21.3 7.3 24 12 24Z" />
      <path fill="#FBBC05" d="M5.2 14.3c-.2-.7-.4-1.5-.4-2.3s.1-1.6.4-2.3V6.6H1.2C.4 8.3 0 10.1 0 12s.4 3.7 1.2 5.4l4-3.1Z" />
      <path fill="#EA4335" d="M12 4.8c1.8 0 3.3.6 4.6 1.8l3.4-3.4C18 1.1 15.2 0 12 0 7.3 0 3.2 2.7 1.2 6.6l4 3.1C6.2 6.9 8.8 4.8 12 4.8Z" />
    </svg>
  );
}

function RedditIcon({ className }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" className={className} aria-hidden="true">
      <circle cx="12" cy="12" r="12" fill="#FF4500" />
      <circle cx="8.8" cy="13.1" r="1.3" fill="#fff" />
      <circle cx="15.2" cy="13.1" r="1.3" fill="#fff" />
      <path fill="#fff" d="M12 18.4c-2.3 0-3.8-1-3.8-1s.8 1.8 3.8 1.8 3.8-1.8 3.8-1.8-1.5 1-3.8 1Z" />
    </svg>
  );
}

function LinkedInIcon({ className }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" className={className} aria-hidden="true">
      <rect width="24" height="24" rx="3" fill="#0A66C2" />
      <path fill="#fff" d="M7.1 9.4H4.7V19h2.4V9.4ZM5.9 5A1.4 1.4 0 1 0 5.9 7.8 1.4 1.4 0 0 0 5.9 5ZM19.3 19h-2.4v-4.7c0-1.1 0-2.6-1.6-2.6s-1.8 1.2-1.8 2.5V19H11V9.4h2.3v1.3h.1c.3-.6 1.1-1.3 2.3-1.3 2.5 0 3 1.6 3 3.8V19Z" />
    </svg>
  );
}

function BingIcon({ className }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" className={className} aria-hidden="true">
      <path fill="#258FFA" d="M6.2 3 9 4.4v12.1l4.4-2.5-2.2-1.1-1.4-3.5 8 2.8v3.7L9 21 6.2 19.4V3Z" />
    </svg>
  );
}

function StackOverflowIcon({ className }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" className={className} aria-hidden="true">
      <path fill="#BCBBBB" d="M5.7 17.3h12.1v2H5.7Z" />
      <path fill="#F48024" d="m7.1 16.2 10.6 2.2.4-2-10.6-2.2Zm1.4-4.6 9.8 4.6.9-1.9-9.8-4.6Zm2.7-4.4 8.3 6.9 1.3-1.6-8.3-6.9Zm5.3-4.7-1.7 1.2 6.5 9.1 1.7-1.2ZM7 20.6h12.8v-5.3h-2v3.3H9V15.3H7v5.3Z" />
    </svg>
  );
}

function DevToIcon({ className }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" className={className} aria-hidden="true">
      <rect width="24" height="24" rx="4" fill="#0A0A0A" />
      <path fill="#fff" d="M7.2 8.4H5.1v7.2h2.1c1.4 0 2.2-.8 2.2-2.1v-3c0-1.3-.8-2.1-2.2-2.1Zm.5 5.1c0 .4-.2.6-.6.6H7v-3.6h.1c.4 0 .6.2.6.6v2.4ZM12.4 8.4h-1.8v7.2h1.7l.1-2.6.7 2.3h.8l.7-2.3v2.6h1.7V8.4h-1.8l-.9 3.4-.8-3.4Zm6.5 1.3h-1.8V8.4h5.1v1.3h-1.8v5.9h-1.5V9.7Z" />
    </svg>
  );
}

function DoubaoIcon({ className }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" className={className} aria-hidden="true">
      <circle cx="12" cy="12" r="10" fill="#3B82F6" />
      <path fill="#fff" d="M8 8.5h8v2H8zm0 5h8v2H8z" />
    </svg>
  );
}

function OtherIcon({ className }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" className={className} aria-hidden="true">
      <circle cx="6" cy="12" r="2" fill="#94a3b8" />
      <circle cx="12" cy="12" r="2" fill="#94a3b8" />
      <circle cx="18" cy="12" r="2" fill="#94a3b8" />
    </svg>
  );
}

function GlobeIcon({ className }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <circle cx="12" cy="12" r="9" stroke="#64748b" strokeWidth="1.6" />
      <path d="M3 12h18M12 3c2.6 2.8 4 5.8 4 9s-1.4 6.2-4 9c-2.6-2.8-4-5.8-4-9s1.4-6.2 4-9Z" stroke="#64748b" strokeWidth="1.6" />
    </svg>
  );
}

export function referrerLineColor(source: string, index: number) {
  const known: Record<string, string> = {
    "github.com": "#2563eb",
    "chatgpt.com": "#7c3aed",
    Google: "#16a34a",
    "reddit.com": "#ef4444",
    Other: "#94a3b8",
  };
  if (known[source]) {
    return known[source];
  }
  const palette = ["#0ea5e9", "#f59e0b", "#ec4899", "#14b8a6", "#8b5cf6", "#f97316"];
  return palette[index % palette.length];
}
