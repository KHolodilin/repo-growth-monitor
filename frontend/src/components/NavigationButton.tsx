import { useEffect, useId, useRef, useState, type ButtonHTMLAttributes } from "react";
import { useIsMobileChart } from "../lib/useMediaQuery";
import { cn } from "../lib/utils";

/** Short tabs keep their accessible name; only actually clipped labels need a visual hint. */
export function NavigationButton({ label, mobileLabel, truncate = false, className, onClick, ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { label: string; mobileLabel?: string; truncate?: boolean }) {
  const mobile = useIsMobileChart();
  const text = useRef<HTMLSpanElement>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const suppressClick = useRef(false);
  const [clipped, setClipped] = useState(false);
  const [open, setOpen] = useState(false);
  const id = useId();
  const hint = mobile && clipped;
  useEffect(() => {
    const node = text.current!;
    const measure = () => setClipped(truncate && node.scrollWidth > node.clientWidth);
    measure();
    if (typeof ResizeObserver === "undefined") {
      window.addEventListener("resize", measure);
      return () => { window.removeEventListener("resize", measure); clearTimeout(timer.current); };
    }
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    return () => { observer.disconnect(); clearTimeout(timer.current); };
  }, [label, truncate]);
  return <button {...props} aria-label={label} aria-describedby={hint && open ? id : undefined}
    className={cn("relative min-w-0 rounded-md px-3 py-1.5 text-sm font-medium", className)}
    onMouseEnter={() => setOpen(true)} onMouseLeave={() => setOpen(false)}
    onFocus={() => setOpen(true)} onBlur={() => setOpen(false)}
    onKeyDown={event => { if (event.key === "Escape") setOpen(false); }}
    onPointerDown={event => {
      suppressClick.current = false;
      if (hint && event.pointerType === "touch") timer.current = setTimeout(() => { suppressClick.current = true; setOpen(true); }, 500);
    }}
    onPointerUp={() => clearTimeout(timer.current)} onPointerCancel={() => clearTimeout(timer.current)}
    onContextMenu={event => { if (hint) event.preventDefault(); }}
    onClick={event => { if (suppressClick.current) { event.preventDefault(); suppressClick.current = false; return; } setOpen(hint); onClick?.(event); }}>
    <span ref={text} className={cn("block", truncate && "truncate")}>{mobile ? mobileLabel ?? label : label}</span>
    {hint && open && <span id={id} role="tooltip" style={{ top: text.current!.getBoundingClientRect().bottom + 6 }} className="pointer-events-none fixed inset-x-4 z-50 whitespace-normal break-words rounded border bg-card px-2 py-1 text-left text-xs text-foreground shadow">{label}</span>}
  </button>;
}
