import { Link } from "react-router-dom";
import { RepoSwitcher } from "./RepoSwitcher";

export type BreadcrumbItem = {
  label: string;
  to?: string;
  repoSwitcher?: {
    currentId: number;
    hrefFor: (repositoryId: number) => string;
    description?: string;
  };
};

export function PageBreadcrumb({ items }: { items: BreadcrumbItem[] }) {
  return (
    <nav className="flex min-w-0 flex-wrap items-center gap-y-1 text-sm md:block" aria-label="Breadcrumb">
      {items.map((item, index) => (
        <span key={`${item.label}-${index}`} className="inline-flex min-w-0 max-w-full items-center md:inline">
          {index > 0 && (
            <span className="mx-2 shrink-0 text-muted-foreground" aria-hidden="true">
              ›
            </span>
          )}
          {item.repoSwitcher ? (
            <RepoSwitcher
              currentId={item.repoSwitcher.currentId}
              currentLabel={item.label}
              hrefFor={item.repoSwitcher.hrefFor}
              description={item.repoSwitcher.description}
            />
          ) : item.to ? (
            <Link className="font-medium text-muted-foreground hover:text-foreground" to={item.to}>
              {item.label}
            </Link>
          ) : (
            <h1 className="inline min-w-0 break-normal text-sm font-medium text-muted-foreground">{item.label}</h1>
          )}
        </span>
      ))}
    </nav>
  );
}
