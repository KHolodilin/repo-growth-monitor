/** @type {import("prettier").Config} */
export default {
  printWidth: 100,
  singleQuote: false,
  trailingComma: "all",
  semi: true,
  // Windows checkouts often use CRLF via core.autocrlf; keep style checks EOL-agnostic.
  endOfLine: "auto",
};
