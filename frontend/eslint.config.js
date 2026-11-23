import js from "@eslint/js";
import eslintConfigPrettier from "eslint-config-prettier";
import reactHooks from "eslint-plugin-react-hooks";
import globals from "globals";
import tseslint from "typescript-eslint";

export default tseslint.config(
  {
    // Keep lint scoped to the frontend project even if stray trees exist under frontend/.
    ignores: [
      "dist/**",
      "coverage/**",
      "node_modules/**",
      ".tools/**",
      ".m2-docker/**",
      "backend/**",
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommendedTypeChecked,
  {
    // Shared type-aware options. tsconfig.node.json includes vite.config.ts, but the
    // typescript-eslint project service still does not attach that file (verified error:
    // "was not found by the project service"). allowDefaultProject is required for
    // type-aware lint of the Vite config only; src stays on tsconfig.json via projectService.
    languageOptions: {
      parserOptions: {
        projectService: {
          allowDefaultProject: ["vite.config.ts"],
        },
        tsconfigRootDir: import.meta.dirname,
      },
    },
  },
  {
    files: ["src/**/*.{ts,tsx}"],
    extends: [reactHooks.configs.flat.recommended],
    languageOptions: {
      globals: {
        ...globals.browser,
      },
    },
  },
  {
    files: ["vite.config.ts"],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
  },
  {
    files: ["**/*.{js,mjs,cjs}"],
    extends: [tseslint.configs.disableTypeChecked],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
  },
  eslintConfigPrettier,
);
