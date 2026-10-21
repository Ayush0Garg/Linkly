import { splitCode } from '../lib/format';

/** The short code with the service prefix set back quietly, so the part that identifies the link stands out. */
export function Code({ code, large = false }: { code: string; large?: boolean }) {
  const { prefix, rest } = splitCode(code);
  return (
    <span className={`code${large ? ' code--large' : ''}`}>
      {prefix && <span className="code__prefix">{prefix}</span>}
      {rest}
    </span>
  );
}
