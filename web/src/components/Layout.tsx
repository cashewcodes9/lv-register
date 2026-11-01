import { NavLink, Outlet } from "react-router";
import { cn } from "@/lib/utils";

const PAGES = [
  { to: "/", label: "Overview", end: true },
  { to: "/search", label: "Search" },
  { to: "/changes", label: "Changes" },
  { to: "/insights", label: "Insights" },
  { to: "/pipeline", label: "Pipeline" },
];

export function Layout() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <header className="border-t-4 border-t-primary border-b">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-6 px-4 py-3">
          <NavLink to="/" className="font-heading text-lg font-semibold tracking-tight text-primary">
            lv-register
          </NavLink>
          <nav className="flex flex-wrap gap-1 text-sm" aria-label="Main">
            {PAGES.map((page) => (
              <NavLink
                key={page.to}
                to={page.to}
                end={page.end}
                className={({ isActive }) =>
                  cn(
                    "rounded-md px-3 py-1.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground",
                    isActive && "bg-primary/10 font-medium text-primary",
                  )
                }
              >
                {page.label}
              </NavLink>
            ))}
          </nav>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-6">
        <Outlet />
      </main>
      <footer className="mx-auto max-w-6xl px-4 pb-8 text-xs text-muted-foreground">
        Open data of the Latvian Register of Enterprises (Uzņēmumu reģistrs, data.gov.lv), captured weekly. Events
        are changes seen between two captures.
      </footer>
    </div>
  );
}
