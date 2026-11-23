import js from "@eslint/js";
import eslintConfigPrettier from "eslint-config-prettier";
import {
  createConfig,
  recommended as boundariesRecommended,
} from "eslint-plugin-boundaries/config";
import jsxA11y from "eslint-plugin-jsx-a11y";
import reactHooks from "eslint-plugin-react-hooks";
import globals from "globals";
import tseslint from "typescript-eslint";

const boundariesConfig = createConfig({
  // Only classify application sources; tooling configs stay outside architecture rules.
  files: ["src/**/*.{js,jsx,ts,tsx}"],
  settings: {
    ...boundariesRecommended.settings,
    "boundaries/elements": [
      { type: "components", pattern: "src/components", partialMatch: false },
      { type: "pages", pattern: "src/pages", partialMatch: false },
      { type: "public", pattern: "src/public", partialMatch: false },
      { type: "lib", pattern: "src/lib", partialMatch: false },
      { type: "test-helpers", pattern: "src/test", partialMatch: false },
    ],
    "boundaries/files": [
      { category: "test", pattern: "src/**/*.test.ts" },
      { category: "test", pattern: "src/**/*.test.tsx" },
      { category: "test", pattern: "src/test/**/*" },
      { category: "entry", pattern: "src/App.tsx" },
      { category: "entry", pattern: "src/main.tsx" },
      { category: "shared", pattern: "src/lib/period.ts" },
      { category: "shared", pattern: "src/lib/dashboardViewData.ts" },
      { category: "ambient", pattern: "src/vite-env.d.ts" },
      { category: "ambient", pattern: "src/assets/**/*" },
    ],
  },
  rules: {
    ...boundariesRecommended.rules,
    // Every linted src file must match an element and/or file descriptor.
    "boundaries/no-unknown-files": [2],
    "boundaries/dependencies": [
      2,
      {
        // Type-only imports stay subject to the same policies (import nodes, kind "type").
        // Internals are checked so production files cannot import co-located *.test.*.
        checkInternals: true,
        default: "disallow",
        policies: [
          {
            from: { element: { type: ["lib", "components", "pages", "public", "test-helpers"] } },
            allow: { dependency: { relationship: { to: "internal" } } },
          },
          {
            from: { element: { type: "lib" } },
            allow: {
              to: { element: { type: ["lib"] } },
            },
          },
          {
            from: { element: { type: "components" } },
            allow: {
              to: { element: { type: ["components", "lib"] } },
            },
          },
          {
            from: { element: { type: "pages" } },
            allow: {
              to: { element: { type: ["pages", "components", "lib"] } },
            },
          },
          {
            from: { element: { type: "public" } },
            allow: {
              to: { element: { type: ["public", "pages", "components", "lib"] } },
            },
          },
          {
            from: { element: { type: "test-helpers" } },
            allow: {
              to: {
                element: { type: ["test-helpers", "lib", "components", "pages", "public"] },
              },
            },
          },
          {
            from: { file: { categories: "entry" } },
            allow: {
              to: { element: { type: ["components", "pages", "public", "lib"] } },
            },
          },
          {
            from: { file: { categories: "entry" } },
            allow: {
              to: { file: { categories: ["entry", "ambient"] } },
            },
          },
          {
            from: { element: { type: ["components", "pages", "public", "lib"] } },
            allow: {
              to: { file: { categories: "ambient" } },
            },
          },
          // Independent shared types/calcs may use other lib modules, not UI/pages.
          {
            from: { file: { categories: "shared" } },
            allow: {
              to: { element: { type: ["lib"] } },
            },
          },
          {
            from: { file: { categories: "shared" } },
            allow: { dependency: { relationship: { to: "internal" } } },
          },
          // Production code must not import tests / fixtures / setup helpers.
          {
            from: { file: { categories: { noneOf: ["test"] } } },
            disallow: { to: { file: { categories: "test" } } },
          },
          {
            from: { file: { categories: { noneOf: ["test"] } } },
            disallow: { to: { element: { type: "test-helpers" } } },
          },
          // Tests may import pages/components/lib (and other tests); last match wins.
          {
            from: { file: { categories: "test" } },
            allow: {
              to: {
                element: { type: ["components", "pages", "public", "lib", "test-helpers"] },
              },
            },
          },
          {
            from: { file: { categories: "test" } },
            allow: {
              to: { file: { categories: ["test", "entry", "shared", "ambient"] } },
            },
          },
        ],
      },
    ],
  },
});

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
    files: ["src/**/*.{jsx,tsx}"],
    ...jsxA11y.flatConfigs.recommended,
    languageOptions: {
      ...jsxA11y.flatConfigs.recommended.languageOptions,
      globals: {
        ...globals.browser,
      },
    },
    settings: {
      "jsx-a11y": {
        components: {
          // Real <button> wrappers — map only when semantics are guaranteed.
          Button: "button",
          NavigationButton: "button",
        },
      },
    },
  },
  boundariesConfig,
  {
    // Resolver for TS path/extension resolution; not a boundaries/* key, so keep outside createConfig.
    settings: {
      "import/resolver": {
        typescript: {
          alwaysTryTypes: true,
          project: "./tsconfig.json",
        },
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
