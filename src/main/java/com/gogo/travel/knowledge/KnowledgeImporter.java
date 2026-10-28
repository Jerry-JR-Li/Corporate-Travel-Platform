package com.gogo.travel.knowledge;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.reader.ReaderInput;
import io.agentscope.core.rag.reader.WordReader;
import org.apache.poi.ss.usermodel.*;
import org.springframework.core.io.Resource;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Deterministic chunk IDs and source/model fingerprints make restart/import idempotent. */
public final class KnowledgeImporter {
    private KnowledgeImporter() {}
    public static String revision(Resource resource, String model, int dimensions) throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        try(InputStream in=resource.getInputStream()) { in.transferTo(new java.security.DigestOutputStream(OutputStream.nullOutputStream(),hash)); }
        hash.update(("parser-v1:"+model+":"+dimensions).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash.digest());
    }
    public static List<Document> read(Resource resource, String corpus) throws IOException {
        List<String> texts=new ArrayList<>();
        if (resource.getFilename()!=null && resource.getFilename().endsWith(".xlsx")) {
            try(InputStream in=resource.getInputStream(); Workbook workbook=WorkbookFactory.create(in)) {
                DataFormatter formatter=new DataFormatter();
                for(Sheet sheet:workbook) {
                    Row header=sheet.getRow(sheet.getFirstRowNum());
                    if(header==null) continue;
                    for(Row row:sheet) {
                        if(row.getRowNum()==header.getRowNum()) continue;
                        StringBuilder text=new StringBuilder();
                        for(int col=0;col<row.getLastCellNum();col++) {
                            String value=formatter.formatCellValue(row.getCell(col)).strip();
                            if(!value.isBlank()) text.append(formatter.formatCellValue(header.getCell(col))).append(": ").append(value).append("\n");
                        }
                        if(!text.isEmpty()) texts.add(text.toString());
                    }
                }
            }
        } else {
            for(Document doc:readWord(resource)) texts.add(doc.getMetadata().getContentText());
        }
        List<Document> result=new ArrayList<>();
        for(String text:texts) {
            // Bound chunks for embedding input size, with small overlap for context.
            for(int start=0;start<text.length();start+=1400) {
                String chunk=text.substring(start,Math.min(start+1600,text.length()));
                if(!chunk.isBlank()) result.add(new Document(new DocumentMetadata(TextBlock.builder().text(chunk).build(),
                        corpus,Integer.toString(result.size()),Map.of("source",Objects.requireNonNullElse(resource.getFilename(),corpus)))));
            }
        }
        return result;
    }
    public static List<Document> readWord(Resource resource) throws IOException {
        Path tmp=Files.createTempFile("travel-corpus-", ".docx");
        try(InputStream in=resource.getInputStream()) {
            Files.copy(in,tmp,StandardCopyOption.REPLACE_EXISTING);
            List<Document> docs=new WordReader().read(ReaderInput.fromPath(tmp)).block();
            return docs==null ? List.of() : docs;
        } finally { Files.deleteIfExists(tmp); }
    }
}
