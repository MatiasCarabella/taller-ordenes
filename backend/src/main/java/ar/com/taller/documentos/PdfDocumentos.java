package ar.com.taller.documentos;

import static ar.com.taller.documentos.DocumentosService.*;
import ar.com.taller.operacion.NegocioException;
import java.awt.Color;
import java.io.*;
import java.math.BigDecimal;
import java.text.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class PdfDocumentos {
    private static final Color GREEN=new Color(25,65,57),GRAY=new Color(78,98,91),PALE=new Color(237,244,238),LINE=new Color(210,222,215);
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("America/Argentina/Buenos_Aires"));
    private static String date(Instant i){return i==null?"Sin registro":DATE.format(i);}
    private static String decimal(BigDecimal b){
        var f=new DecimalFormat("#,##0.00",DecimalFormatSymbols.getInstance(Locale.forLanguageTag("es-AR")));
        return f.format(b);
    }
    private static String decision(String d){return switch(d){case "ACEPTADO"->"Aceptado";case "RECHAZADO"->"Rechazado";case "REVISION"->"Revisión solicitada";default->"Pendiente de respuesta";};}
    public byte[] generar(Documento data){
        try(var doc=new PDDocument();var buffer=new ByteArrayOutputStream()){
            var info=doc.getDocumentInformation();info.setTitle((data.resumen()?"Resumen final":"Presupuesto")+" - Orden "+data.orden().id()+" - Versión "+data.version().numero());
            info.setAuthor("Taller - Gestión de órdenes");info.setCreator("Taller");
            var calendar=GregorianCalendar.from(data.generado().atZone(ZoneOffset.UTC));info.setCreationDate(calendar);
            try(var fontInput=PdfDocumentos.class.getResourceAsStream("/fonts/DejaVuSans.ttf")){
                if(fontInput==null)throw new IOException("Missing bundled font");
                var font=PDType0Font.load(doc,fontInput,true);
                try(var layout=new Layout(doc,font,data)){
                    var o=data.orden();var v=data.version();
                    layout.field("Cliente",o.cliente());
                    layout.field("Vehículo",o.dominio()+" | "+o.marca()+" "+o.modelo());
                    layout.field("Responsable",o.responsable());
                    if(data.resumen()){
                        layout.field("Estado del servicio",o.estado().equals("ENTREGADO")?"Entregado":"Finalizado");
                        layout.field("Recepción",date(o.recibido()));
                        layout.field("Inicio de reparación",date(o.iniciado()));
                        layout.field("Finalización",date(o.finalizado()));
                        if(o.entregado()!=null)layout.field("Entrega",date(o.entregado()));
                        layout.section("Diagnóstico",o.diagnostico());
                        layout.section("Trabajo final realizado",o.trabajo());
                        layout.scope("Alcance autorizado - versión "+v.numero(),
                                "Aceptado por "+v.decisor()+" el "+date(v.decidido())+".",v.renglones());
                        layout.total("Importe final acordado (ARS)",o.ingresoFinal());
                    }else{
                        layout.field("Versión publicada",Integer.toString(v.numero()));
                        layout.field("Publicación",date(v.publicado())+" | "+v.autor());
                        layout.field("Plazo de respuesta",date(v.vence()));
                        layout.field("Decisión registrada",decision(v.decision()));
                        if(v.decidido()!=null)layout.field("Respuesta",date(v.decidido())+" | "+v.decisor());
                        layout.field("Autorización operativa al generar",o.versionAutorizada()==null?"Sin aceptación registrada":"Versión "+o.versionAutorizada());
                        layout.section("Motivo de ingreso",o.motivo());
                        layout.section("Diagnóstico",o.diagnostico());
                        layout.scope("Detalle completo de la propuesta",null,v.renglones());
                        layout.total("Total de esta versión (ARS)",v.total());
                        layout.paragraph("Cada versión contiene el alcance completo. Las decisiones se realizan con una sesión autenticada en el portal; una propuesta sin aceptar no habilita trabajo adicional.",9,GRAY);
                    }
                    layout.paragraph("Fechas expresadas en hora de Buenos Aires. Generado el "+date(data.generado())+".",8,GRAY);
                    if(layout.substituted)layout.paragraph("Los caracteres sin representación tipográfica se indican mediante su código [U+...].",8,GRAY);
                }
            }
            doc.save(buffer);return buffer.toByteArray();
        }catch(IOException e){throw new NegocioException(HttpStatus.SERVICE_UNAVAILABLE,"PDF_NO_DISPONIBLE","No se pudo generar el documento. Volvé a intentarlo.");}
    }
    private static final class Layout implements AutoCloseable {
        private final PDDocument doc;private final PDType0Font font;private final Documento data;
        private final Map<Integer,String> glyphs=new HashMap<>();
        private PDPageContentStream stream;private float y;private boolean substituted=false;
        private static final float LEFT=42,WIDTH=PDRectangle.A4.getWidth()-84,BOTTOM=64;
        private static final float[] COL={32,58,158,65,99,WIDTH-412};
        Layout(PDDocument doc,PDType0Font font,Documento data)throws IOException{this.doc=doc;this.font=font;this.data=data;page();}
        private String safe(String text){
            var out=new StringBuilder();String normalized=Normalizer.normalize(Objects.toString(text,""),Normalizer.Form.NFC);
            normalized.codePoints().forEach(cp->{
                if(cp=='\n'||cp=='\r'){out.append('\n');return;}
                if(Character.isWhitespace(cp)){out.append(' ');return;}
                if(Character.isISOControl(cp))return;
                out.append(glyphs.computeIfAbsent(cp,key->{
                    String s=new String(Character.toChars(key));
                    try{font.encode(s);return s;}catch(IllegalArgumentException|IOException e){substituted=true;return "[U+"+Integer.toHexString(key).toUpperCase(Locale.ROOT)+"]";}
                }));
            });return out.toString();
        }
        private float width(String s,float size)throws IOException{return font.getStringWidth(s)/1000*size;}
        private void text(String s,float x,float baseline,float size,Color color,boolean strong)throws IOException{
            stream.beginText();stream.setFont(font,size);stream.setNonStrokingColor(color);
            stream.setRenderingMode(strong?RenderingMode.FILL_STROKE:RenderingMode.FILL);stream.setStrokingColor(color);stream.setLineWidth(.18f);
            stream.newLineAtOffset(x,baseline);stream.showText(s);stream.endText();
        }
        private void page()throws IOException{
            if(stream!=null)stream.close();
            var page=new PDPage(PDRectangle.A4);doc.addPage(page);stream=new PDPageContentStream(doc,page);
            float top=PDRectangle.A4.getHeight();
            text("TALLER / GESTIÓN DE ÓRDENES",LEFT,top-37,8,GREEN,true);
            text(data.resumen()?"Resumen final de servicio":"Presupuesto de servicio",LEFT,top-68,22,GREEN,true);
            text("Orden #"+data.orden().id()+"  |  "+safe(data.orden().dominio())+"  |  Versión "+data.version().numero(),LEFT,top-88,9,GRAY,false);
            stream.setStrokingColor(LINE);stream.setLineWidth(.6f);stream.moveTo(LEFT,top-101);stream.lineTo(LEFT+WIDTH,top-101);stream.stroke();
            y=top-121;
        }
        private void ensure(float height)throws IOException{if(y-height<BOTTOM)page();}
        private List<String> wrap(String text,float size,float maxWidth)throws IOException{
            var lines=new ArrayList<String>();
            for(String paragraph:safe(text).split("\n",-1)){
                if(paragraph.isBlank()){lines.add("");continue;}
                String current="";
                for(String word:paragraph.strip().split(" +")){
                    String trial=current.isEmpty()?word:current+" "+word;
                    if(width(trial,size)<=maxWidth){current=trial;continue;}
                    if(!current.isEmpty()){lines.add(current);current="";}
                    for(int cp:word.codePoints().toArray()){
                        String letter=new String(Character.toChars(cp));
                        if(width(current+letter,size)>maxWidth&&!current.isEmpty()){lines.add(current);current="";}
                        current+=letter;
                    }
                }
                if(!current.isEmpty())lines.add(current);
            }return lines;
        }
        void paragraph(String text,float size,Color color)throws IOException{
            float leading=size*1.5f;
            for(String line:wrap(text,size,WIDTH)){ensure(leading);text(line,LEFT,y,size,color,false);y-=leading;}y-=8;
        }
        void field(String label,String value)throws IOException{
            var lines=wrap(label+": "+Objects.toString(value,"Sin registro"),9,WIDTH);
            ensure(Math.min(lines.size(),2)*14);
            for(String line:lines){ensure(14);text(line,LEFT,y,9,GRAY,false);y-=14;}y-=3;
        }
        void heading(String label)throws IOException{
            ensure(49);y-=8;text(safe(label),LEFT,y,12,GREEN,true);y-=23;
        }
        void section(String label,String content)throws IOException{heading(label);paragraph(content,10,GREEN);}
        private void tableHeader()throws IOException{
            stream.setNonStrokingColor(GREEN);stream.addRect(LEFT,y-29,WIDTH,29);stream.fill();
            String[] labels={"N°","Tipo","Descripción","Cantidad","P. unitario","Subtotal"};float x=LEFT;
            for(int i=0;i<labels.length;i++){text(labels[i],x+6,y-18,8,Color.WHITE,true);x+=COL[i];}y-=29;
        }
        private record TableRow(List<List<String>> cells,float height) {}
        private TableRow row(Renglon r)throws IOException{
            String[] values={Integer.toString(r.numero()),r.tipo().equals("REPUESTO")?"Repuesto":"Mano de obra",r.descripcion(),decimal(r.cantidad()),decimal(r.precio()),decimal(r.subtotal())};
            var cells=new ArrayList<List<String>>();int max=1;
            for(int i=0;i<values.length;i++){var lines=wrap(values[i],8.5f,COL[i]-12);cells.add(lines);max=Math.max(max,lines.size());}
            return new TableRow(cells,max*12+16);
        }
        void scope(String label,String note,List<Renglon> rows)throws IOException{
            float first=rows.isEmpty()?30:row(rows.getFirst()).height();
            float noteHeight=note==null?0:wrap(note,9,WIDTH).size()*13.5f+8;
            float ending=rows.size()==1?(data.resumen()?140:185):0;
            ensure(31+noteHeight+29+first+ending);heading(label);
            if(note!=null)paragraph(note,9,GRAY);
            table(rows);
        }
        void table(List<Renglon> rows)throws IOException{
            ensure(85);tableHeader();int index=0;
            for(var r:rows){
                var current=row(r);var cells=current.cells();float height=current.height();
                // Keep the last item with the total and closing notes on the same page.
                float following=index==rows.size()-1?(data.resumen()?140:185):0;
                if(y-height-following<BOTTOM){page();tableHeader();}
                if(index++%2==0){stream.setNonStrokingColor(PALE);stream.addRect(LEFT,y-height,WIDTH,height);stream.fill();}
                float x=LEFT;
                for(int i=0;i<cells.size();i++){
                    float baseline=y-14;
                    for(String line:cells.get(i)){float at=i>=3?x+COL[i]-6-width(line,8.5f):x+6;text(line,at,baseline,8.5f,GREEN,false);baseline-=12;}x+=COL[i];
                }
                stream.setStrokingColor(LINE);stream.setLineWidth(.4f);stream.moveTo(LEFT,y-height);stream.lineTo(LEFT+WIDTH,y-height);stream.stroke();y-=height;
            }y-=18;
        }
        void total(String label,BigDecimal amount)throws IOException{
            ensure(75);stream.setNonStrokingColor(PALE);stream.addRect(LEFT,y-51,WIDTH,51);stream.fill();
            text(label,LEFT+12,y-29,10,GREEN,false);String value=decimal(amount);text(value,LEFT+WIDTH-12-width(value,16),y-30,16,GREEN,true);y-=70;
        }
        @Override public void close()throws IOException{
            if(stream!=null){stream.close();stream=null;}
            for(int i=0;i<doc.getNumberOfPages();i++){
                stream=new PDPageContentStream(doc,doc.getPage(i),PDPageContentStream.AppendMode.APPEND,true,true);
                stream.setStrokingColor(LINE);stream.setLineWidth(.5f);stream.moveTo(LEFT,49);stream.lineTo(LEFT+WIDTH,49);stream.stroke();
                text("Taller | Importes de venta en ARS",LEFT,33,8,GRAY,false);
                String n=(i+1)+" / "+doc.getNumberOfPages();text(n,LEFT+WIDTH-width(n,8),33,8,GRAY,false);stream.close();stream=null;
            }
        }
    }
}
