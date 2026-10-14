type Axis = { data?: unknown[] };

type Series = { name?: string; type?: string; data?: unknown[] };

type ChartOption = {
  xAxis?: Axis | Axis[];
  series?: Series[];
};

type ChartView = {
  getEchartsInstance: () => {
    getDataURL: (opts: { type: "png"; pixelRatio: number; backgroundColor: string }) => string;
  };
};

export function chartExportBase(chartId: string) {
  const cleaned = chartId.replace(/[^A-Za-z0-9._-]+/g, "-").replace(/^-+|-+$/g, "");
  if (cleaned.length === 0) {
    return "chart";
  }
  return cleaned;
}

/** One row per axis date, one column per line series. Event markers and rank glyphs stay off the sheet. */
export function chartCsv(option: object) {
  const chart = option as ChartOption;
  const axis = Array.isArray(chart.xAxis) ? chart.xAxis[0] : chart.xAxis;
  const dates = (axis?.data ?? []).map((value) => (value == null ? "" : String(value)));
  const columns = (chart.series ?? []).filter(isExportedSeries);
  const header = ["date", ...columns.map((series) => series.name)].map(csvField).join(",");
  const rows = dates.map((date, index) =>
    [date, ...columns.map((series) => cellText(series.data?.[index]))].map(csvField).join(","),
  );
  return [header, ...rows].join("\n");
}

export function downloadChartPng(chart: ChartView | null, filename: string) {
  if (!chart) {
    return;
  }
  saveDownload(
    filename,
    chart.getEchartsInstance().getDataURL({ type: "png", pixelRatio: 2, backgroundColor: "#fff" }),
  );
}

export function downloadChartCsv(filename: string, csv: string) {
  const body = new TextEncoder().encode(csv);
  const bytes = new Uint8Array(body.length + 3);
  bytes[0] = 0xef;
  bytes[1] = 0xbb;
  bytes[2] = 0xbf;
  bytes.set(body, 3);
  const blob = new Blob([bytes], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  saveDownload(filename, url);
  URL.revokeObjectURL(url);
}

function isExportedSeries(series: Series): series is Series & { name: string } {
  return series.type === "line" && typeof series.name === "string" && series.name.length > 0 && series.name !== "Events";
}

function cellText(value: unknown): string {
  if (value == null) {
    return "";
  }
  if (typeof value === "number") {
    return Number.isFinite(value) ? String(value) : "";
  }
  if (typeof value === "string") {
    return value;
  }
  if (typeof value === "object" && "value" in value) {
    return cellText(value.value);
  }
  return "";
}

function csvField(value: string) {
  if (/[",\n\r]/.test(value)) {
    return `"${value.replaceAll("\"", "\"\"")}"`;
  }
  return value;
}

function saveDownload(filename: string, href: string) {
  const link = document.createElement("a");
  link.href = href;
  link.download = filename;
  link.click();
}
