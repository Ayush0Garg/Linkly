import { ApiError } from '../api/client';

export function QueryError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const message = error instanceof ApiError ? error.message : 'Something went wrong while loading this.';
  return (
    <div className="form-error" role="alert">
      <p>{message}</p>
      <button type="button" className="btn btn--text" onClick={onRetry}>Try again</button>
    </div>
  );
}
