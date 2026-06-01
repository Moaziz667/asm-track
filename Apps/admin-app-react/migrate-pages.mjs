import fs from 'fs';
import path from 'path';

const APP_DIR = path.resolve(process.cwd(), 'Apps/admin-app/src/app');
const PAGES_DIR = path.resolve(process.cwd(), 'Apps/admin-app-react/src/pages');

function capitalize(s) {
  if (typeof s !== 'string') return '';
  return s.charAt(0).toUpperCase() + s.slice(1);
}

function toCamelCase(str) {
  return str.split('-').map(capitalize).join('');
}

if (!fs.existsSync(PAGES_DIR)) {
  fs.mkdirSync(PAGES_DIR, { recursive: true });
}

function processAppDirectory(currentDir, relativePath = '') {
  const entries = fs.readdirSync(currentDir);
  
  for (const entry of entries) {
    const fullPath = path.join(currentDir, entry);
    const stat = fs.statSync(fullPath);
    
    if (stat.isDirectory()) {
      // It's a route folder (e.g. login, map, route-builder)
      processAppDirectory(fullPath, path.join(relativePath, entry));
    } else if (entry === 'page.tsx') {
      const folderName = path.basename(currentDir);
      
      // If it's the root page.tsx, name it HomePage
      let pageName = 'HomePage';
      if (relativePath !== '') {
        pageName = toCamelCase(folderName) + 'Page';
        
        // Handle nested folders, e.g. auth/login -> AuthLoginPage (simplified: just use folder name if unique enough)
        // For this app, folders are mostly top level.
      }
      
      const newPath = path.join(PAGES_DIR, `${pageName}.tsx`);
      fs.copyFileSync(fullPath, newPath);
      console.log(`Copied ${relativePath}/page.tsx -> ${pageName}.tsx`);
    } else if (entry === 'layout.tsx') {
       // Ignore layouts, we already mapped to protected layout
    }
  }
}

processAppDirectory(APP_DIR);
