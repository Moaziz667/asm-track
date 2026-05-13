// @ts-check

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'ASM Track',
  tagline: 'Last-Mile Delivery Platform — Technical Documentation',
  favicon: 'img/favicon.ico',
  url: 'https://asm-track.local',
  baseUrl: '/',
  onBrokenLinks: 'warn',
  onBrokenMarkdownLinks: 'warn',

  markdown: {
    mermaid: true,
  },
  themes: ['@docusaurus/theme-mermaid'],

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          sidebarPath: require.resolve('./sidebars.js'),
          routeBasePath: '/',
        },
        blog: false,
        theme: {
          customCss: require.resolve('./src/css/custom.css'),
        },
      }),
    ],
  ],

  plugins: [
    function cytoscapeAlias(context, options) {
      return {
        name: 'cytoscape-alias-plugin',
        configureWebpack() {
          return {
            module: {
              rules: [
                {
                  test: /cytoscape\/dist\/cytoscape\.umd\.js$/,
                  type: 'javascript/auto',
                },
              ],
            },
            resolve: {
              alias: {
                'cytoscape/dist/cytoscape.umd.js': require.resolve('cytoscape/dist/cytoscape.umd.js'),
              },
            },
          };
        },
      };
    },
  ],

  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      colorMode: {
        defaultMode: 'dark',
        respectPrefersColorScheme: true,
      },
      navbar: {
        title: 'ASM Track',
        items: [
          { type: 'docSidebar', sidebarId: 'docs', position: 'left', label: 'Docs' },
          { href: 'http://localhost:8082/swagger-ui.html', label: 'Delivery API', position: 'right' },
          { href: 'http://localhost:8080/swagger-ui.html', label: 'IAM API', position: 'right' },
          { href: 'http://localhost:8086/swagger-ui.html', label: 'Driver API', position: 'right' },
        ],
      },
      footer: {
        style: 'dark',
        copyright: `ASM Track · PFE 2026 · Built with Docusaurus`,
      },
      mermaid: {
        theme: { light: 'neutral', dark: 'dark' },
      },
      prism: {
        additionalLanguages: ['java', 'bash', 'yaml', 'json'],
      },
    }),
};

module.exports = config;
