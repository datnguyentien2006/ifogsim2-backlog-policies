package backfill.util;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/** Minimal streaming CSV writer (values are escaped; commas inside cells become semicolons). */
public final class Csv implements Closeable {

    private final PrintWriter out;
    public final String path;

    public Csv(String path) throws IOException {
        this.path = path;
        File f = new File(path);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        this.out = new PrintWriter(new BufferedWriter(new FileWriter(f)));
    }

    public Csv(String path, String header) throws IOException {
        this(path);
        if (header != null && !header.isEmpty()) out.println(header);
    }

    /** Append a pre-formatted CSV line (no escaping). */
    public void rawLine(String line) {
        out.println(line);
    }

    public void row(Object... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            String s = String.valueOf(cells[i]);
            if (s.indexOf(',') >= 0) s = s.replace(',', ';');
            sb.append(s);
        }
        out.println(sb);
    }

    @Override public void close() {
        out.flush();
        out.close();
    }
}
