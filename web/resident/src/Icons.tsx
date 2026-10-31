import type { ReactNode } from "react";

/** Ícones de traço próprios (24×24, `currentColor`): seguem o tema e não dependem das fontes de emoji do aparelho. Decorativos. */
function Svg({ children }: { children: ReactNode }) {
  return (
    <svg className="icon" viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.8"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">{children}</svg>
  );
}

export const IconHome = () => <Svg><path d="M3 11.5 12 4l9 7.5" /><path d="M5.5 10v9.5h13V10" /><path d="M10 19.5v-5h4v5" /></Svg>;
export const IconMenu = () => <Svg><path d="M4 7h16M4 12h16M4 17h16" /></Svg>;
export const IconCamera = () => <Svg><rect x="3" y="7" width="13" height="10" rx="2" /><path d="m16 11 5-3v8l-5-3" /></Svg>;
export const IconVisits = () => <Svg><circle cx="9" cy="8" r="3" /><path d="M3.5 19c.5-3.2 2.7-5 5.5-5s5 1.8 5.5 5" /><path d="M16 5.5a3 3 0 0 1 0 5.5M17.5 14.2c1.8.6 3 2.2 3.3 4.8" /></Svg>;
export const IconParcel = () => <Svg><path d="M3.5 7.5 12 3l8.5 4.5v9L12 21l-8.5-4.5z" /><path d="M3.5 7.5 12 12l8.5-4.5M12 12v9" /></Svg>;
export const IconMessage = () => <Svg><path d="M4 5h16v11H9l-5 4z" /></Svg>;
export const IconMore = () => <Svg><rect x="4" y="4" width="6.5" height="6.5" rx="1.5" /><rect x="13.5" y="4" width="6.5" height="6.5" rx="1.5" /><rect x="4" y="13.5" width="6.5" height="6.5" rx="1.5" /><rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.5" /></Svg>;
