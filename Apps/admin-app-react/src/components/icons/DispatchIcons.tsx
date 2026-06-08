interface IconProps {
  size?: number;
  color?: string;
  className?: string;
}

export function IconAssign({ size = 20, color = 'currentColor', className }: IconProps) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" height={size} width={size} style={{ width: size, height: size }} viewBox="0 -960 960 960" fill={color} stroke={color} strokeWidth="25" className={className}>
      <path d="M200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h168q13-36 43.5-58t68.5-22q38 0 68.5 22t43.5 58h168q33 0 56.5 23.5T840-760v268q-19-9-39-15.5t-41-9.5v-243H200v560h242q3 22 9.5 42t15.5 38H200Zm0-120v40-560 243-3 280Zm80-40h163q3-21 9.5-41t14.5-39H280v80Zm0-160h244q32-30 71.5-50t84.5-27v-3H280v80Zm0-160h400v-80H280v80Zm221.5-198.5Q510-807 510-820t-8.5-21.5Q493-850 480-850t-21.5 8.5Q450-833 450-820t8.5 21.5Q467-790 480-790t21.5-8.5ZM720-40q-83 0-141.5-58.5T520-240q0-83 58.5-141.5T720-440q83 0 141.5 58.5T920-240q0 83-58.5 141.5T720-40Zm-20-80h40v-100h100v-40H740v-100h-40v100H600v40h100v100Z" />
    </svg>
  );
}
 
export function IconReassign({ size = 20, color = 'currentColor', className }: IconProps) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" height={size} width={size} style={{ width: size, height: size }} viewBox="0 -960 960 960" fill={color} stroke={color} strokeWidth="25" className={className}>
      <path d="m620-660-58-56 44-44H318v-80h288l-44-44 56-58 142 142-140 140ZM200-200h560v-200H200v200Zm143-57q17-17 17-43t-17-43q-17-17-43-17t-43 17q-17 17-17 43t17 43q17 17 43 17t43-17Zm360 0q17-17 17-43t-17-43q-17-17-43-17t-43 17q-17 17-17 43t17 43q17 17 43 17t43-17ZM120-480h608l-42-120H274l44 44-56 58-142-142 142-142 56 58-44 44h426q20 0 35 11t21 29l84 240v320q0 17-11.5 28.5T800-40h-40q-17 0-28.5-11.5T720-80v-40H240v40q0 17-11.5 28.5T200-40h-40q-17 0-28.5-11.5T120-80v-400Zm80 80v200-200Z" />
    </svg>
  );
}
 
export function IconReplan({ size = 20, color = 'currentColor', className }: IconProps) {
  const adjustedSize = Math.round(size * 1.45);
  return (
    <svg xmlns="http://www.w3.org/2000/svg" height={adjustedSize} width={adjustedSize} style={{ width: adjustedSize, height: adjustedSize }} viewBox="0 -960 960 960" fill={color} stroke={color} strokeWidth="25" className={className}>
      <path d="M339.5-108.5q-65.5-28.5-114-77t-77-114Q120-365 120-440h80q0 117 81.5 198.5T480-160q117 0 198.5-81.5T760-440q0-117-81.5-198.5T480-720h-6l62 62-56 58-160-160 160-160 56 58-62 62h6q75 0 140.5 28.5t114 77q48.5 48.5 77 114T840-440q0 75-28.5 140.5t-77 114q-48.5 48.5-114 77T480-80q-75 0-140.5-28.5Z" />
    </svg>
  );
}
 
export function IconCall({ size = 20, color = 'currentColor', className }: IconProps) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" height={size} width={size} style={{ width: size, height: size }} viewBox="0 -960 960 960" fill={color} stroke={color} strokeWidth="25" className={className}>
      <path d="M798-120q-125 0-247-54.5T329-329Q229-429 174.5-551T120-798q0-18 12-30t30-12h162q14 0 25 9.5t13 22.5l26 140q2 16-1 27t-11 19l-97 98q20 37 47.5 71.5T387-386q31 31 65 57.5t72 48.5l94-94q9-9 23.5-13.5T670-390l138 28q14 4 23 14.5t9 23.5v162q0 18-12 30t-30 12ZM241-600l66-66-17-94h-89q5 41 14 81t26 79Zm358 358q39 17 79.5 27t81.5 13v-88l-94-19-67 67ZM241-600Zm358 358Z" />
    </svg>
  );
}

