import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const jsonPath = path.join(__dirname, '../node_modules/@cloudscape-design/design-tokens/index-visual-refresh.json');
const tokens = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));

const targetCamel = [
  'colorBackgroundLayoutMain',
  'colorBackgroundContainerContent',
  'colorBackgroundLayoutPanelContent', // wait, check if this is exactly the name
  'colorBackgroundDropdownItemHover',
  'colorBorderDividerDefault',
  'colorBorderInputDefault',
  'colorTextBodyDefault',
  'colorTextBodySecondary',
  'colorTextFormSecondary',
  'colorBackgroundButtonPrimaryDefault',
  'colorBackgroundButtonPrimaryHover',
  'colorTextStatusSuccess',
  'colorTextStatusWarning',
  'colorTextStatusError',
  'colorTextStatusInfo',
  'colorBorderItemFocused',
  'borderRadiusContainer',
  'borderRadiusButton',
  'borderRadiusInput',
  'shadowContainer',
  'shadowDropdown',
  'shadowModal'
];

function camelToKebab(str) {
  return str.replace(/[A-Z0-9]/g, m => '-' + m.toLowerCase());
}

// Check what dark mode context exists
const darkContext = tokens.contexts?.['color-scheme-dark'] || tokens.contexts?.['dark'] || {};
console.log('Contexts available:', Object.keys(tokens.contexts || {}));

const result = {};
for (const target of targetCamel) {
  const kebab = camelToKebab(target);
  const tokenVal = tokens.tokens[kebab];
  if (tokenVal) {
    // Find dark value if any
    let darkValue = null;
    for (const contextName of Object.keys(tokens.contexts || {})) {
      if (contextName.includes('dark') || contextName.includes('dark-mode')) {
        const ctxToken = tokens.contexts[contextName].tokens?.[kebab];
        if (ctxToken) {
          darkValue = ctxToken.value;
        }
      }
    }
    result[target] = {
      kebab,
      light: tokenVal.value,
      dark: darkValue || tokenVal.value
    };
  } else {
    // Find partial matches to see if the token is named slightly differently
    const matches = Object.keys(tokens.tokens).filter(k => k.includes(kebab.slice(0, 10)));
    result[target] = {
      kebab,
      error: 'Not found',
      suggestions: matches
    };
  }
}

console.log(JSON.stringify(result, null, 2));
