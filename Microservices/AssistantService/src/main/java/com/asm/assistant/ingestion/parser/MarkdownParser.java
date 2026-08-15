package com.asm.assistant.ingestion.parser;

import com.asm.assistant.ingestion.model.ParsedChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Structure-aware Markdown chunker: splits on headings (H1–H3) so a chunk is one coherent section
 * with its heading trail as the citation label, then sub-splits any oversized section on paragraph
 * boundaries with a small overlap. YAML frontmatter is stripped. Code fences are kept intact — the
 * splitter never cuts inside a ``` block.
 */
@Component
public class MarkdownParser {

    @Value("${assistant.chunking.max-chars:2000}")
    private int maxChars;

    @Value("${assistant.chunking.overlap-chars:150}")
    private int overlapChars;

    public List<ParsedChunk> parse(String markdown) {
        // Normalize CRLF → LF first: docs are checked out with \r\n, and a trailing \r on each line
        // breaks both heading detection (^#{1,3}\s+.* won't cross \r) and paragraph splitting (\n\n
        // never matches \r\n\r\n) — collapsing a whole file into one chunk.
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        String body = stripFrontmatter(normalized);
        List<Section> sections = splitByHeading(body);
        List<ParsedChunk> chunks = new ArrayList<>();
        int ordinal = 0;
        for (Section s : sections) {
            String text = s.heading.isBlank() ? s.body : (s.heading + "\n\n" + s.body);
            if (text.isBlank()) continue;
            for (String piece : sizeSplit(text)) {
                chunks.add(new ParsedChunk(s.headingTrail(), ordinal++, piece.strip()));
            }
        }
        return chunks;
    }

    private String stripFrontmatter(String md) {
        if (md.startsWith("---")) {
            int end = md.indexOf("\n---", 3);
            if (end > 0) {
                int nl = md.indexOf('\n', end + 1);
                return nl > 0 ? md.substring(nl + 1) : "";
            }
        }
        return md;
    }

    private List<Section> splitByHeading(String body) {
        List<Section> sections = new ArrayList<>();
        String[] lines = body.split("\n", -1);
        StringBuilder buf = new StringBuilder();
        String h1 = "", currentHeading = "";
        boolean inFence = false;
        for (String line : lines) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("```")) inFence = !inFence;
            boolean isHeading = !inFence && line.matches("^#{1,3}\\s+.*");
            if (isHeading) {
                if (buf.length() > 0 || !currentHeading.isBlank()) {
                    sections.add(new Section(h1, currentHeading, buf.toString()));
                    buf.setLength(0);
                }
                int level = line.indexOf(' ');
                String text = line.substring(level).strip();
                currentHeading = text;
                if (line.startsWith("# ")) h1 = text;
            } else {
                buf.append(line).append('\n');
            }
        }
        if (buf.length() > 0 || !currentHeading.isBlank()) {
            sections.add(new Section(h1, currentHeading, buf.toString()));
        }
        return sections;
    }

    /** Split oversized text on blank lines, keeping a small tail overlap for context continuity. */
    private List<String> sizeSplit(String text) {
        List<String> out = new ArrayList<>();
        if (text.length() <= maxChars) { out.add(text); return out; }
        String[] paras = text.split("\n\n");
        StringBuilder cur = new StringBuilder();
        for (String para : paras) {
            if (cur.length() + para.length() + 2 > maxChars && cur.length() > 0) {
                out.add(cur.toString());
                String tail = cur.length() > overlapChars ? cur.substring(cur.length() - overlapChars) : cur.toString();
                cur = new StringBuilder(tail).append("\n\n");
            }
            cur.append(para).append("\n\n");
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private record Section(String h1, String heading, String body) {
        String headingTrail() {
            if (heading.isBlank()) return h1;
            if (h1.isBlank() || h1.equals(heading)) return heading;
            return h1 + " › " + heading;
        }
    }
}
