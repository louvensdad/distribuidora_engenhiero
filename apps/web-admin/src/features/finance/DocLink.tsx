import type { DocumentView } from "../../api/types";

/** Link de download autorizado (o servidor confere a permissão e registra no histórico). */
export function DocLink({ d }: { d: DocumentView }) {
  if (d.state !== "AVAILABLE") return <span className="muted small">{d.file_name} — recusado{d.rejection ? `: ${d.rejection}` : ""}</span>;
  return <a href={`/api/v1/documents/${d.id}/download`}>{d.file_name}</a>;
}
