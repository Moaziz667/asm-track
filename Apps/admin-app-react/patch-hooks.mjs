import fs from 'fs';
import path from 'path';

const SRC_DIR = path.resolve(process.cwd(), 'src');

function walk(d) {
  fs.readdirSync(d).forEach(f => {
    const p = path.join(d, f);
    if (fs.statSync(p).isDirectory()) {
      walk(p);
    } else if (p.endsWith('.tsx') || p.endsWith('.ts')) {
      let c = fs.readFileSync(p, 'utf8');
      let m = false;

      // Fix next/dynamic
      if (c.includes('next/dynamic')) {
        c = c.replace(/import dynamic from ['"]next\/dynamic['"];?/g, "import { lazy as dynamic } from 'react';");
        m = true;
      }

      // Fix Next router being blindly replaced with react-router-dom useRouter
      if (c.includes('useRouter')) {
        c = c.replace(/import { useRouter, useSearchParams } from ['"]react-router-dom['"];?/g, "import { useNavigate as useRouter, useSearchParams } from 'react-router-dom';");
        c = c.replace(/import { useRouter } from ['"]react-router-dom['"];?/g, "import { useNavigate as useRouter } from 'react-router-dom';");
        m = true;
      }
      
      // Fix types verbatimModuleSyntax (Type imports without type keyword)
      // I'll just turn it off in tsconfig, so I don't strictly need to fix it here,
      // but let's blindly replace next/router if they somehow still exist.
      if (c.includes('next/router')) {
         c = c.replace(/next\/router/g, 'react-router-dom');
         m = true;
      }

      if (m) {
        fs.writeFileSync(p, c, 'utf8');
        console.log('Patched:', p);
      }
    }
  });
}

walk(SRC_DIR);
console.log('Done mapping hooks.');
