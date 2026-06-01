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

      // React Router navigate API: router.push(X) -> router(X)
      if (c.includes('router.push(')) {
        c = c.replace(/router\.push\((.*?)\)/g, "router($1)");
        m = true;
      }
      
      // React Router navigate API: router.replace(X) -> router(X, { replace: true })
      if (c.includes('router.replace(')) {
        c = c.replace(/router\.replace\((.*?)\)/g, "router($1, { replace: true })");
        m = true;
      }

      // React Router searchParams: useSearchParams returns [params, setParams]
      if (c.includes('const searchParams = useSearchParams();') || c.includes('const searchParams = useSearchParams()')) {
        c = c.replace(/const searchParams = useSearchParams\(\);?/g, "const [searchParams] = useSearchParams();");
        m = true;
      }

      // Next.js Link href -> React Router Link to
      if (c.includes('<Link ')) {
        c = c.replace(/<Link ([^>]*?)href=/g, "<Link $1to=");
        m = true;
      }

      // Next.js process.env.NEXT_PUBLIC_ -> Vite import.meta.env.VITE_
      if (c.includes('process.env.NEXT_PUBLIC_')) {
        c = c.replace(/process\.env\.NEXT_PUBLIC_/g, "import.meta.env.VITE_");
        m = true;
      }

      // Next.js next/dynamic 
      // replace: const X = dynamic(() => import('...'), { ... });
      // with: const X = React.lazy(() => import('...')); 
      if (c.includes('dynamic(')) {
        c = c.replace(/dynamic(?:<[^>]+>)?\(\(\) => import\((['"][^'"]+['"])\)(?:,\s*\{[\s\S]*?\}\s*)?\)/g, "dynamic(() => import($1))");
        m = true;
      }

      // JSX style tags
      if (c.includes('<style jsx global>')) {
        c = c.replace(/<style jsx global>\{`([\s\S]*?)`\}<\/style>/g, "<style>{`$1`}</style>");
        m = true;
      }

      if (c.includes("require('leaflet-draw')")) {
         c = c.replace(/require\('leaflet-draw'\);/g, "import 'leaflet-draw';");
         m = true;
      }

      if (m) {
        fs.writeFileSync(p, c, 'utf8');
        console.log('Fixed syntax in:', p);
      }
    }
  });
}

walk(SRC_DIR);
console.log('Done deep patching.');
