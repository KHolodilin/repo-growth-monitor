import { Link } from "react-router-dom";

export type BreadcrumbItem = {
  label: string;
  to?: string;
};

export function PageBreadcrumb({ items }: { items: BreadcrumbItem[] }) {
  return (
    <nav className="text-sm" aria-label="Breadcrumb">
      {items.map((item, index) => (
        <span key={`${item.label}-${index}`}>
          {index > 0 && (
            <span className="mx-2 text-muted-foreground" aria-hidden="true">
              ›
            </span>
          )}
          {item.to ? (
            <Link className="font-medium text-muted-foreground hover:text-foreground" to={item.to}>
              {item.label}
            </Link>
          ) : (
            <h1 className="inline break-all text-sm font-medium text-muted-foreground">{item.label}</h1>
          )}
        </span>
      ))}
    </nav>
  );
}
