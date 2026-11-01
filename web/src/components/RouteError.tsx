import { isRouteErrorResponse, useRouteError } from "react-router";
import { Button } from "@/components/ui/button";

/**
 * Errors while loading or rendering a page. The common one in production: the API was
 * redeployed, and this tab still holds the old index.html whose page chunks no longer exist.
 */
export function RouteError() {
  const error = useRouteError();
  const message = isRouteErrorResponse(error) ? `${error.status} ${error.statusText}` : error instanceof Error ? error.message : String(error);
  const staleChunk = /dynamically imported module|Importing a module script failed|error loading dynamically imported module/i.test(message);
  return (
    <div className="mx-auto max-w-xl space-y-3 py-16 text-center">
      <h1 className="font-heading text-2xl font-semibold">{staleChunk ? "A new version is available" : "Something went wrong"}</h1>
      <p className="text-sm text-muted-foreground">
        {staleChunk
          ? "The app was updated, or the server did not answer while this page was loading. Reload to continue."
          : message}
      </p>
      <Button onClick={() => window.location.reload()}>Reload</Button>
    </div>
  );
}
