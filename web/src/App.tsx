import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createBrowserRouter, RouterProvider } from "react-router";
import { ApiError } from "@/api/client";
import { Layout } from "@/components/Layout";
import { RouteError } from "@/components/RouteError";
import { TooltipProvider } from "@/components/ui/tooltip";
import { NotFound } from "@/pages/NotFound";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 60_000,
      refetchOnWindowFocus: false,
      // 4xx answers (unknown company, bad input, rate limit) do not get better by retrying.
      retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2,
    },
  },
});

// Each page is its own chunk: Recharts (Overview) and d3 (Network) load only where they are used.
const router = createBrowserRouter([
  {
    element: <Layout />,
    children: [
      {
        // Errors render inside the layout, so the navigation stays usable.
        errorElement: <RouteError />,
        children: [
          { path: "/", lazy: async () => ({ Component: (await import("@/pages/Overview")).Overview }) },
          { path: "/search", lazy: async () => ({ Component: (await import("@/pages/Search")).Search }) },
          { path: "/companies/:reg", lazy: async () => ({ Component: (await import("@/pages/Company")).Company }) },
          { path: "/companies/:reg/network", lazy: async () => ({ Component: (await import("@/pages/Network")).Network }) },
          { path: "/changes", lazy: async () => ({ Component: (await import("@/pages/Changes")).Changes }) },
          { path: "/pipeline", lazy: async () => ({ Component: (await import("@/pages/Pipeline")).Pipeline }) },
          { path: "/pipeline/:id", lazy: async () => ({ Component: (await import("@/pages/Pipeline")).RunDetail }) },
          { path: "/insights", lazy: async () => ({ Component: (await import("@/pages/Insights")).Insights }) },
          { path: "*", element: <NotFound /> },
        ],
      },
    ],
  },
]);

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <RouterProvider router={router} />
      </TooltipProvider>
    </QueryClientProvider>
  );
}
