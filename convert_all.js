const fs = require("fs");
const path = require("path");

function walk(dir) {
    let results = [];
    let list = fs.readdirSync(dir);
    list.forEach(function(file) {
        file = dir + '/' + file;
        let stat = fs.statSync(file);
        if (stat && stat.isDirectory()) { 
            results = results.concat(walk(file));
        } else { 
            if (file.endsWith(".tsx") || file.endsWith(".ts")) {
                results.push(file);
            }
        }
    });
    return results;
}

const files = walk("Apps/admin-app/src");

let changedCount = 0;
files.forEach(f => {
    let code = fs.readFileSync(f, "utf-8");
    if (code.includes("@mantine")) {
        const originalCode = code;
        
        // Remove mantine imports entirely, and inject HeroUI if needed
        let hasHeroUI = false;
        if (code.includes("<Button") || code.includes("<Chip") || code.includes("<Spinner") || code.includes("<Tooltip")) {
            hasHeroUI = true;
        }

        code = code.replace(/import \{([\s\S]*?)\} from '@mantine\/core';\n?/, "");
        code = code.replace(/import \{([\s\S]*?)\} from '@mantine\/notifications';\n?/, "");

        // If the file used Mantine buttons or badges etc, we must import HeroUI (though we'd need to merge imports)
        // For simplicity, we just add it to the top.
        if (hasHeroUI && !code.includes("@heroui/react")) {
            code = code.replace(/('|")use client('|");?\n/, "'use client';\nimport { Button, Chip, Spinner, Tooltip } from '@heroui/react';\n");
            if (!code.includes("@heroui/react")) {
                code = "import { Button, Chip, Spinner, Tooltip } from '@heroui/react';\n" + code;
            }
        }

        // Structural Replacements
        code = code.replace(/<Text([^>]*)>/g, "<span style={{ color: 'var(--text-primary)' }}$1>");
        code = code.replace(/<\/Text>/g, "</span>");
        
        code = code.replace(/<Box([^>]*)>/g, "<div$1>");
        code = code.replace(/<\/Box>/g, "</div>");
        
        code = code.replace(/<Divider ([^>]*)\/?>/g, "<hr style={{ border: 0, borderTop: '1px solid var(--border-color)', margin: '16px 0' }} />");
        
        code = code.replace(/<ActionIcon([^>]*)>/g, "<button style={{ background: 'none', border: 'none', cursor: 'pointer' }}$1>");
        code = code.replace(/<\/ActionIcon>/g, "</button>");
        
        code = code.replace(/<UnstyledButton([^>]*)>/g, "<button style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 0 }}$1>");
        code = code.replace(/<\/UnstyledButton>/g, "</button>");
        
        code = code.replace(/<Stack /g, "<div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }} ");
        code = code.replace(/<\/Stack>/g, "</div>");
        
        code = code.replace(/<Group /g, "<div style={{ display: 'flex', alignItems: 'center', gap: '16px', flexWrap: 'wrap' }} ");
        code = code.replace(/<\/Group>/g, "</div>");
        
        code = code.replace(/<Center /g, "<div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center' }} ");
        code = code.replace(/<\/Center>/g, "</div>");
        
        code = code.replace(/<Paper /g, "<div style={{ background: 'var(--surface-1)', border: '1px solid var(--border-color)', borderRadius: 'var(--radius)' }} ");
        code = code.replace(/<\/Paper>/g, "</div>");
        
        code = code.replace(/<ThemeIcon([^>]*)>/g, "<div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', width: 32, height: 32, borderRadius: '50%', background: 'var(--surface-2)' }}$1>");
        code = code.replace(/<\/ThemeIcon>/g, "</div>");
        
        code = code.replace(/<Loader([^>]*)>/g, "<Spinner$1>");
        
        code = code.replace(/<Badge([^>]*)>/g, "<Chip size=\"sm\"$1>");
        code = code.replace(/<\/Badge>/g, "</Chip>");
        
        code = code.replace(/<ScrollArea([^>]*)>/g, "<div style={{ overflowY: 'auto' }}$1>");
        code = code.replace(/<\/ScrollArea>/g, "</div>");
        
        code = code.replace(/<TextInput\s*([^>]*)\/>/g, "<input style={{ width:'100%', padding:'6px 10px', borderRadius:'var(--radius)', border:'1px solid var(--border-color)', background:'var(--surface-2)', color:'var(--text-primary)', fontSize:12 }} type=\"text\" $1 />");
        code = code.replace(/<Textarea\s*([^>]*)\/>/g, "<textarea style={{ width:'100%', padding:'6px 10px', borderRadius:'var(--radius)', border:'1px solid var(--border-color)', background:'var(--surface-2)', color:'var(--text-primary)', fontSize:12 }} $1 />");
        code = code.replace(/<NumberInput\s*([^>]*)\/>/g, "<input style={{ width:'100%', padding:'6px 10px', borderRadius:'var(--radius)', border:'1px solid var(--border-color)', background:'var(--surface-2)', color:'var(--text-primary)', fontSize:12 }} type=\"number\" $1 />");
        code = code.replace(/<Select\s*([^>]*)\/>/g, "<select style={{ width:'100%', padding:'6px 10px', borderRadius:'var(--radius)', border:'1px solid var(--border-color)', background:'var(--surface-2)', color:'var(--text-primary)', fontSize:12 }} $1><option value=\"\">Sélectionner</option></select>");

        // Toast replacements
        code = code.replace(/notifications\.show\(\{/g, "toast.info(");
        
        if (code !== originalCode) {
            fs.writeFileSync(f, code);
            changedCount++;
        }
    }
});
console.log(`Changed ${changedCount} files.`);
