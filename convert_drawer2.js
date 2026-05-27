const fs = require("fs");
let p = "Apps/admin-app/src/components/overlays/ReassignDrawer.tsx";
let code = fs.readFileSync(p, "utf-8");

// Convert TextInput and Textarea
code = code.replace(/<TextInput\s*([^>]*)\/>/g, "<input style={{ width:'100%', padding:'6px 10px', borderRadius:'var(--radius)', border:'1px solid var(--border-color)', background:'var(--surface-2)', color:'var(--text-primary)', fontSize:12 }} $1 />");
code = code.replace(/<Textarea\s*([^>]*)\/>/g, "<textarea style={{ width:'100%', padding:'6px 10px', borderRadius:'var(--radius)', border:'1px solid var(--border-color)', background:'var(--surface-2)', color:'var(--text-primary)', fontSize:12 }} $1 />");

// Drawer replacement - Drawer is huge, wrapping everything. Let's find it.
code = code.replace(/<Drawer([^>]*)>([\s\S]*?)<\/Drawer>/g, (match, props, inner) => {
    return `{mounted && open && createPortal(
    <div style={{ position: 'fixed', inset: 0, zIndex: 1000, display: 'flex', justifyContent: 'flex-end' }}>
      <div 
        style={{ position: 'absolute', inset: 0, backgroundColor: 'rgba(0, 0, 0, 0.6)', backdropFilter: 'blur(2px)' }} 
        onClick={onClose}
      />
      <div style={{
        position: 'relative',
        background: 'var(--app-bg)',
        width: 1020,
        maxWidth: '100vw',
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
        boxShadow: '-10px 0 25px rgba(0,0,0,0.5)',
        animation: 'slideInRight 0.3s ease-out'
      }}>
        ${inner}
      </div>
    </div>,
    document.body
  )}`;
});

fs.writeFileSync(p, code);
