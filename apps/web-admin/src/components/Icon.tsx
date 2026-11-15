// Ícones do pacote (viewBox 24, traço 1.7, currentColor). Inseridos inline para herdar a cor do tema.
const SOURCES = import.meta.glob("../assets/icons/*.svg", { query: "?raw", import: "default", eager: true }) as Record<
  string,
  string
>;
const ICONS: Record<string, string> = Object.fromEntries(
  Object.entries(SOURCES).map(([path, svg]) => [
    path.replace(/^.*\/(.*)\.svg$/, "$1"),
    svg.replace(/<title>.*?<\/title>/, ""),
  ]),
);

export function Icon({ name, size = 20, label }: { name: string; size?: number; label?: string }) {
  const svg = ICONS[name];
  if (!svg) return null;
  return (
    <span
      className="icon"
      style={{ width: size, height: size }}
      role={label ? "img" : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      dangerouslySetInnerHTML={{ __html: svg }}
    />
  );
}
