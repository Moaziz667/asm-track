import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./src/**/*.{js,ts,jsx,tsx,mdx}"],
  darkMode: "class",
  theme: {
    extend: {
      fontFamily: {
        sans: ["'IBM Plex Sans'", "system-ui", "sans-serif"],
        mono: ['"JetBrains Mono"', '"IBM Plex Mono"', "ui-monospace", "monospace"],
      },
      borderRadius: {
        xs:  "2px",
        sm:  "4px",
        DEFAULT: "6px",
        md:  "6px",
        lg:  "8px",
        xl:  "12px",
        "2xl": "16px",
      },
      fontSize: {
        "2xs": ["10px", { lineHeight: "14px" }],
        xs:    ["11px", { lineHeight: "16px" }],
        sm:    ["12px", { lineHeight: "18px" }],
        base:  ["13px", { lineHeight: "20px" }],
        md:    ["14px", { lineHeight: "20px" }],
        lg:    ["15px", { lineHeight: "22px" }],
        xl:    ["18px", { lineHeight: "26px" }],
        "2xl": ["22px", { lineHeight: "30px" }],
        "3xl": ["28px", { lineHeight: "34px" }],
      },
      fontWeight: {
        normal:     "400",
        medium:     "500",
        semibold:   "600",
        bold:       "700",
      },
    },
  },
};

export default config;
