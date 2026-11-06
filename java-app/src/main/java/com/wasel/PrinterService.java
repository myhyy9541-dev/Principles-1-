package com.wasel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.Graphics2D;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.PDFRenderer;

/** Printing adapter limited to queues installed in Windows and files with explicit safe rendering paths. */
public final class PrinterService {
    public static final long MAX_JOB_BYTES = 32L * 1024 * 1024;
    private static final int MAX_PDF_PAGES = 200;
    private PrinterService() { }

    public static JsonArray list() {
        JsonArray out = new JsonArray();
        PrintService defaultService = PrintServiceLookup.lookupDefaultPrintService();
        for (PrintService service : PrintServiceLookup.lookupPrintServices(null, null)) {
            JsonObject item = new JsonObject(); item.addProperty("id", stableId(service)); item.addProperty("name", service.getName());
            JsonArray formats = new JsonArray(); for (String format : new String[]{"PDF","DOCX/DOC","XLSX/XLS","PPTX/PPT","PNG/JPEG","TXT"}) formats.add(format);
            item.add("formats", formats); item.addProperty("default", service.equals(defaultService)); out.add(item);
        }
        return out;
    }

    public static String stableId(PrintService service) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(service.getName().strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return "p-" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    public static String submit(Path file, String filename, String printerId) throws Exception {
        long length=Files.size(file);
        if(length<1||length>MAX_JOB_BYTES)throw new IOException("حجم مهمة الطباعة يجب أن يكون بين 1 بايت و32 ميغابايت.");
        String lower=filename==null?"":filename.toLowerCase(Locale.ROOT);
        PrintService service=find(printerId); if(service==null)throw new IOException("الطابعة غير موجودة أو تغيّر معرّفها؛ حدّث قائمة الطابعات.");
        if(lower.endsWith(".pdf")) printPdf(file,service);
        else if(lower.endsWith(".png")||lower.endsWith(".jpg")||lower.endsWith(".jpeg")||lower.endsWith(".gif")) printImage(file,service);
        else if(lower.endsWith(".txt")) printHtml("<!doctype html><html><meta charset=\"utf-8\"><body><pre>"+escape(Files.readString(file,StandardCharsets.UTF_8))+"</pre></body></html>",service);
        else if(OfficePreviewService.supports(lower)) printHtml(OfficePreviewService.render(file,lower),service);
        else throw new IOException("الطباعة تدعم PDF وملفات Office الحديثة والقديمة وصور PNG/JPEG/GIF وTXT فقط.");
        return "أُرسلت المهمة إلى قائمة طباعة Windows: "+service.getName()+"؛ تحقق من قائمة الطباعة للتأكد من التنفيذ.";
    }

    private static void printPdf(Path file,PrintService service)throws Exception{
        try(PDDocument document=Loader.loadPDF(file.toFile())){
            if(document.getNumberOfPages()<1||document.getNumberOfPages()>MAX_PDF_PAGES)throw new IOException("ملف PDF يجب أن يحتوي من صفحة إلى 200 صفحة للطباعة.");
            PDFRenderer renderer=new PDFRenderer(document); PrinterJob job=job(service,"واصل PDF");
            job.setPrintable((graphics,pageFormat,pageIndex)->{
                if(pageIndex>=document.getNumberOfPages())return Printable.NO_SUCH_PAGE;
                PDPage page=document.getPage(pageIndex);double pageWidth=page.getCropBox().getWidth(),pageHeight=page.getCropBox().getHeight();
                double scale=Math.min(pageFormat.getImageableWidth()/pageWidth,pageFormat.getImageableHeight()/pageHeight);
                Graphics2D g=(Graphics2D)graphics.create();
                try{double renderedWidth=pageWidth*scale,renderedHeight=pageHeight*scale;g.translate(pageFormat.getImageableX()+(pageFormat.getImageableWidth()-renderedWidth)/2,pageFormat.getImageableY()+(pageFormat.getImageableHeight()-renderedHeight)/2);renderer.renderPageToGraphics(pageIndex,g,(float)scale,(float)scale);return Printable.PAGE_EXISTS;}
                catch(IOException ex){PrinterException failure=new PrinterException("تعذر تصيير صفحة PDF للطباعة.");failure.initCause(ex);throw failure;}
                finally{g.dispose();}
            }); job.print();
        }
    }

    private static void printImage(Path file,PrintService service)throws Exception{
        BufferedImage image=ImageIO.read(file.toFile()); if(image==null)throw new IOException("تعذرت قراءة الصورة للطباعة.");
        PrinterJob job=job(service,"واصل صورة");
        job.setPrintable((graphics,format,page)->{
            if(page>0)return Printable.NO_SUCH_PAGE;
            Graphics2D g=(Graphics2D)graphics.create();
            try{
                double scale=Math.min(format.getImageableWidth()/image.getWidth(),format.getImageableHeight()/image.getHeight());
                int width=(int)Math.round(image.getWidth()*scale),height=(int)Math.round(image.getHeight()*scale);
                g.translate(format.getImageableX()+(format.getImageableWidth()-width)/2,format.getImageableY()+(format.getImageableHeight()-height)/2);
                g.scale(width/(double)image.getWidth(),height/(double)image.getHeight());g.drawImage(image,0,0,null);return Printable.PAGE_EXISTS;
            }finally{g.dispose();}
        });job.print();
    }

    private static void printHtml(String html,PrintService service)throws Exception{
        javax.swing.JEditorPane pane=new javax.swing.JEditorPane();pane.setEditable(false);pane.setContentType("text/html; charset=UTF-8");pane.setText(html);
        PrinterJob job=job(service,"واصل مستند");
        job.setPrintable((graphics,format,page)->{
            Graphics2D g=(Graphics2D)graphics.create();
            try{
                double scale=0.75;int width=(int)Math.floor(format.getImageableWidth()/scale),pageHeight=(int)Math.floor(format.getImageableHeight()/scale);
                pane.setSize(width,32767);int documentHeight=Math.max(1,pane.getPreferredSize().height);
                if(page*pageHeight>=documentHeight)return Printable.NO_SUCH_PAGE;
                g.translate(format.getImageableX(),format.getImageableY());g.scale(scale,scale);g.translate(0,-page*pageHeight);pane.printAll(g);return Printable.PAGE_EXISTS;
            }finally{g.dispose();}
        });job.print();
    }

    private static PrinterJob job(PrintService service,String title)throws Exception{PrinterJob job=PrinterJob.getPrinterJob();job.setPrintService(service);job.setJobName(title);return job;}
    private static String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
    private static PrintService find(String id){if(id==null||id.isBlank())return null;for(PrintService service:PrintServiceLookup.lookupPrintServices(null,null))if(id.equals(stableId(service)))return service;return null;}
}
