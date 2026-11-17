import { useState, type FormEvent } from "react";
import { ApiError, uploadFile } from "../../api/http";
import type { AccountingDocument, DocumentRequest } from "../../api/types";
import { useResource } from "../../api/useResource";
import { formatDateTime } from "../../app/format";
import { Field, FormError, fieldError } from "../../components/Form";
import { EmptyState, ErrorState, ForbiddenState, LoadingState } from "../../components/States";
import { useSession } from "../../session/SessionContext";
import { DocLink } from "./DocLink";
import { DOC_KIND, monthBr } from "./accountingLabels";

const asError = (e: unknown) => (e instanceof ApiError ? e : new ApiError({ status: 0, code: "UNKNOWN" }));

/** Central de documentos (CONT-04): arquivos privados com versões, solicitações e download registrado. */
export function DocumentsCenterPage() {
  const { can } = useSession();
  const [docs, reload] = useResource<{ items: AccountingDocument[] }>("/api/v1/documents");
  const [reqs, reloadReqs] = useResource<{ items: DocumentRequest[] }>("/api/v1/document-requests?state=OPEN");
  const [notice, setNotice] = useState<string | null>(null);
  const refresh = (m: string) => { setNotice(m); reload(); reloadReqs(); };
  if (docs.status === "loading") return <LoadingState />;
  if (docs.status === "forbidden") return <ForbiddenState />;
  if (docs.status === "error") return <ErrorState error={docs.error} onRetry={reload} />;
  const open = reqs.status === "ready" ? reqs.data.items : [];
  return (
    <section className="page" aria-labelledby="titulo-docs">
      <header className="page-head">
        <div>
          <h1 id="titulo-docs">Documentos</h1>
          <p className="muted">Arquivos privados (PDF, PNG, JPEG até 10 MB), conferidos pelo conteúdo — não pelo nome. Nova versão guarda a anterior. Cada download fica no histórico. Antivírus: NÃO CONFIGURADO neste ambiente.</p>
        </div>
      </header>
      {notice && <p className="notice" role="status">{notice}</p>}
      {open.length > 0 && (
        <section className="card" aria-labelledby="titulo-solic">
          <h2 id="titulo-solic" className="h3">Solicitações abertas</h2>
          <ul className="timeline">{open.map((r) => (
            <li key={r.id}><strong>{r.title}</strong><span>{DOC_KIND[r.kind] ?? r.kind}{r.competence ? ` · ${monthBr(r.competence)}` : ""} · pedido por {r.created_by_name}{r.requested_from_name ? ` a ${r.requested_from_name}` : ""}</span>
              <span className="muted small">{formatDateTime(r.created_at)}</span></li>
          ))}</ul>
        </section>
      )}
      {can("documents.attach") && <UploadForm requests={open} onDone={refresh} />}
      {docs.data.items.length === 0 ? <EmptyState title="Nenhum documento" /> : (
        <div className="table-wrap card"><table className="stack">
          <thead><tr><th scope="col">Documento</th><th scope="col">Tipo</th><th scope="col">Competência</th><th scope="col">Versões</th></tr></thead>
          <tbody>{docs.data.items.map((d) => (
            <tr key={d.id}>
              <th scope="row">{d.title}<span className="muted small"><br />enviado por {d.created_by_name}</span></th>
              <td data-label="Tipo">{DOC_KIND[d.kind] ?? d.kind}</td>
              <td data-label="Competência">{monthBr(d.competence)}</td>
              <td data-label="Versões">{d.versions.map((v) => (
                <span key={v.version_no} className="small">v{v.version_no}: {v.file ? <DocLink d={v.file} /> : "—"} · {v.created_by_name}{v.reason ? ` (${v.reason})` : ""}<br /></span>
              ))}</td>
            </tr>
          ))}</tbody>
        </table></div>
      )}
    </section>
  );
}

function UploadForm({ requests, onDone }: { requests: DocumentRequest[]; onDone: (m: string) => void }) {
  const [f, setF] = useState({ kind: "OUTRO", title: "", competence: "", request: "" });
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!file) {
      setError(new ApiError({ status: 422, code: "VALIDATION_ERROR", detail: "Escolha o arquivo (só o nome não basta).", errors: [{ field: "file", message: "Obrigatório" }] }));
      return;
    }
    const fields: Record<string, string> = { kind: f.kind, title: f.title };
    if (f.competence) fields.competence = f.competence;
    if (f.request) fields.request_id = f.request;
    try {
      await uploadFile("/api/v1/documents/uploads", file, fields);
    } catch (err) {
      setError(asError(err));
      return;
    }
    onDone("Documento enviado.");
  };
  return (
    <form className="card form-grid" onSubmit={(e) => void submit(e)} noValidate aria-label="Enviar documento">
      <h2 className="h3 span-2">Enviar documento</h2>
      <Field id="dc-tipo" label="Tipo *" error={fieldError(error, "kind")}>
        <select id="dc-tipo" value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value })}>
          {Object.entries(DOC_KIND).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
      </Field>
      <Field id="dc-titulo" label="Título *" error={fieldError(error, "title")}>
        <input id="dc-titulo" maxLength={160} value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} />
      </Field>
      <Field id="dc-comp" label="Competência" error={fieldError(error, "competence")}>
        <input id="dc-comp" type="month" value={f.competence} onChange={(e) => setF({ ...f, competence: e.target.value })} />
      </Field>
      {requests.length > 0 && (
        <Field id="dc-sol" label="Atende à solicitação">
          <select id="dc-sol" value={f.request} onChange={(e) => setF({ ...f, request: e.target.value })}>
            <option value="">Nenhuma</option>{requests.map((r) => <option key={r.id} value={r.id}>{r.title}</option>)}
          </select>
        </Field>
      )}
      <Field id="dc-arq" label="Arquivo *" error={fieldError(error, "file")}>
        <input id="dc-arq" type="file" accept="application/pdf,image/png,image/jpeg" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
      </Field>
      <FormError error={error} />
      <div className="actions span-2"><button className="btn btn-primary">Enviar</button></div>
    </form>
  );
}
