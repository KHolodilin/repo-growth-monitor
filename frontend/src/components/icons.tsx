import logoUrl from "../assets/logo.png";

type IconProps = {
  className?: string;
};

export function LogoMark({ className }: IconProps) {
  return <img src={logoUrl} alt="" className={className} draggable={false} />;
}

export function DashboardIcon({ className }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <rect x="3.2" y="3.2" width="7.4" height="7.4" rx="1.6" stroke="currentColor" strokeWidth="1.7" />
      <rect x="13.4" y="3.2" width="7.4" height="7.4" rx="1.6" stroke="currentColor" strokeWidth="1.7" />
      <rect x="3.2" y="13.4" width="7.4" height="7.4" rx="1.6" stroke="currentColor" strokeWidth="1.7" />
      <rect x="13.4" y="13.4" width="7.4" height="7.4" rx="1.6" stroke="currentColor" strokeWidth="1.7" />
    </svg>
  );
}

export function RepositoriesIcon({ className }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <ellipse cx="12" cy="5.2" rx="7.6" ry="2.35" stroke="currentColor" strokeWidth="1.7" />
      <path
        d="M4.4 5.2v13.5c0 1.3 3.4 2.35 7.6 2.35s7.6-1.05 7.6-2.35V5.2"
        stroke="currentColor"
        strokeWidth="1.7"
      />
      <path d="M4.4 10.4c0 1.3 3.4 2.35 7.6 2.35s7.6-1.05 7.6-2.35" stroke="currentColor" strokeWidth="1.7" />
      <path d="M4.4 15.5c0 1.3 3.4 2.35 7.6 2.35s7.6-1.05 7.6-2.35" stroke="currentColor" strokeWidth="1.7" />
    </svg>
  );
}

export function SettingsIcon({ className }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <circle cx="12" cy="12" r="3.05" stroke="currentColor" strokeWidth="1.7" />
      <path
        d="M12 3.15 13.05 5.4a1.2 1.2 0 0 0 1.62.55l2.28-.9.9 2.28a1.2 1.2 0 0 0 .55 1.62L20.85 10.7 18.6 11.75a1.2 1.2 0 0 0-.55 1.62l.9 2.28-2.28.9a1.2 1.2 0 0 0-1.62.55L12 20.85 10.95 18.6a1.2 1.2 0 0 0-1.62-.55l-2.28.9-.9-2.28a1.2 1.2 0 0 0-.55-1.62L3.15 13.3 5.4 12.25a1.2 1.2 0 0 0 .55-1.62l-.9-2.28 2.28-.9a1.2 1.2 0 0 0 1.62-.55L12 3.15Z"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinejoin="round"
      />
    </svg>
  );
}
