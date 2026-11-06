package com.wasel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;

/** Local, read-only Office preview. It deliberately renders content, not active macros or embedded objects. */
public final class OfficePreviewService {
    public static final long MAX_INPUT_BYTES = 32L * 1024 * 1024;
    private static final int MAX_OUTPUT_CHARS = 2_000_000;
    private static final int MAX_ROWS = 20_000;
    private static final int MAX_COLUMNS = 512;
    private static final int MAX_SLIDES = 500;

    static {
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(64L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(MAX_OUTPUT_CHARS);
    }

    private OfficePreviewService() { }

    public static boolean supports(String filename) {
        String ext = extension(filename);
        return switch (ext) { case "docx", "doc", "xlsx", "xls", "pptx", "ppt" -> true; default -> false; };
    }

    public static String render(Path file, String filename) throws IOException {
        if (!supports(filename)) throw new IOException("صيغة Office هذه غير مدعومة للمعاينة.");
        long size = Files.size(file);
        if (size > MAX_INPUT_BYTES) throw new IOException("حجم مستند Office يتجاوز حد المعاينة 32 ميغابايت.");
        String ext = extension(filename);
        BoundedHtml html = new BoundedHtml();
        html.add("<!doctype html><html lang=\"ar\" dir=\"auto\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>body{font:15px 'Segoe UI',Arial,sans-serif;color:#263b38;background:#fff;padding:20px;line-height:1.65}.preview-note{padding:8px;background:#fff8df;border:1px solid #e8d896;border-radius:6px;color:#66581f}h1,h2{color:#16785c}table{border-collapse:collapse;width:100%;margin:12px 0}td,th{border:1px solid #bccac6;padding:6px;vertical-align:top;white-space:pre-wrap;overflow-wrap:anywhere}section{margin:14px 0;padding:12px;border:1px solid #dbe5e2;border-radius:8px}pre{white-space:pre-wrap;overflow-wrap:anywhere}</style><body><p class=\"preview-note\">معاينة محلية للمحتوى النصي والجداول. قد لا تظهر بعض الصور والرسومات والتنسيقات المتقدمة كما في Microsoft Office.</p>");
        try {
            switch (ext) {
                case "docx" -> renderDocx(file, html);
                case "doc" -> renderDoc(file, html);
                case "xlsx", "xls" -> renderWorkbook(file, html);
                case "pptx" -> renderPptx(file, html);
                case "ppt" -> renderPpt(file, html);
                default -> throw new IOException("صيغة Office غير مدعومة.");
            }
        } catch (IOException ex) { throw ex; }
        catch (Exception ex) { throw new IOException("تعذرت معاينة مستند Office. قد يكون الملف تالفاً أو محمياً بكلمة مرور.", ex); }
        html.add("</body></html>");
        return html.toString();
    }

    private static void renderDocx(Path file, BoundedHtml out) throws Exception {
        try (InputStream in = Files.newInputStream(file); XWPFDocument document = new XWPFDocument(in)) {
            for (var body : document.getBodyElements()) {
                if (body instanceof XWPFParagraph paragraph) paragraph(out, paragraph.getText(), paragraph.getStyle());
                else if (body instanceof XWPFTable table) {
                    out.add("<table>");
                    for (var row : table.getRows()) { out.add("<tr>"); for (XWPFTableCell cell : row.getTableCells()) { out.add("<td>"); out.text(cell.getText()); out.add("</td>"); } out.add("</tr>"); }
                    out.add("</table>");
                }
            }
        }
    }

    private static void renderDoc(Path file, BoundedHtml out) throws Exception {
        try (InputStream in = Files.newInputStream(file); HWPFDocument document = new HWPFDocument(in); WordExtractor extractor = new WordExtractor(document)) {
            for (String paragraph : extractor.getParagraphText()) paragraph(out, paragraph, "");
        }
    }

    private static void renderWorkbook(Path file, BoundedHtml out) throws Exception {
        try (InputStream in = Files.newInputStream(file); Workbook workbook = WorkbookFactory.create(in)) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            for (Sheet sheet : workbook) {
                out.add("<h2>"); out.text(sheet.getSheetName()); out.add("</h2><table>");
                int rows = 0;
                for (Row row : sheet) {
                    if (++rows > MAX_ROWS) { out.add("<tr><td>تم إيقاف المعاينة عند حد الصفوف.</td></tr>"); break; }
                    out.add("<tr>"); int cells = 0;
                    for (Cell cell : row) {
                        if (++cells > MAX_COLUMNS) break;
                        out.add("<td>"); out.text(formatter.formatCellValue(cell)); out.add("</td>");
                    }
                    out.add("</tr>");
                }
                out.add("</table>");
            }
        }
    }

    private static void renderPptx(Path file, BoundedHtml out) throws Exception {
        try (InputStream in = Files.newInputStream(file); XMLSlideShow deck = new XMLSlideShow(in)) {
            int index = 0;
            for (XSLFSlide slide : deck.getSlides()) {
                if (++index > MAX_SLIDES) { out.add("<p>تم إيقاف المعاينة عند حد الشرائح.</p>"); break; }
                out.add("<section><h2>الشريحة "); out.text(Integer.toString(index)); out.add("</h2>");
                for (XSLFShape shape : slide.getShapes()) if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape text) paragraph(out, text.getText(), "");
                out.add("</section>");
            }
        }
    }

    private static void renderPpt(Path file, BoundedHtml out) throws Exception {
        try (InputStream in = Files.newInputStream(file); HSLFSlideShow deck = new HSLFSlideShow(in)) {
            int index = 0;
            for (HSLFSlide slide : deck.getSlides()) {
                if (++index > MAX_SLIDES) { out.add("<p>تم إيقاف المعاينة عند حد الشرائح.</p>"); break; }
                out.add("<section><h2>الشريحة "); out.text(Integer.toString(index)); out.add("</h2>");
                for (HSLFShape shape : slide.getShapes()) if (shape instanceof org.apache.poi.hslf.usermodel.HSLFTextShape text) paragraph(out, text.getText(), "");
                out.add("</section>");
            }
        }
    }

    private static void paragraph(BoundedHtml out, String text, String style) {
        String tag = style != null && style.toLowerCase(Locale.ROOT).contains("heading") ? "h2" : "p";
        out.add("<" + tag + ">"); out.text(text); out.add("</" + tag + ">");
    }

    private static String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.'); return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static final class BoundedHtml {
        private final StringBuilder value = new StringBuilder();
        void add(String text) { ensure(text.length()); value.append(text); }
        void text(String text) {
            if (text == null) return;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                switch (ch) { case '&' -> add("&amp;"); case '<' -> add("&lt;"); case '>' -> add("&gt;"); case '"' -> add("&quot;"); case '\'' -> add("&#39;"); default -> add(String.valueOf(ch)); }
            }
        }
        private void ensure(int amount) { if ((long) value.length() + amount > MAX_OUTPUT_CHARS) throw new IllegalArgumentException("المحتوى أكبر من حد المعاينة."); }
        @Override public String toString() { return value.toString(); }
    }
}
