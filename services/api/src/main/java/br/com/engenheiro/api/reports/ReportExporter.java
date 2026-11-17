package br.com.engenheiro.api.reports;

import br.com.engenheiro.api.finance.FinanceReportService;
import br.com.engenheiro.api.reports.ReportService.Report;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Arquivos do relatório (REP-06): CSV, XLSX e PDF são escritos a partir das MESMAS seções ({@link #sections}), que por sua
 * vez só leem o {@link Report} já filtrado pela permissão — campo oculto não existe no objeto, logo não chega ao arquivo.
 */
@Component
public class ReportExporter {
    enum Kind { TEXT, MONEY, QTY, PERCENT }

    record Cell(String text, BigDecimal number, Kind kind) {
        static Cell text(String s) {
            return new Cell(s == null ? "" : s, null, Kind.TEXT);
        }

        static Cell money(String s) {
            return s == null ? text("—") : new Cell(s, new BigDecimal(s), Kind.MONEY);
        }

        static Cell qty(String s) {
            return s == null ? text("—") : new Cell(s, new BigDecimal(s), Kind.QTY);
        }

        static Cell pct(String s) {
            return s == null ? text("—") : new Cell(s, new BigDecimal(s), Kind.PERCENT);
        }
    }

    record Section(String title, List<String> headers, List<List<Cell>> rows) {
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // ---------------- seções comuns ----------------
    static List<String[]> meta(Report r) {
        List<String[]> m = new ArrayList<>();
        m.add(new String[] {"Período", r.label() + " (" + DMY.format(r.from()) + " a " + DMY.format(r.to().minusDays(1)) + ")"});
        m.add(new String[] {"Unidade", r.branch_name() == null ? "Todas" : r.branch_name()});
        m.add(new String[] {"Base", "Competência · fuso " + r.timezone() + " · política " + r.policy_version()});
        m.add(new String[] {"Origem", r.source().equals("SNAPSHOT") ? "Retrato do mês fechado" : "Livro (aberto)"});
        m.add(new String[] {"Gerado por", r.generated_by() + " em "
                + WHEN.format(r.generated_at().atZoneSameInstant(ZoneId.of(ReportService.TIMEZONE)))});
        if (!r.hidden().isEmpty()) {
            m.add(new String[] {"Campos ocultos", "CMV, perdas, total de custos, lucro e margem (sua permissão)"});
        }
        return m;
    }

    static List<Section> sections(Report r) {
        List<Section> out = new ArrayList<>();
        ReportService.Result x = r.result();
        List<List<Cell>> close = new ArrayList<>();
        close.add(List.of(Cell.text("Vendas cumpridas (bruto)"), Cell.money(x.gross_revenue())));
        close.add(List.of(Cell.text("(-) Descontos"), Cell.money(x.discounts())));
        close.add(List.of(Cell.text("(-) Devoluções"), Cell.money(x.returns())));
        close.add(List.of(Cell.text("Receita líquida"), Cell.money(x.net_revenue())));
        if (r.hidden().isEmpty()) {
            close.add(List.of(Cell.text("(-) CMV"), Cell.money(x.cogs())));
        }
        close.add(List.of(Cell.text("(-) Despesas"), Cell.money(x.expenses_total())));
        if (r.hidden().isEmpty()) {
            close.add(List.of(Cell.text("(-) Perdas"), Cell.money(x.losses())));
            close.add(List.of(Cell.text("CMV + despesas + perdas"), Cell.money(x.costs_expenses_losses())));
            close.add(List.of(Cell.text(x.profit().startsWith("-") ? "Prejuízo" : "Lucro"), Cell.money(x.profit())));
            close.add(List.of(Cell.text("Margem sobre a receita líquida (%)"), Cell.pct(x.margin_percent())));
        }
        out.add(new Section("Fechamento", List.of("Linha", "Valor (R$)"), close));

        List<List<Cell>> cats = new ArrayList<>();
        for (FinanceReportService.Item e : r.expenses()) {
            cats.add(List.of(Cell.text(e.label()), Cell.money(e.amount())));
        }
        cats.add(List.of(Cell.text("Despesas totais"), Cell.money(x.expenses_total())));
        out.add(new Section("Despesas por categoria", List.of("Categoria", "Valor (R$)"), cats));

        List<List<Cell>> subs = new ArrayList<>();
        for (FinanceReportService.Sub s : r.subcategories()) {
            subs.add(List.of(Cell.text(FinanceReportService.label(s.category())), Cell.text(s.subcategory()),
                    Cell.text(String.valueOf(s.entries())), Cell.money(s.amount())));
        }
        out.add(new Section("Subcategorias", List.of("Categoria", "Subcategoria", "Lançamentos", "Valor (R$)"), subs));

        List<List<Cell>> sold = new ArrayList<>();
        for (ReportService.UnitQty u : r.sold().by_unit()) {
            sold.add(List.of(Cell.text(u.unit()), Cell.qty(u.sold()), Cell.qty(u.backlog())));
        }
        out.add(new Section("Quantidade vendida (" + r.sold().orders() + " pedidos)", List.of("Unidade-base", "Vendida", "Em carteira"), sold));

        List<String> th = new ArrayList<>(List.of("Período", "Vendas (R$)", "Despesas (R$)"));
        if (r.hidden().isEmpty()) {
            th.addAll(List.of("CMV (R$)", "Perdas (R$)", "Resultado (R$)"));
        }
        List<List<Cell>> tl = new ArrayList<>();
        for (ReportService.Point p : r.timeline()) {
            List<Cell> row = new ArrayList<>(List.of(Cell.text(p.label()), Cell.money(p.net_revenue()), Cell.money(p.expenses_total())));
            if (r.hidden().isEmpty()) {
                row.addAll(List.of(Cell.money(p.cogs()), Cell.money(p.losses()), Cell.money(p.profit())));
            }
            tl.add(row);
        }
        out.add(new Section("Linha do tempo (" + bucket(r.timeline_bucket()) + ")", th, tl));

        List<List<Cell>> cmp = new ArrayList<>();
        for (ReportService.Delta d : r.comparison().items()) {
            boolean q = d.key().equals("sold_quantity");
            cmp.add(List.of(Cell.text(d.label()), q ? Cell.qty(d.current()) : Cell.money(d.current()),
                    q ? Cell.qty(d.previous()) : Cell.money(d.previous()), q ? Cell.qty(d.difference()) : Cell.money(d.difference()),
                    Cell.pct(d.percent())));
        }
        out.add(new Section("Comparação com " + r.comparison().label(), List.of("Indicador", "Atual", "Anterior", "Diferença", "Variação %"), cmp));
        return out;
    }

    static String bucket(String b) {
        return switch (b) {
            case "DAY" -> "por dia";
            case "WEEK" -> "por semana";
            default -> "por mês";
        };
    }

    // ---------------- CSV ----------------
    /** CSV para planilha brasileira: `;` como separador, vírgula decimal, sem milhar, UTF-8 com BOM. */
    public byte[] csv(Report r) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(line("Relatório gerencial — Distribuidora de Cana Engenheiro"));
        for (String[] m : meta(r)) {
            sb.append(line(m[0], m[1]));
        }
        for (Section s : sections(r)) {
            sb.append("\r\n").append(line(s.title()));
            sb.append(line(s.headers().toArray(String[]::new)));
            for (List<Cell> row : s.rows()) {
                sb.append(line(row.stream().map(c -> c.kind() == Kind.TEXT ? c.text() : c.text().replace('.', ',')).toArray(String[]::new)));
            }
        }
        sb.append("\r\n").append(line("Fórmula", r.formula()));
        sb.append(line("Observação", r.note()));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String line(String... cells) {
        List<String> out = new ArrayList<>();
        for (String c : cells) {
            String v = c == null ? "" : c;
            // Neutraliza fórmula em planilha (CSV injection) e escapa aspas/separador.
            if (!v.isEmpty() && "=+@\t\r".indexOf(v.charAt(0)) >= 0) {
                v = "'" + v;
            }
            out.add(v.contains(";") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v);
        }
        return String.join(";", out) + "\r\n";
    }

    // ---------------- XLSX ----------------
    public byte[] xlsx(Report r) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            CellStyle qty = wb.createCellStyle();
            qty.setDataFormat(wb.createDataFormat().getFormat("#,##0.000"));
            CellStyle bold = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(true);
            bold.setFont(f);
            Sheet resumo = wb.createSheet("Resumo");
            int i = 0;
            resumo.createRow(i++).createCell(0).setCellValue("Relatório gerencial — Distribuidora de Cana Engenheiro");
            for (String[] m : meta(r)) {
                Row row = resumo.createRow(i++);
                row.createCell(0).setCellValue(m[0]);
                row.createCell(1).setCellValue(m[1]);
            }
            List<Section> secs = sections(r);
            for (int s = 0; s < secs.size(); s++) {
                Section sec = secs.get(s);
                Sheet sh = s < 2 ? resumo : wb.createSheet(sheetName(sec.title()));
                int j = s < 2 ? i + 1 : 0;
                Row title = sh.createRow(j++);
                title.createCell(0).setCellValue(sec.title());
                title.getCell(0).setCellStyle(bold);
                Row head = sh.createRow(j++);
                for (int h = 0; h < sec.headers().size(); h++) {
                    head.createCell(h).setCellValue(sec.headers().get(h));
                    head.getCell(h).setCellStyle(bold);
                }
                for (List<Cell> cells : sec.rows()) {
                    Row row = sh.createRow(j++);
                    for (int c = 0; c < cells.size(); c++) {
                        Cell cell = cells.get(c);
                        var xc = row.createCell(c);
                        if (cell.number() == null) {
                            xc.setCellValue(cell.text());
                        } else {
                            // Valor exibido com 2 (ou 6) casas a partir do decimal exato; o texto exato fica no CSV/API.
                            xc.setCellValue(cell.number().doubleValue());
                            xc.setCellStyle(cell.kind() == Kind.QTY ? qty : money);
                        }
                    }
                }
                if (s < 2) {
                    i = j;
                }
                sh.setColumnWidth(0, 40 * 256);
                sh.setColumnWidth(1, 22 * 256);
            }
            Row fr = resumo.createRow(i + 1);
            fr.createCell(0).setCellValue("Fórmula");
            fr.createCell(1).setCellValue(r.formula());
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String sheetName(String title) {
        String t = title.replaceAll("[\\\\/?*\\[\\]:]", " ");
        return t.length() > 31 ? t.substring(0, 31) : t;
    }

    // ---------------- PDF ----------------
    /** PDF de impressão: mesmas seções, com as colunas do fechamento em 2D (altura proporcional, escala única). */
    public byte[] pdf(Report r) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Pdf p = new Pdf(doc);
            p.text("Relatório gerencial — Distribuidora de Cana Engenheiro", 14, true);
            p.gap(4);
            for (String[] m : meta(r)) {
                p.text(m[0] + ": " + m[1], 9, false);
            }
            p.gap(8);
            p.bars(r.chart());
            for (Section s : sections(r)) {
                p.gap(8);
                p.text(s.title(), 11, true);
                p.row(s.headers(), true);
                for (List<Cell> row : s.rows()) {
                    p.row(row.stream().map(c -> c.kind() == Kind.MONEY ? brl(c.number()) : c.kind() == Kind.QTY
                            ? num(c.number(), 3) : c.kind() == Kind.PERCENT ? num(c.number(), 2) + "%" : c.text()).toList(), false);
                }
                if (s.rows().isEmpty()) {
                    p.text("Sem lançamentos no período.", 9, false);
                }
            }
            p.gap(8);
            p.wrap("Fórmula: " + r.formula(), 8);
            p.wrap(r.note(), 8);
            p.close();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String brl(BigDecimal v) {
        return (v.signum() < 0 ? "-R$ " : "R$ ") + num(v.abs(), 2);
    }

    static String num(BigDecimal v, int scale) {
        DecimalFormat f = new DecimalFormat(scale == 2 ? "#,##0.00" : "#,##0.000", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("pt-BR")));
        f.setParseBigDecimal(true);
        return f.format(v);
    }

    /** Escritor simples de páginas A4 com quebra automática. */
    static final class Pdf {
        final PDDocument doc;
        final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDPageContentStream cs;
        float y;
        static final float MARGIN = 40;
        static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;

        Pdf(PDDocument doc) throws IOException {
            this.doc = doc;
            newPage();
        }

        void newPage() throws IOException {
            if (cs != null) {
                cs.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = PDRectangle.A4.getHeight() - MARGIN;
        }

        void need(float h) throws IOException {
            if (y - h < MARGIN) {
                newPage();
            }
        }

        void gap(float h) {
            y -= h;
        }

        String safe(PDType1Font font, String s) {
            StringBuilder b = new StringBuilder();
            for (char c : s.toCharArray()) {
                String ch = c == '−' || c == '–' ? "-" : String.valueOf(c);
                try {
                    font.encode(ch);
                    b.append(ch);
                } catch (IOException | IllegalArgumentException e) {
                    b.append('?');
                }
            }
            return b.toString();
        }

        void at(float x, float yy, String s, float size, boolean strong) throws IOException {
            PDType1Font font = strong ? bold : regular;
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(x, yy);
            cs.showText(safe(font, s));
            cs.endText();
        }

        float width(String s, float size, boolean strong) throws IOException {
            PDType1Font font = strong ? bold : regular;
            return font.getStringWidth(safe(font, s)) / 1000 * size;
        }

        void text(String s, float size, boolean strong) throws IOException {
            need(size + 4);
            y -= size + 3;
            at(MARGIN, y, s, size, strong);
        }

        void wrap(String s, float size) throws IOException {
            StringBuilder lineB = new StringBuilder();
            for (String w : s.split(" ")) {
                if (width(lineB + " " + w, size, false) > WIDTH && lineB.length() > 0) {
                    text(lineB.toString(), size, false);
                    lineB.setLength(0);
                }
                lineB.append(lineB.length() == 0 ? "" : " ").append(w);
            }
            if (lineB.length() > 0) {
                text(lineB.toString(), size, false);
            }
        }

        void row(List<String> cells, boolean header) throws IOException {
            need(13);
            y -= 12;
            int n = cells.size();
            float first = n == 2 ? WIDTH * 0.6f : WIDTH * 0.3f;
            float rest = n > 1 ? (WIDTH - first) / (n - 1) : 0;
            for (int i = 0; i < n; i++) {
                String c = cells.get(i);
                if (i == 0) {
                    at(MARGIN, y, c, 8.5f, header);
                } else {
                    float right = MARGIN + first + rest * i;
                    at(right - width(c, 8.5f, header), y, c, 8.5f, header);
                }
            }
        }

        /** Colunas 2D: altura = |valor| na mesma escala única; valor negativo (prejuízo) em vermelho com o sinal no rótulo. */
        void bars(List<ReportService.Column> cols) throws IOException {
            if (cols.isEmpty()) {
                return;
            }
            BigDecimal max = cols.stream().map(c -> new BigDecimal(c.amount()).abs()).reduce(BigDecimal.ONE, BigDecimal::max);
            float h = 110;
            need(h + 40);
            float base = y - h - 6;
            float w = WIDTH / cols.size();
            for (int i = 0; i < cols.size(); i++) {
                ReportService.Column c = cols.get(i);
                BigDecimal v = new BigDecimal(c.amount());
                float bh = v.abs().multiply(BigDecimal.valueOf(h)).divide(max, 4, java.math.RoundingMode.HALF_UP).floatValue();
                float x = MARGIN + w * i + w * 0.2f;
                cs.setNonStrokingColor(v.signum() < 0 ? 0.75f : 0.15f, v.signum() < 0 ? 0.3f : 0.55f, v.signum() < 0 ? 0.3f : 0.45f);
                cs.addRect(x, base, w * 0.6f, Math.max(bh, 0.5f));
                cs.fill();
                cs.setNonStrokingColor(0f, 0f, 0f);
                String val = brl(v);
                at(x, base + bh + 3, val, 7.5f, true);
                at(x, base - 10, c.label(), 7.5f, false);
            }
            y = base - 16;
        }

        void close() throws IOException {
            cs.close();
        }
    }
}
