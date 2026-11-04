import type { SeriesStyle } from "../lib/seriesStyle";
const GLYPHS: Record<string, string> = { circle: "●", rect: "■", triangle: "▲", diamond: "◆", roundRect: "▰", pin: "▼", arrow: "➤" };
export function SeriesSwatch({ style }: { style: SeriesStyle }) {
  return <svg aria-hidden="true" viewBox="0 0 30 14" className="inline-block h-3.5 w-8 shrink-0 rounded bg-card align-middle">
    <line x1="1" y1="7" x2="29" y2="7" stroke={style.color} strokeWidth="2" strokeDasharray={{ solid: undefined, dashed: "5 3", dotted: "1 3" }[style.line]} />
    <text x="15" y="11" textAnchor="middle" fontSize="10" fill={style.color}>{GLYPHS[style.symbol]}</text>
  </svg>;
}
