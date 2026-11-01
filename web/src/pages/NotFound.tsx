import { Link } from "react-router";

export function NotFound() {
  return (
    <div className="space-y-2 py-12 text-center">
      <h1 className="font-heading text-2xl font-semibold">Page not found</h1>
      <Link to="/" className="text-sm hover:underline">
        Back to the overview
      </Link>
    </div>
  );
}
