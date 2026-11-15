// Datas no fuso comercial da empresa (America/Sao_Paulo), independentemente do fuso do computador.
const DT = new Intl.DateTimeFormat("pt-BR", { timeZone: "America/Sao_Paulo", dateStyle: "short", timeStyle: "short" });
export const formatDateTime = (iso: string) => DT.format(new Date(iso));
