import { useEffect, useId, useRef, type ReactNode } from "react";
import { X } from "lucide-react";

/** Native modal supplies background inertness, Escape and keyboard focus containment. */
export function RepositoryWarningSheet({ count, children, onClose }: { count: number; children: ReactNode; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const gesture = useRef<{ x: number; y: number } | null>(null);
  const title = useId();
  useEffect(() => {
    const node = dialog.current!;
    const focused = document.activeElement as HTMLElement | null;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    node.showModal();
    return () => { node.close(); document.body.style.overflow = overflow; focused?.focus(); };
  }, []);
  return <dialog ref={dialog} aria-labelledby={title} aria-modal="true"
    className="fixed inset-x-0 bottom-0 top-auto m-0 max-h-[85dvh] w-full max-w-none overflow-hidden rounded-t-2xl border bg-card p-0 text-foreground shadow-xl backdrop:bg-black/40"
    onCancel={event => { event.preventDefault(); onClose(); }}
    onClick={event => { if (event.target === event.currentTarget) { const r = event.currentTarget.getBoundingClientRect(); if (event.clientY < r.top || event.clientY > r.bottom || event.clientX < r.left || event.clientX > r.right) onClose(); } }}>
    <div className="touch-pan-x border-b px-4 pb-3 pt-2"
      onPointerDown={event => { gesture.current = { x: event.clientX, y: event.clientY }; if (!(event.target as Element).closest("button")) event.currentTarget.setPointerCapture?.(event.pointerId); }}
      onPointerCancel={() => { gesture.current = null; }}
      onPointerUp={event => { const start = gesture.current; gesture.current = null; if (start && event.clientY - start.y > 60 && Math.abs(event.clientX - start.x) < 40) onClose(); }}>
      <div className="mx-auto mb-2 h-1 w-10 rounded bg-muted-foreground/40" aria-hidden="true" />
      <div className="flex items-center justify-between gap-3"><h2 id={title} className="font-medium">Repository data warnings</h2><button autoFocus type="button" aria-label="Close repository warnings" className="flex size-11 shrink-0 items-center justify-center rounded-md border" onClick={onClose}><X className="size-5" aria-hidden="true" /></button></div>
      <p className="text-sm text-muted-foreground">{count} affected repositories</p>
    </div>
    <div className="max-h-[calc(85dvh-8rem)] overflow-y-auto overscroll-contain px-4 pb-[max(1rem,env(safe-area-inset-bottom))]">{children}</div>
  </dialog>;
}

