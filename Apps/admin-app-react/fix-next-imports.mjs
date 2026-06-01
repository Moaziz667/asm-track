import fs from 'fs';
import path from 'path';

const SRC_DIR = path.resolve(process.cwd(), 'Apps/admin-app-react/src');

function fixNextImports(dir) {
  const files = fs.readdirSync(dir);

  for (const file of files) {
    const fullPath = path.join(dir, file);
    const stat = fs.statSync(fullPath);

    if (stat.isDirectory()) {
      fixNextImports(fullPath);
    } else if (fullPath.endsWith('.ts') || fullPath.endsWith('.tsx')) {
      let content = fs.readFileSync(fullPath, 'utf8');
      let modified = false;

      if (content.includes("'use client'") || content.includes('"use client"')) {
        content = content.replace(/['"]use client['"];?\n?/g, '');
        modified = true;
      }

      if (content.includes('next/navigation')) {
        content = content.replace(/next\/navigation/g, 'react-router-dom');
        content = content.replace(/usePathname/g, 'useLocation');
        // useLocation returns { pathname } so we need to map that.
        // Wait, if they do `const pathname = usePathname();`, react-router would be:
        // `const { pathname } = useLocation();`
        content = content.replace(/const pathname = useLocation\(\);/g, "const { pathname } = useLocation();");
        modified = true;
      }

      if (content.includes('next/link')) {
        content = content.replace(/import Link from ['"]next\/link['"];?/g, "import { Link } from 'react-router-dom';");
        modified = true;
      }
      
      if (content.includes('next/image')) {
        content = content.replace(/import Image from ['"]next\/image['"];?/g, "");
        content = content.replace(/<Image/g, "<img");
        content = content.replace(/&lt;Image/g, "&lt;img");
        modified = true;
      }

      if (modified) {
        fs.writeFileSync(fullPath, content, 'utf8');
        console.log('Fixed:', fullPath);
      }
    }
  }
}

fixNextImports(SRC_DIR);