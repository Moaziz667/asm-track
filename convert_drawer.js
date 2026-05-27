const fs = require("fs");
let p = "Apps/admin-app/src/components/overlays/ReassignDrawer.tsx";
let code = fs.readFileSync(p, "utf-8");

code = code.replace(/import \{([\s\S]*?)\} from '@mantine\/core';/, "import { Button, Chip, Spinner } from '@heroui/react';\nimport { createPortal } from 'react-dom';");

// Convert simple textual Mantine components
code = code.replace(/<Text([^>]*)>/g, "<span$1>");
code = code.replace(/<\/Text>/g, "</span>");
code = code.replace(/<Box([^>]*)>/g, "<div$1>");
code = code.replace(/<\/Box>/g, "</div>");
code = code.replace(/<Divider ([^>]*)\/?>/g, "<hr style={{ border: 0, borderTop: '1px solid var(--border-color)', margin: '16px 0' }} />");
code = code.replace(/<ActionIcon([^>]*)>/g, "<button$1>");
code = code.replace(/<\/ActionIcon>/g, "</button>");
code = code.replace(/<UnstyledButton([^>]*)>/g, "<button$1>");
code = code.replace(/<\/UnstyledButton>/g, "</button>");

code = code.replace(/<Stack /g, "<div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }} ");
code = code.replace(/<\/Stack>/g, "</div>");

code = code.replace(/<Group /g, "<div style={{ display: 'flex', alignItems: 'center', gap: '16px' }} ");
code = code.replace(/<\/Group>/g, "</div>");
code = code.replace(/<Paper /g, "<div style={{ background: 'var(--surface-1)', border: '1px solid var(--border-color)', borderRadius: 'var(--radius)' }} ");
code = code.replace(/<\/Paper>/g, "</div>");
code = code.replace(/<ThemeIcon([^>]*)>/g, "<div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', width: 32, height: 32, borderRadius: '50%', background: 'var(--surface-2)' }}$1>");
code = code.replace(/<\/ThemeIcon>/g, "</div>");

code = code.replace(/<Loader([^>]*)>/g, "<Spinner$1>");
code = code.replace(/<Badge([^>]*)>/g, "<Chip size=\"sm\"$1>");
code = code.replace(/<\/Badge>/g, "</Chip>");
code = code.replace(/<ScrollArea /g, "<div style={{ overflowY: 'auto' }} ");
code = code.replace(/<\/ScrollArea>/g, "</div>");


fs.writeFileSync(p, code);
